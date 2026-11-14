#include "hwid_config.h"

#include <windows.h>

#include <cstring>
#include <cwchar>

#include "openzen.h"

namespace hwid {

namespace {

std::wstring temp_ini_path() {
    wchar_t tmp[MAX_PATH] = {0};
    if (GetTempPathW(MAX_PATH, tmp) == 0) return std::wstring();
    return std::wstring(tmp) + L"openzen_hwid.ini";
}

// Written on first run so the file exists to be edited, and so the defaults are
// documented where the user will actually look for them.
void write_default_ini(const std::wstring& path) {
    static const wchar_t kBody[] =
        L"; OpenZen HWID spoof - edit, then restart the game to apply.\n"
        L"[hwid]\n"
        L"; 1 = on, 0 = off (0 is the clean baseline for testing)\n"
        L"enabled=1\n"
        L"\n"
        L"; wmi  = UUID / disk serial / board serial\n"
        L";        via the wbemcomn vtable, so the anticheat reads the fake too\n"
        L"; net  = MAC address, via an inline hook on GetAdaptersAddresses\n"
        L"; reg / smbios / disk / wmipx = legacy surfaces, off by default\n"
        L"surfaces=wmi,net\n"
        L"\n"
        L"; 1 = intercept reads instead of writing properties.\n"
        L"; Switch to 1 if the log shows 'Put rejected ... switching to Get'\n"
        L"force_get_hook=0\n"
        L"\n"
        L"; 1 = log every hook decision\n"
        L"verbose=0\n";

    HANDLE h = CreateFileW(path.c_str(), GENERIC_WRITE, FILE_SHARE_READ, nullptr,
                           CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) return;
    const BYTE bom[2] = {0xFF, 0xFE};   // UTF-16LE
    DWORD written = 0;
    WriteFile(h, bom, 2, &written, nullptr);
    WriteFile(h, kBody, static_cast<DWORD>(sizeof(kBody) - sizeof(wchar_t)),
              &written, nullptr);
    CloseHandle(h);
}

std::wstring find_ini() {
    // %TEMP% first: the loader already drops the jar there, so the whole
    // client keeps its runtime state in one place.
    const std::wstring tmp_path = temp_ini_path();
    if (!tmp_path.empty() &&
        GetFileAttributesW(tmp_path.c_str()) != INVALID_FILE_ATTRIBUTES) {
        return tmp_path;
    }
    // Fall back to the directory holding this DLL.
    if (openzen::g_self_module) {
        wchar_t mod[MAX_PATH] = {0};
        if (GetModuleFileNameW(openzen::g_self_module, mod, MAX_PATH) != 0) {
            std::wstring p = mod;
            const size_t slash = p.find_last_of(L"\\/");
            if (slash != std::wstring::npos) {
                p.resize(slash + 1);
                p += L"openzen_hwid.ini";
                if (GetFileAttributesW(p.c_str()) != INVALID_FILE_ATTRIBUTES) return p;
            }
        }
    }
    // Nothing found: create the documented default so it can be edited.
    if (!tmp_path.empty()) {
        write_default_ini(tmp_path);
        if (GetFileAttributesW(tmp_path.c_str()) != INVALID_FILE_ATTRIBUTES) return tmp_path;
    }
    return std::wstring();
}

std::wstring read_ini_string(const std::wstring& ini, const wchar_t* key,
                             const wchar_t* fallback) {
    if (ini.empty()) return fallback;
    wchar_t buf[512] = {0};
    GetPrivateProfileStringW(L"hwid", key, fallback, buf,
                             static_cast<DWORD>(std::size(buf)), ini.c_str());
    return buf;
}

bool read_ini_bool(const std::wstring& ini, const wchar_t* key, bool fallback) {
    if (ini.empty()) return fallback;
    return GetPrivateProfileIntW(L"hwid", key, fallback ? 1 : 0, ini.c_str()) != 0;
}

std::wstring env_value(const char* name) {
    char buf[512] = {0};
    DWORD n = GetEnvironmentVariableA(name, buf, static_cast<DWORD>(sizeof buf) - 1);
    if (n == 0 || n >= sizeof buf) return std::wstring();
    std::wstring w;
    const size_t len = std::strlen(buf);
    w.reserve(len);
    for (size_t i = 0; i < len; ++i) {
        w.push_back(static_cast<wchar_t>(static_cast<unsigned char>(buf[i])));
    }
    return w;
}

Config load() {
    Config c;
    const std::wstring ini = find_ini();

    if (!ini.empty()) {
        c.enabled = read_ini_bool(ini, L"enabled", c.enabled);
        c.surfaces = read_ini_string(ini, L"surfaces", c.surfaces.c_str());
        c.force_get_hook = read_ini_bool(ini, L"force_get_hook", c.force_get_hook);
        c.verbose = read_ini_bool(ini, L"verbose", c.verbose);
    }

    // Environment overrides win: handy for a one-off A/B run.
    const std::wstring env_surfaces = env_value("OPENZEN_HWID_SURFACES");
    if (!env_surfaces.empty()) c.surfaces = env_surfaces;

    const std::wstring env_off = env_value("OPENZEN_HWID_DISABLE");
    if (!env_off.empty()) c.enabled = false;

    if (!env_value("OPENZEN_FORCE_GET_HOOK").empty()) c.force_get_hook = true;

    return c;
}

} // namespace

const Config& config() {
    static const Config c = load();
    return c;
}

const std::wstring& config_path() {
    static const std::wstring p = [] {
        std::wstring found = find_ini();
        return found;
    }();
    return p;
}

bool surface_on(const char* name) {
    const Config& c = config();
    if (!c.enabled) return false;
    if (!name) return false;

    const std::wstring& list = c.surfaces;
    if (list == L"all") return true;
    if (list == L"none") return false;

    const size_t n = std::strlen(name);
    size_t i = 0;
    while (i < list.size()) {
        while (i < list.size() && (list[i] == L' ' || list[i] == L',')) ++i;
        const size_t start = i;
        while (i < list.size() && list[i] != L',' && list[i] != L' ') ++i;
        const size_t len = i - start;
        if (len == n) {
            bool same = true;
            for (size_t k = 0; k < n; ++k) {
                const wchar_t a = static_cast<wchar_t>(
                    static_cast<unsigned char>(name[k]));
                wchar_t b = list[start + k];
                if (b >= L'A' && b <= L'Z') b = static_cast<wchar_t>(b - L'A' + L'a');
                wchar_t al = a;
                if (al >= L'A' && al <= L'Z') al = static_cast<wchar_t>(al - L'A' + L'a');
                if (al != b) { same = false; break; }
            }
            if (same) return true;
        }
    }
    return false;
}

} // namespace hwid
