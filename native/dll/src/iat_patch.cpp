#include "openzen.h"
#include "iat_patch.h"

#include <psapi.h>

#include <cstring>
#include <mutex>

#pragma comment(lib, "psapi.lib")

namespace hwid {

namespace {

struct Target {
    const char* dll;
    const char* func;
    void* hook;
};

constexpr int kMaxTargets = 16;
constexpr ULONG kDllNotificationLoaded = 1;

Target g_targets[kMaxTargets];
int g_target_count = 0;
bool g_notification_live = false;
std::mutex g_targets_mtx;

using LdrDllNotificationFn = VOID (CALLBACK*)(ULONG, const void*, PVOID);
using LdrRegisterDllNotificationFn = LONG (NTAPI*)(ULONG, LdrDllNotificationFn, PVOID, PVOID*);

struct LoadedNotification {
    ULONG flags;
    const void* full_name;
    const void* base_name;
    PVOID base;
    ULONG size;
};

struct UnloadedNotification {
    ULONG flags;
    const void* full_name;
    const void* base_name;
    PVOID base;
    ULONG size;
};

union NotificationData {
    LoadedNotification loaded;
    UnloadedNotification unloaded;
};

bool same_name(const char* a, const char* b) {
    return a && b && _stricmp(a, b) == 0;
}

// Security software scans the imports of its own modules, so rewriting one of
// its IAT slots to point into this DLL is asking to be quarantined. Nothing in
// this list is a hardware-fingerprint collector.
bool is_security_module(HMODULE mod) {
    static const wchar_t* const kFragments[] = {
        L"360", L"zhudongfangyu", L"huorong", L"hips", L"qqpcmgr", L"qqpctray",
        L"kxescore", L"kxetray", L"kwsprot", L"baidusd", L"msmpeng", L"mpclient",
        L"mpdefender", L"csfalcon", L"symantec", L"mcafee", L"kaspersky", L"avp",
        L"egui", L"nod32", L"ekrn", L"avast", L"avgnt", L"nortonsecurity",
        L"trendmicro", nullptr,
    };
    wchar_t name[MAX_PATH] = {0};
    if (!GetModuleBaseNameW(GetCurrentProcess(), mod, name, MAX_PATH)) return false;
    CharLowerBuffW(name, static_cast<DWORD>(wcslen(name)));
    for (int i = 0; kFragments[i]; ++i) {
        if (wcsstr(name, kFragments[i])) return true;
    }
    return false;
}

// A module whose PE section table carries the protector's own sections is not
// a normal consumer of ole32/advapi32: it is a WinLicense / Code Virtualizer
// image (the anticheat client is exactly this). Those images keep an inline
// hook counter and a code-integrity loop, and rewriting one of their IAT slots
// hands the check a pointer that is not inside the system DLL it expects.
// Skipping them is cheaper than winning that argument.
//
// The section names are the reliable signal; the name check below is only a
// fallback for a build that renamed its sections.
bool is_protected_module(HMODULE mod) {
    if (!mod) return true;

    auto dos = reinterpret_cast<const IMAGE_DOS_HEADER*>(mod);
    if (!dos || dos->e_magic != IMAGE_DOS_SIGNATURE) return true;
    auto nt = reinterpret_cast<const IMAGE_NT_HEADERS*>(
        reinterpret_cast<const BYTE*>(mod) + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE) return true;

    // Only walk the section table when the header can be trusted. Every module
    // in a 64-bit process is PE32+ and carries a full optional header; if the
    // sizes disagree the image is packed, hand-mapped, or mid-teardown, and
    // reading a section table out of it is an access violation. This runs over
    // every loaded module, so one bad header would take the game down.
    if (nt->OptionalHeader.Magic != IMAGE_NT_OPTIONAL_HDR64_MAGIC) return true;
    if (nt->FileHeader.SizeOfOptionalHeader != sizeof(IMAGE_OPTIONAL_HEADER64)) return true;

    const WORD section_count = nt->FileHeader.NumberOfSections;
    if (section_count == 0 || section_count > 96) return true;

    // Derive the table from sizeof(IMAGE_NT_HEADERS) rather than from the
    // macro, which trusts SizeOfOptionalHeader - the field we just refused to
    // rely on.
    auto sec = reinterpret_cast<const IMAGE_SECTION_HEADER*>(
        reinterpret_cast<const BYTE*>(nt) + sizeof(IMAGE_NT_HEADERS));

    static const char* const kProtectorSections[] = {
        ".bugland", ".boot", ".fptable", ".detourc", ".detourd",
        ".themida", ".vmp0", ".vmp1", ".enigma1", ".enigma2", nullptr,
    };
    for (WORD i = 0; i < section_count; ++i, ++sec) {
        char name[9] = {0};
        std::memcpy(name, sec->Name, 8);
        for (int k = 0; kProtectorSections[k]; ++k) {
            if (_stricmp(name, kProtectorSections[k]) == 0) return true;
        }
    }

    // Fallback: the anticheat client's own module names.
    static const wchar_t* const kAcFragments[] = {
        L"maxhook", L"heypixel", L"anticheat", L"anti-cheat", L"bugland", nullptr,
    };
    wchar_t base[MAX_PATH] = {0};
    if (GetModuleBaseNameW(GetCurrentProcess(), mod, base, MAX_PATH)) {
        CharLowerBuffW(base, static_cast<DWORD>(wcslen(base)));
        for (int i = 0; kAcFragments[i]; ++i) {
            if (wcsstr(base, kAcFragments[i])) return true;
        }
    }
    return false;
}

VOID CALLBACK on_dll_notification(ULONG reason, const void* data, PVOID) {
    if (reason != kDllNotificationLoaded || !data) return;
    auto* info = static_cast<const NotificationData*>(data);
    HMODULE mod = static_cast<HMODULE>(info->loaded.base);
    if (!mod) return;
    // Reached under the loader lock: no allocation, no module enumeration.
    // is_protected_module() only walks the PE header of `mod`, which is mapped
    // and immutable at this point, so it is safe here.
    if (is_protected_module(mod)) return;
    if (is_security_module(mod)) {
        wchar_t base[MAX_PATH] = {0};
        if (GetModuleBaseNameW(GetCurrentProcess(), mod, base, MAX_PATH)) {
            char narrow[MAX_PATH] = {0};
            WideCharToMultiByte(CP_ACP, 0, base, -1, narrow, MAX_PATH, nullptr, nullptr);
            openzen::log::info("IAT skip %s (security module)", narrow);
        }
        return;
    }

    for (int i = 0; i < kMaxTargets; ++i) {
        const Target& t = g_targets[i];
        if (!t.hook) break;
        patch_import(mod, t.dll, t.func, t.hook);
    }
}

void register_target(const char* dll, const char* func, void* hook) {
    std::lock_guard<std::mutex> lk(g_targets_mtx);
    for (int i = 0; i < g_target_count; ++i) {
        const Target& t = g_targets[i];
        if (t.hook == hook && same_name(t.dll, dll) && same_name(t.func, func)) return;
    }
    if (g_target_count >= kMaxTargets) return;
    g_targets[g_target_count++] = Target{ dll, func, hook };

    if (g_notification_live) return;
    HMODULE ntdll = GetModuleHandleA("ntdll.dll");
    if (!ntdll) return;
    auto reg = reinterpret_cast<LdrRegisterDllNotificationFn>(
        GetProcAddress(ntdll, "LdrRegisterDllNotification"));
    if (!reg) return;
    PVOID cookie = nullptr;
    if (reg(0, &on_dll_notification, nullptr, &cookie) == 0) {
        g_notification_live = true;
    }
}

} // namespace

bool patch_import(HMODULE mod, const char* dll, const char* func, void* hook, bool log_hit) {
    if (!mod || !dll || !func || !hook) return false;

    auto dos = reinterpret_cast<const IMAGE_DOS_HEADER*>(mod);
    if (!dos || dos->e_magic != IMAGE_DOS_SIGNATURE) return false;
    auto nt = reinterpret_cast<const IMAGE_NT_HEADERS*>(
        reinterpret_cast<const BYTE*>(mod) + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE) return false;

    const auto& dir = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT];
    if (dir.Size == 0) return false;
    auto desc = reinterpret_cast<const IMAGE_IMPORT_DESCRIPTOR*>(
        reinterpret_cast<const BYTE*>(mod) + dir.VirtualAddress);

    for (; desc->Name; ++desc) {
        const char* dll_name = reinterpret_cast<const char*>(
            reinterpret_cast<const BYTE*>(mod) + desc->Name);
        if (!same_name(dll_name, dll)) continue;
        if (!desc->OriginalFirstThunk) continue;

        auto* names = reinterpret_cast<const IMAGE_THUNK_DATA*>(
            reinterpret_cast<const BYTE*>(mod) + desc->OriginalFirstThunk);
        auto* slots = reinterpret_cast<IMAGE_THUNK_DATA*>(
            reinterpret_cast<BYTE*>(mod) + desc->FirstThunk);

        for (; names->u1.AddressOfData; ++names, ++slots) {
            if (IMAGE_SNAP_BY_ORDINAL(names->u1.Ordinal)) continue;
            auto by_name = reinterpret_cast<const IMAGE_IMPORT_BY_NAME*>(
                reinterpret_cast<const BYTE*>(mod) + names->u1.AddressOfData);
            if (!same_name(by_name->Name, func)) continue;

            DWORD old_protect = 0;
            if (!VirtualProtect(slots, sizeof(IMAGE_THUNK_DATA), PAGE_READWRITE, &old_protect))
                continue;
            slots->u1.Function = reinterpret_cast<ULONGLONG>(hook);
            VirtualProtect(slots, sizeof(IMAGE_THUNK_DATA), old_protect, &old_protect);
            FlushInstructionCache(GetCurrentProcess(), slots, sizeof(IMAGE_THUNK_DATA));
            if (!log_hit) return true;
            wchar_t base[MAX_PATH] = {0};
            if (GetModuleBaseNameW(GetCurrentProcess(), mod, base, MAX_PATH)) {
                char narrow[MAX_PATH] = {0};
                WideCharToMultiByte(CP_ACP, 0, base, -1, narrow, MAX_PATH, nullptr, nullptr);
                openzen::log::info("IAT patched %s -> %s!%s", narrow, dll, func);
            }
            return true;
        }
    }
    return false;
}

int patch_import_all(const char* dll, const char* func, void* hook) {
    HMODULE mods[1024];
    DWORD bytes = 0;
    if (!EnumProcessModules(GetCurrentProcess(), mods, sizeof mods, &bytes)) return 0;
    DWORD count = bytes / sizeof(HMODULE);
    if (count > 1024) count = 1024;
    int patched = 0;
    for (DWORD i = 0; i < count; ++i) {
        if (is_protected_module(mods[i])) {
            wchar_t base[MAX_PATH] = {0};
            if (GetModuleBaseNameW(GetCurrentProcess(), mods[i], base, MAX_PATH)) {
                char narrow[MAX_PATH] = {0};
                WideCharToMultiByte(CP_ACP, 0, base, -1, narrow, MAX_PATH, nullptr, nullptr);
                openzen::log::info("IAT skip %s (protected image; left %s!%s intact)",
                                   narrow, dll, func);
            }
            continue;
        }
        if (is_security_module(mods[i])) continue;
        if (patch_import(mods[i], dll, func, hook)) ++patched;
    }
    return patched;
}

int install_import(const char* dll, const char* func, void* hook) {
    register_target(dll, func, hook);
    return patch_import_all(dll, func, hook);
}

} // namespace hwid
