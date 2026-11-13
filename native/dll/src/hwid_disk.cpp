#include "hwid_disk.h"

#include <windows.h>
#include <winioctl.h>
#include <ntdddisk.h>
#include <ntddscsi.h>

#include <cstddef>
#include <atomic>
#include <cstring>
#include <mutex>

#include "openzen.h"
#include "iat_patch.h"
#include "spoof_values.h"

namespace hwid {

namespace {

constexpr BYTE kIdentifyCommand = 0xEC;
constexpr DWORD kIdentifyBlockBytes = 512;     // IDENTIFY DEVICE returns exactly one sector
constexpr size_t kIdentifySerialOffset = 20;   // words 10..19 of the IDENTIFY block
constexpr size_t kIdentifySerialBytes = 20;

using DeviceIoControl_t = BOOL (WINAPI*)(HANDLE, DWORD, LPVOID, DWORD,
                                        LPVOID, DWORD, LPDWORD, LPOVERLAPPED);
using GetVolumeInformationW_t = BOOL (WINAPI*)(LPCWSTR, LPWSTR, DWORD, LPDWORD,
                                               LPDWORD, LPDWORD, LPWSTR, DWORD);

DeviceIoControl_t g_real_ioctl = nullptr;
GetVolumeInformationW_t g_real_volume = nullptr;
std::mutex g_mtx;
bool g_ready = false;
std::atomic<bool> g_seen_identify{false};
std::atomic<bool> g_seen_descriptor{false};

void note_hit(const char* surface, std::atomic<bool>& flag) {
    if (!flag.exchange(true)) {
        openzen::log::info("HWID disk spoof: first %s read intercepted", surface);
    }
}

void write_identify_serial(BYTE* block) {
    note_hit("ATA IDENTIFY", g_seen_identify);
    unsigned char serial[kIdentifySerialBytes];
    disk_serial_identify(serial);
    std::memcpy(block + kIdentifySerialOffset, serial, kIdentifySerialBytes);
}

void patch_storage_descriptor(const void* in, DWORD in_len, void* out, DWORD out_len) {
    if (!in || in_len < sizeof(STORAGE_PROPERTY_QUERY)) return;
    if (!out || out_len <= sizeof(STORAGE_DEVICE_DESCRIPTOR)) return;
    auto* query = static_cast<const STORAGE_PROPERTY_QUERY*>(in);
    if (query->PropertyId != StorageDeviceProperty) return;
    if (query->QueryType != PropertyStandardQuery) return;

    auto* desc = static_cast<const STORAGE_DEVICE_DESCRIPTOR*>(out);
    DWORD offset = desc->SerialNumberOffset;
    if (offset == 0 || offset >= out_len) return;

    const std::string fake = narrow_ascii(values().disk_serial);
    if (fake.size() + 1 > out_len - offset) return;
    note_hit("storage descriptor", g_seen_descriptor);
    std::memcpy(static_cast<BYTE*>(out) + offset, fake.c_str(), fake.size() + 1);
}

void patch_smart_output(const void* in, DWORD in_len, void* out, DWORD out_len) {
    if (!in || in_len < sizeof(SENDCMDINPARAMS)) return;
    if (!out || out_len < offsetof(SENDCMDOUTPARAMS, bBuffer) + kIdentifySerialOffset
                           + kIdentifySerialBytes) {
        return;
    }
    auto* params = static_cast<const SENDCMDINPARAMS*>(in);
    if (params->irDriveRegs.bCommandReg != kIdentifyCommand) return;
    write_identify_serial(static_cast<SENDCMDOUTPARAMS*>(out)->bBuffer);
}

void patch_ata_passthrough(void* in, DWORD in_len, bool direct) {
    if (!in || in_len < sizeof(ATA_PASS_THROUGH_EX)) return;
    auto* ata = static_cast<ATA_PASS_THROUGH_EX*>(in);
    if (!(ata->AtaFlags & ATA_FLAGS_DATA_IN)) return;
    // Only a full one-sector IDENTIFY block qualifies. Requiring the exact
    // transfer size keeps a plain read (512 bytes, 0x20) from ever matching,
    // whichever byte of the task file this build treats as the command.
    if (ata->DataTransferLength != kIdentifyBlockBytes) return;
    if (ata->CurrentTaskFile[6] != kIdentifyCommand &&
        ata->CurrentTaskFile[1] != kIdentifyCommand) {
        return;
    }

    BYTE* data = direct
        ? reinterpret_cast<BYTE*>(static_cast<ULONG_PTR>(ata->DataBufferOffset))
        : reinterpret_cast<BYTE*>(ata) + ata->DataBufferOffset;
    if (!data) return;
    if (!direct && ata->DataBufferOffset + kIdentifyBlockBytes > in_len) return;
    write_identify_serial(data);
}

BOOL WINAPI HookDeviceIoControl(HANDLE device, DWORD code, LPVOID in, DWORD in_len,
                                LPVOID out, DWORD out_len, LPDWORD returned,
                                LPOVERLAPPED overlapped)
{
    BOOL ok = g_real_ioctl(device, code, in, in_len, out, out_len, returned, overlapped);
    // An overlapped call has not filled the buffer yet, and *returned is not
    // trustworthy, so there is nothing safe to patch.
    if (!ok || !returned || overlapped) return ok;

    DWORD valid = *returned;
    if (valid > out_len) valid = out_len;

    switch (code) {
    case IOCTL_STORAGE_QUERY_PROPERTY:
        patch_storage_descriptor(in, in_len, out, valid);
        break;
    case SMART_RCV_DRIVE_DATA:
        patch_smart_output(in, in_len, out, valid);
        break;
    case IOCTL_ATA_PASS_THROUGH:
        patch_ata_passthrough(in, in_len, false);
        break;
    case IOCTL_ATA_PASS_THROUGH_DIRECT:
        patch_ata_passthrough(in, in_len, true);
        break;
    default:
        break;
    }
    return ok;
}

BOOL WINAPI HookGetVolumeInformationW(LPCWSTR root, LPWSTR label, DWORD label_len,
                                      LPDWORD serial, LPDWORD max_component,
                                      LPDWORD flags, LPWSTR fs_name, DWORD fs_name_len)
{
    BOOL ok = g_real_volume(root, label, label_len, serial, max_component, flags,
                            fs_name, fs_name_len);
    if (!ok || !serial) return ok;
    *serial = mix_volume_serial(*serial);
    return ok;
}

} // namespace

int install_disk() {
    std::lock_guard<std::mutex> lk(g_mtx);
    if (g_ready) return 4;

    HMODULE kernel32 = GetModuleHandleA("kernel32.dll");
    if (!kernel32) return -1;

    if (!g_real_ioctl) {
        g_real_ioctl = reinterpret_cast<DeviceIoControl_t>(
            GetProcAddress(kernel32, "DeviceIoControl"));
    }
    if (!g_real_volume) {
        g_real_volume = reinterpret_cast<GetVolumeInformationW_t>(
            GetProcAddress(kernel32, "GetVolumeInformationW"));
    }
    if (!g_real_ioctl || !g_real_volume) return -1;

    values();

    int hooked = 0;
    hooked += install_import("KERNEL32.dll", "DeviceIoControl",
                             reinterpret_cast<void*>(&HookDeviceIoControl));
    hooked += install_import("KERNEL32.dll", "GetVolumeInformationW",
                             reinterpret_cast<void*>(&HookGetVolumeInformationW));
    if (hooked <= 0) return 0;

    g_ready = true;
    return 4;
}

} // namespace hwid
