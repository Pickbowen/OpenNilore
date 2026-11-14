#include "hwid_hook.h"

#include <windows.h>
#include <winternl.h>

#include <atomic>
#include <cstring>
#include <cwchar>
#include <mutex>
#include <string>
#include <vector>

#include "iat_patch.h"
#include "spoof_values.h"

namespace hwid {

// Number of IAT slots patched (for logging / verification).
std::atomic<LONG> g_patch_count{0};

namespace {

// ---- the real RegQueryValueExW we route to after spoofing -----------------
typedef LSTATUS (WINAPI* RegQueryValueExW_t)(
    HKEY, LPCWSTR, LPDWORD, LPDWORD, LPBYTE, LPDWORD);

// Resolved on first use: at injection time advapi32 may not be loaded yet, and
// a hook that cannot be armed yet must still be registered for later modules.
RegQueryValueExW_t g_real = nullptr;

std::mutex g_mtx;
bool       g_active = false;

// ---- registry key path -----------------------------------------------------
// Matching on the value name alone rewrote any unrelated key that happened to
// hold a "ProductId" or "ComputerName" value. NtQueryKey gives the opened key's
// full path so the read can be tied to the one location Windows stores it in.

typedef NTSTATUS (NTAPI* NtQueryKey_t)(HANDLE, DWORD, PVOID, ULONG, PULONG);
constexpr DWORD kKeyNameInformation = 3;   // KEY_INFORMATION_CLASS::KeyNameInformation

struct KeyNameInfo {
    ULONG NameLength;
    WCHAR Name[1];
};

NtQueryKey_t nt_query_key() {
    static NtQueryKey_t fn = reinterpret_cast<NtQueryKey_t>(
        GetProcAddress(GetModuleHandleA("ntdll.dll"), "NtQueryKey"));
    return fn;
}

bool key_path_matches(HKEY key, const wchar_t* suffix) {
    NtQueryKey_t fn = nt_query_key();
    if (!fn || !key) return true;   // cannot verify -> keep the historical behaviour
    ULONG size = 0;
    fn(key, kKeyNameInformation, nullptr, 0, &size);
    if (size == 0 || size > 8 * 1024) return true;
    std::vector<BYTE> buf(size + sizeof(WCHAR) * 2);
    ULONG got = 0;
    if (fn(key, kKeyNameInformation, buf.data(), static_cast<ULONG>(buf.size()), &got) < 0) {
        return true;
    }
    auto* info = reinterpret_cast<KeyNameInfo*>(buf.data());
    if (info->NameLength == 0) return true;
    std::wstring path(info->Name, info->NameLength / sizeof(WCHAR));
    size_t n = wcslen(suffix);
    if (path.size() < n) return false;
    return _wcsicmp(path.c_str() + (path.size() - n), suffix) == 0;
}

// ---- fingerprint dispatch -------------------------------------------------
const std::wstring* pick_fake(HKEY key, LPCWSTR name) {
    const SpoofValues& v = values();
    if (_wcsicmp(name, L"MachineGuid") == 0) {
        return key_path_matches(key, L"\\REGISTRY\\MACHINE\\SOFTWARE\\MICROSOFT\\CRYPTOGRAPHY")
                ? &v.machine_guid : nullptr;
    }
    if (_wcsicmp(name, L"ProductId") == 0) {
        return key_path_matches(key, L"\\WINDOWS NT\\CURRENTVERSION")
                ? &v.product_id : nullptr;
    }
    if (_wcsicmp(name, L"ComputerName") == 0) {
        return key_path_matches(key, L"\\CONTROL\\COMPUTERNAME\\COMPUTERNAME")
                ? &v.computer_name : nullptr;
    }
    if (_wcsicmp(name, L"VolumeSerialNumber") == 0) {
        return key_path_matches(key, L"\\REGISTRY\\MACHINE\\SYSTEM\\MOUNTEDDEVICES")
                ? &v.volume_serial : nullptr;
    }
    return nullptr;
}

RegQueryValueExW_t real_reg_query();

// MaxHook reads MachineGuid through RegGetValueW, whose implementation never
// calls RegQueryValueExW across the IAT, so the registry hook has to cover
// both entry points.
using RegGetValueW_t = LSTATUS (WINAPI*)(HKEY, LPCWSTR, LPCWSTR, DWORD,
                                         LPDWORD, PVOID, LPDWORD);
RegGetValueW_t g_real_reg_get = nullptr;

RegGetValueW_t real_reg_get() {
    if (g_real_reg_get) return g_real_reg_get;
    HMODULE advapi = GetModuleHandleA("advapi32.dll");
    if (!advapi) return nullptr;
    g_real_reg_get = reinterpret_cast<RegGetValueW_t>(
        GetProcAddress(advapi, "RegGetValueW"));
    return g_real_reg_get;
}

bool subkey_ends_with(const wchar_t* subkey, const wchar_t* suffix) {
    if (!subkey || !suffix) return false;
    size_t a = wcslen(subkey);
    size_t b = wcslen(suffix);
    if (a < b) return false;
    return _wcsicmp(subkey + (a - b), suffix) == 0;
}

const std::wstring* pick_fake_subkey(const wchar_t* subkey, LPCWSTR name) {
    const SpoofValues& v = values();
    if (_wcsicmp(name, L"MachineGuid") == 0) {
        return subkey_ends_with(subkey, L"SOFTWARE\\MICROSOFT\\CRYPTOGRAPHY")
                   ? &v.machine_guid : nullptr;
    }
    if (_wcsicmp(name, L"ProductId") == 0) {
        return subkey_ends_with(subkey, L"SOFTWARE\\MICROSOFT\\WINDOWS NT\\CURRENTVERSION")
                   ? &v.product_id : nullptr;
    }
    if (_wcsicmp(name, L"ComputerName") == 0) {
        return subkey_ends_with(subkey, L"COMPUTERNAME\\COMPUTERNAME")
                   ? &v.computer_name : nullptr;
    }
    return nullptr;
}

LSTATUS WINAPI HookRegGetValueW(HKEY key, LPCWSTR subkey, LPCWSTR name, DWORD flags,
                                LPDWORD type, PVOID data, LPDWORD cbData)
{
    if (name && subkey) {
        const std::wstring* fake = pick_fake_subkey(subkey, name);
        if (fake && !fake->empty()) {
            DWORD need = static_cast<DWORD>((fake->size() + 1) * sizeof(wchar_t));
            if (type) *type = REG_SZ;
            if (!data) {
                if (cbData) *cbData = need;
                return ERROR_SUCCESS;
            }
            if (cbData && *cbData >= need) {
                std::memcpy(data, fake->c_str(), need);
                *cbData = need;
                return ERROR_SUCCESS;
            }
            if (cbData) *cbData = need;
            return ERROR_MORE_DATA;
        }
    }
    RegGetValueW_t real = real_reg_get();
    if (!real) return ERROR_PROC_NOT_FOUND;
    return real(key, subkey, name, flags, type, data, cbData);
}

// ---- the hook -------------------------------------------------------------
// Answered entirely from the fake set, without calling the real function: the
// old order (real call first, replace after) leaked the real length on the
// size-probe pass, and a fake value longer than the real one could never be
// returned to a caller that allocates exactly what the probe reported.
LSTATUS WINAPI HookRegQueryValueExW(
    HKEY key, LPCWSTR name, LPDWORD reserved, LPDWORD type,
    LPBYTE data, LPDWORD cbData)
{
    if (name) {
        const std::wstring* fake = pick_fake(key, name);
        if (fake && !fake->empty()) {
            DWORD need = static_cast<DWORD>((fake->size() + 1) * sizeof(wchar_t));
            if (type) *type = REG_SZ;
            if (!data) {
                if (cbData) *cbData = need;
                return ERROR_SUCCESS;
            }
            if (cbData && *cbData >= need) {
                std::memcpy(data, fake->c_str(), need);
                *cbData = need;
                return ERROR_SUCCESS;
            }
            if (cbData) *cbData = need;
            return ERROR_MORE_DATA;
        }
    }
    RegQueryValueExW_t real = real_reg_query();
    if (!real) return ERROR_PROC_NOT_FOUND;
    return real(key, name, reserved, type, data, cbData);
}

RegQueryValueExW_t real_reg_query() {
    if (g_real) return g_real;
    HMODULE advapi = GetModuleHandleA("advapi32.dll");
    if (!advapi) return nullptr;
    g_real = reinterpret_cast<RegQueryValueExW_t>(
        GetProcAddress(advapi, "RegQueryValueExW"));
    return g_real;
}

} // namespace

int install() {
    std::lock_guard<std::mutex> lk(g_mtx);
    if (g_active) return g_patch_count;

    // Generate the per-process set before the hook can be reached, so a first
    // read from another thread never pays for the initialisation.
    values();

    int patched = install_import("ADVAPI32.dll", "RegQueryValueExW",
                                 reinterpret_cast<void*>(&HookRegQueryValueExW));
    patched += install_import("ADVAPI32.dll", "RegGetValueW",
                              reinterpret_cast<void*>(&HookRegGetValueW));
    g_patch_count = patched;
    g_active = patched > 0;
    return patched;
}

bool active() {
    return g_active;
}

} // namespace hwid
