#include "hwid_firmware.h"

#include <windows.h>

#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "openzen.h"
#include "iat_patch.h"
#include "spoof_values.h"

namespace hwid {

namespace {

constexpr DWORD kRawSmbios = 0x52534D42;   // 'RSMB'
constexpr BYTE  kEndOfTable = 127;
constexpr size_t kMaxTableBytes = 4u << 20;

using GetSystemFirmwareTable_t = UINT (WINAPI*)(DWORD, DWORD, PVOID, DWORD);

GetSystemFirmwareTable_t g_real = nullptr;
std::atomic<bool> g_seen{false};
std::mutex g_mtx;
std::vector<BYTE> g_table;
int g_fields = 0;
bool g_ready = false;

struct Entry {
    std::vector<BYTE> formatted;
    std::vector<std::string> strings;
};

int hex_nibble(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
}

uint32_t hex32(const std::string& s, size_t at) {
    uint32_t v = 0;
    for (size_t i = 0; i < 8 && at + i < s.size(); ++i) {
        int n = hex_nibble(s[at + i]);
        if (n < 0) return 0;
        v = (v << 4) | static_cast<uint32_t>(n);
    }
    return v;
}

void put_le32(BYTE* dst, uint32_t v) {
    dst[0] = static_cast<BYTE>(v);
    dst[1] = static_cast<BYTE>(v >> 8);
    dst[2] = static_cast<BYTE>(v >> 16);
    dst[3] = static_cast<BYTE>(v >> 24);
}

bool parse(const std::vector<BYTE>& raw, size_t start, std::vector<Entry>& out, size_t& consumed) {
    const size_t size = raw.size();
    size_t off = start;
    while (off + 4 <= size) {
        BYTE len = raw[off + 1];
        if (len < 4 || off + len > size) return false;

        Entry e;
        e.formatted.assign(raw.begin() + off, raw.begin() + off + len);

        // The string-set terminator shares its first NUL with the last
        // string's own terminator, so the set ends where a new string would
        // start: at a NUL byte, never at a double-NUL scan.
        size_t p = off + len;
        if (p >= size) return false;
        if (raw[p] == 0) {
            p += (p + 1 < size && raw[p + 1] == 0) ? 2 : 1;
        } else {
            while (p < size && raw[p] != 0) {
                size_t n = 0;
                while (p + n < size && raw[p + n] != 0) ++n;
                if (p + n >= size) return false;
                e.strings.emplace_back(reinterpret_cast<const char*>(&raw[p]), n);
                p += n + 1;
            }
            if (p >= size) return false;
            ++p;
        }

        BYTE type = e.formatted[0];
        out.push_back(std::move(e));
        off = p;
        if (type == kEndOfTable) {
            consumed = off;
            return true;
        }
    }
    // Some firmwares ship no end-of-table structure at all; a walk that lands
    // exactly on the end of the buffer is just as conclusive.
    if (off == size && !out.empty()) {
        consumed = off;
        return true;
    }
    return false;
}

void serialize(const std::vector<Entry>& entries, std::vector<BYTE>& out) {
    for (const Entry& e : entries) {
        out.insert(out.end(), e.formatted.begin(), e.formatted.end());
        for (const std::string& s : e.strings) {
            out.insert(out.end(), s.begin(), s.end());
            out.push_back(0);
        }
        out.push_back(0);
        if (e.strings.empty()) out.push_back(0);
    }
}

void set_string(Entry& e, size_t field_off, const std::string& value) {
    if (e.formatted.size() <= field_off) return;
    BYTE index = e.formatted[field_off];
    if (index == 0 || index > e.strings.size()) {
        e.strings.push_back(value);
        e.formatted[field_off] = static_cast<BYTE>(e.strings.size());
        return;
    }
    e.strings[index - 1] = value;
}

int rebuild(const std::vector<BYTE>& raw, std::vector<BYTE>& out) {
    std::vector<Entry> entries;
    size_t consumed = 0;
    size_t start = 0;

    // Windows hands back the registry blob verbatim on some builds, and that
    // blob carries an 8-byte header in front of the structures.
    if (!parse(raw, 0, entries, consumed)) {
        entries.clear();
        if (raw.size() < 8 || !parse(raw, 8, entries, consumed)) return 0;
        start = 8;
    }

    const SpoofValues& v = values();
    const std::string board = narrow_ascii(v.baseboard_serial);
    const std::string system = narrow_ascii(v.system_serial);

    unsigned char uuid[16];
    product_uuid_bytes(uuid);

    const std::string proc = narrow_ascii(v.processor_id);
    unsigned char processor[8] = {0};
    put_le32(processor, hex32(proc, 8));
    put_le32(processor + 4, hex32(proc, 0));

    int changed = 0;
    for (Entry& e : entries) {
        switch (e.formatted[0]) {
        // Type 0 carries no serial number: Win32_BIOS.SerialNumber is the
        // Type 1 field, and offset 0x07 there is the BIOS segment high byte.
        case 1:
            set_string(e, 0x07, system);
            if (e.formatted.size() >= 0x18) std::memcpy(&e.formatted[0x08], uuid, 16);
            ++changed;
            break;
        case 2:
            set_string(e, 0x07, board);
            ++changed;
            break;
        case 4:
            if (e.formatted.size() >= 0x10) {
                std::memcpy(&e.formatted[0x08], processor, 8);
                ++changed;
            }
            break;
        default:
            break;
        }
    }

    std::vector<BYTE> structures;
    serialize(entries, structures);
    out.assign(raw.begin(), raw.begin() + start);
    out.insert(out.end(), structures.begin(), structures.end());
    out.insert(out.end(), raw.begin() + consumed, raw.end());
    return changed;
}

UINT WINAPI HookGetSystemFirmwareTable(DWORD provider, DWORD table_id, PVOID buffer, DWORD size) {
    if (provider != kRawSmbios || table_id != 0 || !g_real) {
        return g_real(provider, table_id, buffer, size);
    }

    std::lock_guard<std::mutex> lk(g_mtx);
    if (g_table.empty()) return g_real(provider, table_id, buffer, size);
    if (!g_seen.exchange(true)) {
        openzen::log::info("HWID SMBIOS spoof: first GetSystemFirmwareTable intercepted");
    }

    DWORD need = static_cast<DWORD>(g_table.size());
    if (!buffer) return need;
    if (size < need) {
        SetLastError(ERROR_INSUFFICIENT_BUFFER);
        return 0;
    }
    std::memcpy(buffer, g_table.data(), need);
    return need;
}

} // namespace

int install_firmware() {
    std::lock_guard<std::mutex> lk(g_mtx);
    if (g_ready) return g_fields;

    if (g_table.empty()) {
        HMODULE kernel32 = GetModuleHandleA("kernel32.dll");
        if (!kernel32) return -1;
        g_real = reinterpret_cast<GetSystemFirmwareTable_t>(
            GetProcAddress(kernel32, "GetSystemFirmwareTable"));
        if (!g_real) return -1;

        DWORD size = g_real(kRawSmbios, 0, nullptr, 0);
        if (size == 0 || size > kMaxTableBytes) return -1;

        std::vector<BYTE> raw(size);
        if (g_real(kRawSmbios, 0, raw.data(), size) != size) return -1;

        std::vector<BYTE> rebuilt;
        int changed = rebuild(raw, rebuilt);
        if (rebuilt.empty()) return -1;
        g_table.swap(rebuilt);
        g_fields = changed;
    }

    if (install_import("KERNEL32.dll", "GetSystemFirmwareTable",
                       reinterpret_cast<void*>(&HookGetSystemFirmwareTable)) <= 0) {
        return 0;
    }
    g_ready = true;
    return g_fields;
}

} // namespace hwid
