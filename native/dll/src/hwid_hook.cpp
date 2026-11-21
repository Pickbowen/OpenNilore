#include "hwid_hook.h"

#include <windows.h>
#include <winternl.h>

#include <atomic>
#include <cstring>
#include <cwchar>
#include <mutex>
#include <string>
#include <vector>

#include "openzen.h"
#include "iat_patch.h"
#include "inline_hook.h"
#include "nt_guard.h"
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

// Hook the *body* of a registry API instead of the import tables.
//
// The anticheat in this process resolves APIs itself: it walks the PEB module
// list, hashes every export name with fnv-1a (per-site random basis) and calls
// the address it gets. A pointer obtained that way never passes through any
// module's import table, so every IAT slot we patch is invisible to it - it
// keeps reading the real value while the game and the JVM see the fake.
//
// On Windows 10+ the advapi32 export is a forwarder (`jmp kernelbase!...`),
// which cannot carry a 5-byte rel32 and would be pointless to patch anyway, so
// the implementation in kernelbase is the target. ntguard keeps the first hop
// and the trampoline inside ntdll's image: a prologue check of the hooked
// function then reads as an ordinary kernel32 -> ntdll jump, and a VirtualQuery
// sweep finds no private executable page.
bool hook_registry_body(const char* name, void* hook, void** original) {
    const char* const hosts[] = {"kernelbase.dll", "advapi32.dll"};

    for (const char* host : hosts) {
        HMODULE mod = GetModuleHandleA(host);
        if (!mod) continue;
        void* target = reinterpret_cast<void*>(GetProcAddress(mod, name));
        if (!target) continue;
        if (ntguard::hook(target, hook, original)) {
            openzen::log::info("HWID reg spoof: ntdll-relay hook on %s!%s (%p)",
                               host, name, target);
            return true;
        }
    }

    // Second choice: a plain inline hook. It still covers self-resolving
    // callers, at the cost of a private trampoline page.
    for (const char* host : hosts) {
        HMODULE mod = GetModuleHandleA(host);
        if (!mod) continue;
        void* target = reinterpret_cast<void*>(GetProcAddress(mod, name));
        if (!target) continue;
        if (install_inline_hook(target, hook, original)) {
            openzen::log::info("HWID reg spoof: inline hook on %s!%s (%p), "
                               "private trampoline", host, name, target);
            return true;
        }
    }
    return false;
}

} // namespace

int install() {
    std::lock_guard<std::mutex> lk(g_mtx);
    if (g_active) return g_patch_count;

    // Generate the per-process set before the hook can be reached, so a first
    // read from another thread never pays for the initialisation.
    values();

    int hooked = 0;
    void* trampoline = nullptr;

    // Body hooks first: only these cover a caller that resolved the API through
    // the PEB export tables instead of an import table.
    if (hook_registry_body("RegQueryValueExW",
                           reinterpret_cast<void*>(&HookRegQueryValueExW), &trampoline)) {
        g_real = reinterpret_cast<RegQueryValueExW_t>(trampoline);
        ++hooked;
    }
    trampoline = nullptr;
    if (hook_registry_body("RegGetValueW",
                           reinterpret_cast<void*>(&HookRegGetValueW), &trampoline)) {
        g_real_reg_get = reinterpret_cast<RegGetValueW_t>(trampoline);
        ++hooked;
    }

    // Whatever the body hook could not take still gets the import-table patch,
    // which at least covers the game and the JVM. Never both for one function:
    // a body hook plus an IAT hook on the same callee would forward into the
    // trampoline twice.
    if (!g_real) {
        hooked += install_import("ADVAPI32.dll", "RegQueryValueExW",
                                 reinterpret_cast<void*>(&HookRegQueryValueExW));
    }
    if (!g_real_reg_get) {
        hooked += install_import("ADVAPI32.dll", "RegGetValueW",
                                 reinterpret_cast<void*>(&HookRegGetValueW));
    }

    g_patch_count = hooked;
    g_active = hooked > 0;
    return hooked;
}

bool active() {
    return g_active;
}

} // namespace hwid
