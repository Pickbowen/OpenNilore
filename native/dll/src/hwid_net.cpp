#include "hwid_net.h"

#include <winsock2.h>
#include <windows.h>
#include <iptypes.h>
#include <iphlpapi.h>

#include <atomic>
#include <cstring>
#include <mutex>

#include "openzen.h"
#include "iat_patch.h"
#include "inline_hook.h"
#include "nt_guard.h"
#include "spoof_values.h"

namespace hwid {

namespace {

using GetAdaptersAddresses_t = ULONG (WINAPI*)(ULONG, ULONG, PVOID,
                                               PIP_ADAPTER_ADDRESSES, PULONG);

GetAdaptersAddresses_t g_real = nullptr;
std::mutex g_mtx;
bool g_ready = false;
std::atomic<bool> g_seen{false};

GetAdaptersAddresses_t real_adapters() {
    if (g_real) return g_real;
    HMODULE iphlpapi = GetModuleHandleA("iphlpapi.dll");
    if (!iphlpapi) return nullptr;
    g_real = reinterpret_cast<GetAdaptersAddresses_t>(
        GetProcAddress(iphlpapi, "GetAdaptersAddresses"));
    return g_real;
}

ULONG WINAPI HookGetAdaptersAddresses(ULONG family, ULONG flags, PVOID reserved,
                                      PIP_ADAPTER_ADDRESSES addrs, PULONG size)
{
    GetAdaptersAddresses_t real = real_adapters();
    if (!real) return ERROR_PROC_NOT_FOUND;

    ULONG rc = real(family, flags, reserved, addrs, size);
    if (rc != NO_ERROR || !addrs) return rc;

    if (!g_seen.exchange(true)) {
        openzen::log::info("HWID MAC spoof: first GetAdaptersAddresses intercepted");
    }
    for (PIP_ADAPTER_ADDRESSES a = addrs; a; a = a->Next) {
        if (a->PhysicalAddressLength >= 6) {
            unsigned char out[6];
            mix_mac(a->PhysicalAddress, out);
            std::memcpy(a->PhysicalAddress, out, 6);
            a->PhysicalAddressLength = 6;
        }
    }
    return rc;
}

} // namespace

int install_net() {
    std::lock_guard<std::mutex> lk(g_mtx);
    if (g_ready) return 1;

    HMODULE iphlpapi = GetModuleHandleA("iphlpapi.dll");
    if (!iphlpapi) iphlpapi = LoadLibraryA("iphlpapi.dll");
    if (!iphlpapi) return -1;

    void* entry = reinterpret_cast<void*>(
        GetProcAddress(iphlpapi, "GetAdaptersAddresses"));
    if (!entry) return -1;

    values();

    // Primary path: hook the function entry itself.
    //
    // An IAT patch only covers modules that were already loaded and has to
    // skip WinLicense-protected images, so the anticheat keeps reading the
    // real MAC. Patching the entry means every caller in the process - the
    // anticheat included - goes through this hook, and no import table slot
    // is altered, so an IAT self-check sees nothing.
    //
    // This is the technique Cheart's own spoof DLL uses (Detours inline hook),
    // and GetAdaptersAddresses has a relocatable prologue
    // (mov r11,rsp; push rdi; sub rsp,0x60), which is what makes it safe here.
    // First choice: ntguard, whose first hop lands in ntdll.
    //
    // A plain inline hook leaves `E9 <rel32> -> OpenZen.dll` in the function
    // prologue. Two checks kill that against a native anticheat:
    //   * a byte-hash of the first N bytes no longer matches the vendor build;
    //   * the jmp target resolves into a MEM_PRIVATE RWX page, which is exactly
    //     the "committed + executable, not backed by an image" test a scanner
    //     runs on every suspicious address.
    // GetAdaptersAddresses is in the anticheat's own import table, so its
    // prologue is a cheap thing for it to look at. ntguard keeps both the first
    // hop and the trampoline inside ntdll's image, so the prologue reads as an
    // ordinary kernel32 -> ntdll jump and no private executable page exists.
    void* original = nullptr;
    const bool may_patch_code = ntguard::code_hooks_enabled();
    if (may_patch_code &&
        ntguard::hook(entry, reinterpret_cast<void*>(&HookGetAdaptersAddresses), &original)) {
        g_real = reinterpret_cast<GetAdaptersAddresses_t>(original);
        g_ready = true;
        openzen::log::info("HWID MAC spoof: ntdll-relay hook on iphlpapi!GetAdaptersAddresses");
        return 1;
    }

    if (may_patch_code &&
        install_inline_hook(entry, reinterpret_cast<void*>(&HookGetAdaptersAddresses),
                            &original)) {
        g_real = reinterpret_cast<GetAdaptersAddresses_t>(original);
        g_ready = true;
        openzen::log::info("HWID MAC spoof: inline hook on iphlpapi!GetAdaptersAddresses "
                           "(private trampoline; ntguard declined)");
        return 1;
    }

    // Fallback: import-table patch, which still covers the Java side on a
    // system where the entry hook was refused.
    g_real = reinterpret_cast<GetAdaptersAddresses_t>(entry);
    int patched = install_import("IPHLPAPI.dll", "GetAdaptersAddresses",
                                 reinterpret_cast<void*>(&HookGetAdaptersAddresses));
    if (patched <= 0) return 0;

    g_ready = true;
    openzen::log::info("HWID MAC spoof: IAT fallback patched %d modules", patched);
    return patched;
}

} // namespace hwid
