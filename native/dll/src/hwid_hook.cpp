#include "hwid_hook.h"

#include <windows.h>
#include <psapi.h>

#include <atomic>
#include <cstring>
#include <mutex>
#include <random>
#include <string>
#include <vector>

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

// ---- per-run fake fingerprint set (stable within one process) -------------
std::wstring g_fake_guid;
std::wstring g_fake_product_id;
std::wstring g_fake_volume_serial;
std::wstring g_fake_computer_name;

std::mutex   g_mtx;
bool         g_active = false;

// ---- helpers --------------------------------------------------------------
std::wstring random_hex(size_t bytes) {
    static const wchar_t* kHex = L"0123456789ABCDEF";
    std::wstring s;
    s.reserve(bytes * 2);
    // RtlGenRandom (SystemFunction036) from advapi32 - no CSPRNG load.
    using RtlGenRandom_t = BOOLEAN(WINAPI*)(PVOID, ULONG);
    static RtlGenRandom_t pRtl = reinterpret_cast<RtlGenRandom_t>(
        GetProcAddress(GetModuleHandleA("advapi32.dll"), "SystemFunction036"));
    BYTE buf[64] = {0};
    if (pRtl && bytes <= sizeof buf) pRtl(buf, (ULONG)bytes);
    for (size_t i = 0; i < bytes; ++i) {
        uint8_t b = pRtl ? buf[i] : (uint8_t)(rand() & 0xFF);
        s.push_back(kHex[b >> 4]);
        s.push_back(kHex[b & 0xF]);
    }
    return s;
}

std::wstring random_guid() {
    // {XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX}
    auto h = random_hex(16);
    std::wstring g = L"{";
    g += h.substr(0, 8);  g += L"-";
    g += h.substr(8, 4);  g += L"-";
    g += h.substr(12,4);  g += L"-";
    g += h.substr(16,4);  g += L"-";
    g += h.substr(20,12);
    g += L"}";
    return g;
}

std::wstring random_product_id() {
    // "12345-OEM-1234567-12345" style - 5-3-7-5 digits.
    auto d = random_hex(5 + 1 + 3 + 1 + 7 + 1 + 5);
    // we just generate digits; keep it simple and numeric
    std::wstring s;
    const wchar_t* digits = L"0123456789";
    auto rnd = [&]() { return digits[(uint8_t)(rand() & 0xFF) % 10]; };
    auto part = [&](int n) {
        std::wstring p;
        for (int i = 0; i < n; ++i) p.push_back(rnd());
        return p;
    };
    return part(5) + L"-" + L"OEM" + L"-" + part(7) + L"-" + part(5);
}

std::wstring random_volume_serial() {
    return random_hex(4);   // 8 hex upper
}

std::wstring random_computer_name() {
    return L"PC" + random_hex(2);
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
LSTATUS WINAPI HookRegQueryValueExW(
    HKEY key, LPCWSTR name, LPDWORD reserved, LPDWORD type,
    LPBYTE data, LPDWORD cbData)
{
    // Call the real function first - if it fails, just pass through.
    LSTATUS rc = g_real(key, name, reserved, type, data, cbData);
    if (rc != ERROR_SUCCESS || !cbData) return rc;

    // Match the value name against fingerprint keys.
    const wchar_t* kNames[] = {
        L"MachineGuid",
        L"ProductId",
        L"VolumeSerialNumber",
        L"ComputerName",
        nullptr,
    };
    std::wstring valueName = name ? name : L"";
    for (int i = 0; kNames[i]; ++i) {
        if (_wcsicmp(valueName.c_str(), kNames[i]) != 0) continue;

        // Decide which fake to return.
        const std::wstring* fake = nullptr;
        if (_wcsicmp(kNames[i], L"MachineGuid") == 0) fake = &g_fake_guid;
        else if (_wcsicmp(kNames[i], L"ProductId") == 0) fake = &g_fake_product_id;
        else if (_wcsicmp(kNames[i], L"VolumeSerialNumber") == 0) fake = &g_fake_volume_serial;
        else if (_wcsicmp(kNames[i], L"ComputerName") == 0) fake = &g_fake_computer_name;
        if (!fake || fake->empty()) return rc;   // not generated -> pass through

        // size in bytes for REG_SZ
        DWORD need = (DWORD)((fake->size() + 1) * sizeof(wchar_t));
        if (data && *cbData >= need) {
            std::memcpy(data, fake->c_str(), need);
            *cbData = need;
            if (type) *type = REG_SZ;
            return ERROR_SUCCESS;
        }
        // caller buffer too small - report the needed size
        if (cbData) *cbData = need;
        return ERROR_MORE_DATA;
    }
    return rc;
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

    // Generate one stable fake set for this process run.
    g_fake_guid         = random_guid();
    g_fake_product_id   = random_product_id();
    g_fake_volume_serial= random_volume_serial();
    g_fake_computer_name= random_computer_name();

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

    // No importers found -> not active (silent). Also patch our own module if
    // it imports it (it probably doesn't, but harmless).
    return g_active;
}

bool active() {
    return g_active;
}

} // namespace hwid
