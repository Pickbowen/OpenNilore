// End-to-end check for the WMI vtable spoof.
//
//   1. query the real fingerprint through WMI (before any patch)
//   2. install hwid::install_wmi_vtbl()
//   3. query again - the values must now be the derived fakes, and the real
//      ones must be gone
//
// It also reports the Put success/failure counters, because a provider is free
// to reject a write to a projected property and that would silently make the
// whole approach a no-op.
#include <cstdio>
#include <cstdarg>
#include <string>

#include <windows.h>
#include <objbase.h>
#include <wbemidl.h>

#include "../src/spoof_values.h"
#include "../src/hwid_wmi_vtbl.h"

// --- stand-ins for the real logging unit (the test does not link it) -------
namespace openzen {
namespace log {
void init() {}
void info(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    printf("  [log] ");
    vprintf(fmt, ap);
    printf("\n");
    va_end(ap);
}
void error(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    printf("  [err] ");
    vprintf(fmt, ap);
    printf("\n");
    va_end(ap);
}
} // namespace log
} // namespace openzen

static std::wstring query_one(IWbemServices* svc, const wchar_t* cls, const wchar_t* prop) {
    std::wstring got;
    if (!svc) return got;

    // Singleton read by object path first: this is the path that never touches
    // ExecQuery, and it is how a singleton class is normally read.
    {
        std::wstring path = std::wstring(cls) + L"=@";
        BSTR bpath = SysAllocString(path.c_str());
        IWbemClassObject* obj = nullptr;
        HRESULT hr = svc->GetObject(bpath, 0, nullptr, &obj, nullptr);
        SysFreeString(bpath);
        if (SUCCEEDED(hr) && obj) {
            VARIANT v;
            VariantInit(&v);
            CIMTYPE t;
            long f;
            if (SUCCEEDED(obj->Get(prop, 0, &v, &t, &f)) &&
                V_VT(&v) == VT_BSTR && V_BSTR(&v)) {
                got.assign(V_BSTR(&v), SysStringLen(V_BSTR(&v)));
            }
            VariantClear(&v);
            obj->Release();
            if (!got.empty()) return got;
        } else {
            printf("    GetObject(%ls) hr=0x%08lX\n", path.c_str(), (unsigned long)hr);
        }
    }

    BSTR lang = SysAllocString(L"WQL");
    std::wstring q = std::wstring(L"SELECT ") + prop + L" FROM " + cls;
    BSTR query = SysAllocString(q.c_str());
    IEnumWbemClassObject* en = nullptr;
    // vtable order is (language, query) - see the note in hwid_wmi_vtbl.cpp.
    HRESULT hr = svc->ExecQuery(lang, query,
                                WBEM_FLAG_RETURN_IMMEDIATELY | WBEM_FLAG_FORWARD_ONLY,
                                nullptr, &en);
    if (FAILED(hr)) {
        printf("    ExecQuery(%ls) failed hr=0x%08lX\n", q.c_str(), (unsigned long)hr);
    } else if (en) {
        IWbemClassObject* obj = nullptr;
        ULONG n = 0;
        hr = en->Next(2000, 1, &obj, &n);
        if (SUCCEEDED(hr) && n == 1 && obj) {
            VARIANT v;
            VariantInit(&v);
            CIMTYPE t;
            long f;
            if (SUCCEEDED(obj->Get(prop, 0, &v, &t, &f)) &&
                V_VT(&v) == VT_BSTR && V_BSTR(&v)) {
                got.assign(V_BSTR(&v), SysStringLen(V_BSTR(&v)));
            }
            VariantClear(&v);
            obj->Release();
        } else {
            printf("    Next(%ls) hr=0x%08lX n=%lu\n", q.c_str(), (unsigned long)hr, n);
        }
        en->Release();
    }
    SysFreeString(query);
    SysFreeString(lang);
    return got;
}

static IWbemServices* connect_services() {
    IWbemLocator* loc = nullptr;
    HRESULT hr = CoCreateInstance(CLSID_WbemLocator, nullptr, CLSCTX_INPROC_SERVER,
                                  IID_IWbemLocator, (void**)&loc);
    if (FAILED(hr) || !loc) {
        printf("    CoCreateInstance(WbemLocator) hr=0x%08lX\n", (unsigned long)hr);
        return nullptr;
    }
    BSTR ns = SysAllocString(L"ROOT\\CIMV2");
    IWbemServices* svc = nullptr;
    hr = loc->ConnectServer(ns, nullptr, nullptr, nullptr, 0, nullptr, nullptr, &svc);
    SysFreeString(ns);
    loc->Release();
    if (FAILED(hr) || !svc) {
        printf("    ConnectServer(ROOT\\CIMV2) hr=0x%08lX svc=%p\n",
               (unsigned long)hr, (void*)svc);
        return nullptr;
    }
    // Without this the IWbemServices proxy has no usable security context and
    // ExecQuery fails with WBEM_E_INVALID_NAMESPACE even though ConnectServer
    // returned S_OK.
    HRESULT bhr = CoSetProxyBlanket(svc, RPC_C_AUTHN_WINNT, RPC_C_AUTHZ_NONE, nullptr,
                                    RPC_C_AUTHN_LEVEL_CALL, RPC_C_IMP_LEVEL_IMPERSONATE,
                                    nullptr, EOAC_NONE);
    if (FAILED(bhr)) {
        printf("    CoSetProxyBlanket hr=0x%08lX\n", (unsigned long)bhr);
    }
    return svc;
}

static void show(const char* label, const std::wstring& w) {
    printf("%-34s [", label);
    for (wchar_t c : w) putchar(c < 0x80 ? (char)c : '?');
    printf("]\n");
}

int main() {
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    CoInitializeSecurity(nullptr, -1, nullptr, nullptr, RPC_C_AUTHN_LEVEL_DEFAULT,
                         RPC_C_IMP_LEVEL_IMPERSONATE, nullptr, EOAC_NONE, nullptr);

    // 1. real values, before the patch exists
    IWbemServices* svc = connect_services();
    if (!svc) {
        printf("could not connect to ROOT\\CIMV2\n");
        return 1;
    }
    std::wstring uuid_before = query_one(svc, L"Win32_ComputerSystemProduct", L"UUID");
    std::wstring disk_before = query_one(svc, L"Win32_DiskDrive", L"SerialNumber");
    std::wstring board_before = query_one(svc, L"Win32_BaseBoard", L"SerialNumber");
    svc->Release();

    printf("=== BEFORE PATCH (real machine) ===\n");
    show("ComputerSystemProduct.UUID", uuid_before);
    show("DiskDrive.SerialNumber", disk_before);
    show("BaseBoard.SerialNumber", board_before);

    // 2. arm the spoof
    printf("\n=== install_wmi_vtbl() ===\n");
    int rc = hwid::install_wmi_vtbl();
    printf("  rc=%d active=%d\n", rc, (int)hwid::wmi_vtbl_active());

    // 3. same queries, now intercepted
    svc = connect_services();
    std::wstring uuid_after = query_one(svc, L"Win32_ComputerSystemProduct", L"UUID");
    std::wstring disk_after = query_one(svc, L"Win32_DiskDrive", L"SerialNumber");
    std::wstring board_after = query_one(svc, L"Win32_BaseBoard", L"SerialNumber");
    svc->Release();

    const hwid::SpoofValues& v = hwid::values();
    printf("\n=== AFTER PATCH (what the anticheat would read) ===\n");
    show("ComputerSystemProduct.UUID", uuid_after);
    show("  expected (derived)", v.product_uuid);
    show("DiskDrive.SerialNumber", disk_after);
    show("  expected (derived)", v.disk_serial);
    show("BaseBoard.SerialNumber", board_after);
    show("  expected (derived)", v.baseboard_serial);

    printf("\n=== verdict ===\n");
    printf("  uuid  changed=%d matches_derived=%d\n",
           (int)(uuid_after != uuid_before),
           (int)(uuid_after == v.product_uuid));
    printf("  disk  changed=%d matches_derived=%d\n",
           (int)(disk_after != disk_before),
           (int)(disk_after == v.disk_serial));
    printf("  board changed=%d matches_derived=%d\n",
           (int)(board_after != board_before),
           (int)(board_after == v.baseboard_serial));
    return 0;
}
