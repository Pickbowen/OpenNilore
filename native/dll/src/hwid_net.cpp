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

    int patched = install_import("IPHLPAPI.dll", "GetAdaptersAddresses",
                                 reinterpret_cast<void*>(&HookGetAdaptersAddresses));
    if (patched <= 0) return 0;

    g_ready = true;
    return patched;
}

} // namespace hwid
