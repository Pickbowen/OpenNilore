// Minimal WMI probe: try each call shape and print the HRESULT so the one that
// actually works on this machine is unambiguous.
#include <cstdio>
#include <string>

#include <windows.h>
#include <objbase.h>
#include <wbemidl.h>

static const char* hexerr(HRESULT hr) {
    static char b[32];
    sprintf_s(b, "0x%08lX", (unsigned long)hr);
    return b;
}

static void dump(IWbemClassObject* obj, const wchar_t* prop) {
    if (!obj) { printf("      obj=null\n"); return; }
    VARIANT v;
    VariantInit(&v);
    CIMTYPE t = 0;
    long f = 0;
    HRESULT hr = obj->Get(prop, 0, &v, &t, &f);
    printf("      Get(%ls) hr=%s vt=%u", prop, hexerr(hr), (unsigned)V_VT(&v));
    if (SUCCEEDED(hr) && V_VT(&v) == VT_BSTR && V_BSTR(&v)) {
        printf(" value=[");
        for (const wchar_t* p = V_BSTR(&v); *p; ++p) putchar(*p < 0x80 ? (char)*p : '?');
        printf("]");
    }
    printf("\n");
    VariantClear(&v);
}

int main() {
    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    printf("CoInitializeEx        = %s\n", hexerr(hr));
    hr = CoInitializeSecurity(nullptr, -1, nullptr, nullptr, RPC_C_AUTHN_LEVEL_DEFAULT,
                              RPC_C_IMP_LEVEL_IMPERSONATE, nullptr, EOAC_NONE, nullptr);
    printf("CoInitializeSecurity  = %s\n", hexerr(hr));

    IWbemLocator* loc = nullptr;
    hr = CoCreateInstance(CLSID_WbemLocator, nullptr, CLSCTX_INPROC_SERVER,
                          IID_IWbemLocator, (void**)&loc);
    printf("CoCreateInstance      = %s loc=%p\n", hexerr(hr), (void*)loc);
    if (!loc) return 1;

    BSTR ns = SysAllocString(L"ROOT\\CIMV2");
    IWbemServices* svc = nullptr;
    hr = loc->ConnectServer(ns, nullptr, nullptr, nullptr, 0, nullptr, nullptr, &svc);
    SysFreeString(ns);
    printf("ConnectServer         = %s svc=%p\n", hexerr(hr), (void*)svc);
    if (!svc) { loc->Release(); return 1; }

    hr = CoSetProxyBlanket(svc, RPC_C_AUTHN_WINNT, RPC_C_AUTHZ_NONE, nullptr,
                           RPC_C_AUTHN_LEVEL_CALL, RPC_C_IMP_LEVEL_IMPERSONATE,
                           nullptr, EOAC_NONE);
    printf("CoSetProxyBlanket     = %s\n", hexerr(hr));

    struct Case { const wchar_t* q; LONG flags; const wchar_t* prop; };
    Case cases[] = {
        { L"SELECT * FROM Win32_OperatingSystem", 0, L"Caption" },
        { L"SELECT * FROM Win32_OperatingSystem", WBEM_FLAG_FORWARD_ONLY, L"Caption" },
        { L"SELECT UUID FROM Win32_ComputerSystemProduct", 0, L"UUID" },
        { L"SELECT UUID FROM Win32_ComputerSystemProduct", WBEM_FLAG_FORWARD_ONLY, L"UUID" },
        { L"SELECT SerialNumber FROM Win32_BaseBoard", 0, L"SerialNumber" },
        { L"SELECT SerialNumber FROM Win32_DiskDrive", 0, L"SerialNumber" },
    };
    BSTR lang = SysAllocString(L"WQL");
    for (const Case& c : cases) {
        printf("\nquery  %ls  flags=0x%lX\n", c.q, (unsigned long)c.flags);
        BSTR q = SysAllocString(c.q);
        IEnumWbemClassObject* en = nullptr;
        // NOTE: the vtable declares (strQueryLanguage, strQuery) - the language
        // comes FIRST, unlike the MSDN prose. Passing them the documented way
        // returns WBEM_E_INVALID_QUERY_TYPE (0x80041018) for every query.
        hr = svc->ExecQuery(lang, q, c.flags, nullptr, &en);
        printf("      ExecQuery = %s en=%p\n", hexerr(hr), (void*)en);
        if (SUCCEEDED(hr) && en) {
            IWbemClassObject* obj = nullptr;
            ULONG n = 0;
            HRESULT nhr = en->Next(2000, 1, &obj, &n);
            printf("      Next      = %s n=%lu\n", hexerr(nhr), n);
            if (n == 1) dump(obj, c.prop);
            if (obj) obj->Release();
            en->Release();
        }
        SysFreeString(q);
    }

    // singleton by path, with the real key
    printf("\nGetObject by path\n");
    const wchar_t* paths[] = {
        L"Win32_ComputerSystemProduct.Tag=\"Computer System Product\"",
        L"Win32_ComputerSystemProduct=@",
        L"Win32_BaseBoard.Tag=\"Base Board\"",
        L"Win32_BaseBoard=@",
    };
    for (const wchar_t* p : paths) {
        BSTR bp = SysAllocString(p);
        IWbemClassObject* obj = nullptr;
        hr = svc->GetObject(bp, 0, nullptr, &obj, nullptr);
        printf("      GetObject(%ls) = %s\n", p, hexerr(hr));
        if (obj) obj->Release();
        SysFreeString(bp);
    }

    SysFreeString(lang);
    svc->Release();
    loc->Release();
    return 0;
}
