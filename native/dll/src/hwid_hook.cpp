#include "hwid_hook.h"

#include <windows.h>
#include <winternl.h>
#include <psapi.h>

#include <atomic>
#include <cstring>
#include <cwchar>
#include <mutex>
#include <string>
#include <vector>

#include "spoof_values.h"

#pragma comment(lib, "psapi.lib")

namespace hwid {

// Number of IAT slots patched (for logging / verification).
std::atomic<LONG> g_patch_count{0};

namespace {

// ---- the real RegQueryValueExW we route to after spoofing -----------------
typedef LSTATUS (WINAPI* RegQueryValueExW_t)(
    HKEY, LPCWSTR, LPDWORD, LPDWORD, LPBYTE, LPDWORD);

// Loaded in install().
HMODULE g_advapi = nullptr;
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

// ---- IAT patch ------------------------------------------------------------
// Find a module in the target that imports RegQueryValueExW from advapi32,
// and rewrite its IAT slot to point at our hook.
bool patch_module_iat(HMODULE mod, RegQueryValueExW_t hook) {
    // Enumerate imports of `mod`, look for advapi32!RegQueryValueExW.
    auto dos = reinterpret_cast<const IMAGE_DOS_HEADER*>(mod);
    if (!dos || dos->e_magic != IMAGE_DOS_SIGNATURE) return false;
    auto nt = reinterpret_cast<const IMAGE_NT_HEADERS*>(
        reinterpret_cast<const BYTE*>(mod) + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE) return false;

    const auto& dir = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT];
    if (dir.Size == 0) return false;

    auto desc = reinterpret_cast<const IMAGE_IMPORT_DESCRIPTOR*>(
        reinterpret_cast<const BYTE*>(mod) + dir.VirtualAddress);

    while (desc->Name) {
        const char* dllName = reinterpret_cast<const char*>(
            reinterpret_cast<const BYTE*>(mod) + desc->Name);
        if (_stricmp(dllName, "advapi32.dll") != 0) { ++desc; continue; }

        // Without the import-name table the IAT slots hold resolved addresses, not
        // name RVAs - walking them as names reads garbage (and can fault). Skip.
        if (!desc->OriginalFirstThunk) { ++desc; continue; }

        IMAGE_THUNK_DATA* thunk = reinterpret_cast<IMAGE_THUNK_DATA*>(
            reinterpret_cast<BYTE*>(mod) + desc->OriginalFirstThunk);
        IMAGE_THUNK_DATA* iat = reinterpret_cast<IMAGE_THUNK_DATA*>(
            reinterpret_cast<BYTE*>(mod) + desc->FirstThunk);

        bool patched = false;
        while (thunk->u1.AddressOfData) {
            if (IMAGE_SNAP_BY_ORDINAL(thunk->u1.Ordinal)) { ++thunk; ++iat; continue; }
            auto byName = reinterpret_cast<const IMAGE_IMPORT_BY_NAME*>(
                reinterpret_cast<const BYTE*>(mod) + thunk->u1.AddressOfData);
            if (_stricmp(byName->Name, "RegQueryValueExW") == 0) {
                DWORD oldProtect = 0;
                if (VirtualProtect(iat, sizeof(IMAGE_THUNK_DATA),
                                   PAGE_READWRITE, &oldProtect)) {
                    iat->u1.Function =
                        reinterpret_cast<ULONGLONG>(hook);
                    VirtualProtect(iat, sizeof(IMAGE_THUNK_DATA),
                                   oldProtect, &oldProtect);
                    FlushInstructionCache(GetCurrentProcess(),
                                          iat, sizeof(IMAGE_THUNK_DATA));
                    patched = true;
                    ++g_patch_count;
                }
            }
            ++thunk; ++iat;
        }
        if (patched) return true;
        ++desc;
    }
    return false;
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
    return g_real(key, name, reserved, type, data, cbData);
}

} // namespace

bool install() {
    std::lock_guard<std::mutex> lk(g_mtx);
    if (g_active) return true;

    g_advapi = GetModuleHandleA("advapi32.dll");
    if (!g_advapi) return false;
    g_real = reinterpret_cast<RegQueryValueExW_t>(
        GetProcAddress(g_advapi, "RegQueryValueExW"));
    if (!g_real) return false;

    // Generate the per-process set before the hook can be reached, so a first
    // read from another thread never pays for the initialisation.
    values();

    // Patch every loaded module that imports RegQueryValueExW from advapi32.
    HMODULE mods[512];
    DWORD cb = 0;
    if (EnumProcessModules(GetCurrentProcess(), mods, sizeof mods, &cb)) {
        DWORD n = cb / sizeof(HMODULE);
        for (DWORD i = 0; i < n; ++i) {
            if (patch_module_iat(mods[i], HookRegQueryValueExW)) {
                g_active = true;
            }
        }
    }

    // No importers found -> not active (silent).
    return g_active;
}

bool active() {
    return g_active;
}

} // namespace hwid
