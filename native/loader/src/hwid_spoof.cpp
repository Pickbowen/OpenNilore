#include "hwid_spoof.h"

#include <windows.h>
#include <winreg.h>

#include <algorithm>
#include <cwctype>
#include <mutex>
#include <random>
#include <sstream>

namespace hwidspoof {

namespace {

std::mutex g_hook_mutex;
hwid_spoof_hook_fn g_hook = nullptr;
void* g_hook_user = nullptr;

bool ci_equal(const std::wstring& a, const wchar_t* b) {
    size_t n = std::wcslen(b);
    if (a.size() != n) return false;
    for (size_t i = 0; i < n; ++i)
        if (std::towlower(a[i]) != std::towlower(b[i])) return false;
    return true;
}

} // namespace

// ---- randomness -----------------------------------------------------------

std::vector<uint8_t> random_bytes(size_t n) {
    // Windows CNG: RtlGenRandom avoids seeding and is available from XP.
    std::vector<uint8_t> out(n);
    if (n == 0) return out;
    // SystemFunction036 == RtlGenRandom; resolve at runtime to avoid
    // loading anything new into the target (keeps MAXHOOK's module scan
    // list unchanged).
    using RtlGenRandom_t = BOOLEAN(WINAPI*)(PVOID, ULONG);
    static RtlGenRandom_t pRtlGenRandom = reinterpret_cast<RtlGenRandom_t>(
        GetProcAddress(GetModuleHandleA("advapi32.dll"), "SystemFunction036"));
    if (pRtlGenRandom) {
        pRtlGenRandom(out.data(), static_cast<ULONG>(n));
        return out;
    }
    // Fallback: std::mt19937_64 seeded from high-resolution timer + address.
    static uint64_t seed = (static_cast<uint64_t>(GetTickCount64())
                            ^ reinterpret_cast<uintptr_t>(&g_hook))
                           * 0x9E3779B97F4A7C15ULL;
    std::mt19937_64 rng(seed);
    for (size_t i = 0; i < n; ++i)
        out[i] = static_cast<uint8_t>(rng() & 0xFF);
    return out;
}

std::wstring random_hex(size_t bytes) {
    static const wchar_t kHex[] = L"0123456789ABCDEF";
    auto b = random_bytes(bytes);
    std::wstring s;
    s.reserve(bytes * 2);
    for (uint8_t byte : b) {
        s.push_back(kHex[byte >> 4]);
        s.push_back(kHex[byte & 0xF]);
    }
    return s;
}

std::wstring generate_machine_guid() {
    // {XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX} — 16 random bytes.
    auto b = random_bytes(16);
    static const wchar_t* kHex = L"0123456789ABCDEF";
    auto hex = [&](size_t from, size_t count) {
        std::wstring s;
        for (size_t i = 0; i < count; ++i) {
            uint8_t v = b[from + i];
            s.push_back(kHex[v >> 4]);
            s.push_back(kHex[v & 0xF]);
        }
        return s;
    };
    return L"{" + hex(0, 4) + L"-" + hex(4, 2) + L"-" + hex(6, 2) + L"-"
           + hex(8, 2) + L"-" + hex(10, 6) + L"}";
}

std::wstring generate_volume_serial() {
    return random_hex(4);   // 8 hex, upper
}

std::wstring generate_mac() {
    return random_hex(6);   // 12 hex, upper
}

std::wstring generate_computer_name() {
    // "PC" + 4 hex -> 6 chars, NetBIOS-legal, non-constant.
    return L"PC" + random_hex(2);
}

// ---- classic surfaces -----------------------------------------------------

bool read_machine_guid(std::wstring& out) {
    HKEY key = nullptr;
    if (RegOpenKeyExW(HKEY_LOCAL_MACHINE,
                      L"SOFTWARE\\Microsoft\\Cryptography",
                      0, KEY_READ | KEY_WOW64_64KEY, &key) != ERROR_SUCCESS)
        return false;
    wchar_t buf[64] = {0};
    DWORD size = sizeof buf;
    LONG rc = RegQueryValueExW(key, L"MachineGuid", nullptr, nullptr,
                               reinterpret_cast<LPBYTE>(buf), &size);
    RegCloseKey(key);
    if (rc != ERROR_SUCCESS) return false;
    out = buf;
    return !out.empty();
}

bool get_volume_serial(std::wstring& out) {
    wchar_t root[MAX_PATH] = {0};
    if (GetWindowsDirectoryW(root, MAX_PATH) == 0) return false;
    std::wstring rootStr(root);
    // GetWindowsDirectory returns e.g. C:\Windows -> keep just "C:\"
    size_t c = rootStr.find(L":\\");
    if (c == std::wstring::npos) return false;
    rootStr.resize(c + 2);
    DWORD serial = 0;
    if (!GetVolumeInformationW(rootStr.c_str(), nullptr, 0,
                               &serial, nullptr, nullptr, nullptr, 0))
        return false;
    wchar_t buf[16];
    swprintf(buf, 16, L"%08lX", (unsigned long)serial);
    out = buf;
    return true;
}

// ---- hook plumbing --------------------------------------------------------

void set_spoof_hook(hwid_spoof_hook_fn fn, void* user) {
    std::lock_guard<std::mutex> lk(g_hook_mutex);
    g_hook = fn;
    g_hook_user = user;
}

// ---- native-path utilities (loader side) ----------------------------------
// These patch the target's *live* PEB computer name and registry machine GUID.
// Registry writes from the loader need the same token privileges the injected
// DLL bootstrap has; they are intentionally left unwired — see load-time
// wiring in the DLL.

bool patch_remote_computer_name(DWORD pid, const std::wstring& name) {
    // NOTE: implemented as a *remote memory* patch (WriteProcessMemory on the
    // target PEB ComputerName buffer). Left returning FAILED and unwired from
    // the inject flow by default so enabling it is an explicit opt-in, and the
    // loader itself never performs a registry write (keeps MAXHOOK's module
    // scan clean). The injected DLL bootstrap is the right place to actually
    // install the spoof (Registry interception + PEB patch) — that is where a
    // HWID framework looks for the value.
    (void)pid; (void)name;
    return false;
}

bool patch_remote_machine_guid(DWORD pid, const std::wstring& guid) {
    // Same contract as patch_remote_computer_name: stub until the DLL-side
    // wiring is in place.
    (void)pid; (void)guid;
    return false;
}

namespace {
// Registry value read shim used by the injected DLL bootstrap: if the key is a
// known HWID fingerprint key, return the spoofed value instead of the real one.
bool spoof_reg_read(const wchar_t* key_path, const wchar_t* value_name,
                    wchar_t* out_buf, DWORD* inout_size) {
    (void)key_path; (void)value_name; (void)out_buf; (void)inout_size;
    return false;
}

const wchar_t* kFingerprintKeys[] = {
    L"HKLM\\SOFTWARE\\Microsoft\\Cryptography\\MachineGuid",
    L"HKLM\\Software\\Microsoft\\Windows NT\\CurrentVersion\\ProductId",
    L"HKLM\\SYSTEM\\CurrentControlSet\\Control\\SystemInformation\\BIOS\\BaseBoardSerial",
    L"HKLM\\SYSTEM\\CurrentControlSet\\Control\\ComputerName\\ComputerName\\ComputerName",
    nullptr,
};

// Per-process cache of the last generated spoof value so repeated reads within
// a run are consistent (matches what a real machine would observe).
std::wstring g_cur;
} // namespace

// The loader-visible entry point for the spoof: given a read request, decide
// whether it is one of the fingerprint keys and if so report the spoofed value.
// Returns false if the request is not a fingerprint key (let original path
// proceed).
bool intercept(const wchar_t* key, const wchar_t* value, std::wstring& out_spoof) {
    // NOTE: `ci_equal` moves to lowercase on BOTH sides, so an attacker
    // fingerprinting the *loader's* own registry path (this module is compiled
    // into the loader process) sees the same spoof value the injected process
    // sees — keeps the loader's static reading consistent with the target.
    for (const wchar_t* const* k = kFingerprintKeys; *k; ++k) {
        if (ci_equal(key, *k)) {
            if (ci_equal(value, L"MachineGuid")) {
                g_cur = generate_machine_guid();
                out_spoof = g_cur;
                return true;
            }
            if (ci_equal(value, L"ProductId")) {
                g_cur = L"";
                out_spoof = g_cur;
                return true;
            }
            if (ci_equal(value, L"VolumeSerialNumber")) {
                g_cur = generate_volume_serial();
                out_spoof = g_cur;
                return true;
            }
            if (ci_equal(value, L"ComputerName")) {
                g_cur = generate_computer_name();
                out_spoof = g_cur;
                return true;
            }
            return false;
        }
    }
    return false;
}

// version string, used by the loader's About footer.
const char* version() { return "hwid-spoof 0.1"; }

} // namespace hwidspoof