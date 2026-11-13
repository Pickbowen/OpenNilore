#include "hwid_wmi.h"

#include <windows.h>
#include <objbase.h>
#include <wbemidl.h>

#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include <atomic>
#include <cstdio>

#include "openzen.h"
#include "iat_patch.h"
#include "spoof_values.h"

#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "oleaut32.lib")
#pragma comment(lib, "wbemuuid.lib")

namespace hwid {

namespace {

// ---- real CoCreateInstance ------------------------------------------------
typedef HRESULT (STDAPICALLTYPE* CoCreateInstance_t)(
    REFCLSID, LPUNKNOWN, DWORD, REFIID, LPVOID*);

std::atomic<bool> g_seen_wbem{false};

// Resolved on first use: with early injection COM is not loaded yet, and the
// hook still has to be armed for the modules that bring it in later.
CoCreateInstance_t g_real_CoCreate = nullptr;
int g_wmi_patched = 0;
bool g_wmi_active = false;
std::mutex g_mtx;

// The fake set lives in spoof_values.cpp so the registry hook and this one
// report the same machine, and so each device class gets its own value.

// ---- fake IWbemClassObject ------------------------------------------------
// A hand-written forwarding wrapper. Swapping the object's vtable pointer is
// cheaper but kills the process: the provider's own Get() reaches its state
// through the vptr, so a foreign table makes it read garbage. Every method is
// forwarded explicitly here, and only Get() is answered from the fake set.

IWbemClassObject* wrap_object(IWbemClassObject* real);

bool assign_string(VARIANT* pVal, CIMTYPE* pType, long* plFlavor, const std::wstring& fake) {
    if (fake.empty()) return false;
    VariantInit(pVal);
    V_VT(pVal) = VT_BSTR;
    V_BSTR(pVal) = SysAllocString(fake.c_str());
    if (!V_BSTR(pVal)) {
        VariantInit(pVal);
        return false;
    }
    if (pType) *pType = CIM_STRING;
    if (plFlavor) *plFlavor = WBEM_FLAVOR_FLAG_PROPAGATE_TO_INSTANCE;
    return true;
}

/**
 * Per-class dispatch. Matching on the property name alone made every class that has
 * a SerialNumber report the same value - on a real machine the BIOS, the board and
 * the disk never share one - so the class decides which fake is returned.
 */
bool fill_fingerprint(const std::wstring& cls, LPCWSTR name, VARIANT* pVal,
                      CIMTYPE* pType, long* plFlavor) {
    if (!name || !pVal) return false;
    const SpoofValues& v = values();

    if (cls == L"Win32_ComputerSystemProduct") {
        if (_wcsicmp(name, L"UUID") == 0)              return assign_string(pVal, pType, plFlavor, v.product_uuid);
        if (_wcsicmp(name, L"IdentifyingNumber") == 0) return assign_string(pVal, pType, plFlavor, v.system_serial);
        return false;
    }
    if (cls == L"Win32_BIOS") {
        if (_wcsicmp(name, L"SerialNumber") == 0)      return assign_string(pVal, pType, plFlavor, v.system_serial);
        return false;
    }
    if (cls == L"Win32_BaseBoard") {
        if (_wcsicmp(name, L"SerialNumber") == 0)      return assign_string(pVal, pType, plFlavor, v.baseboard_serial);
        return false;
    }
    if (cls == L"Win32_DiskDrive" || cls == L"Win32_PhysicalMedia") {
        if (_wcsicmp(name, L"SerialNumber") == 0)      return assign_string(pVal, pType, plFlavor, v.disk_serial);
        return false;
    }
    if (cls == L"Win32_Processor") {
        if (_wcsicmp(name, L"ProcessorId") == 0)       return assign_string(pVal, pType, plFlavor, v.processor_id);
        return false;
    }
    // Win32_Volume.SerialNumber is a UINT32, so that class is deliberately not
    // answered with a BSTR here; Win32_LogicalDisk is handled by the wrapper,
    // which still has the real serial in hand.
    if (cls == L"Win32_ComputerSystem") {
        if (_wcsicmp(name, L"Name") == 0)              return assign_string(pVal, pType, plFlavor, v.computer_name);
        return false;
    }
    if (cls == L"Win32_OperatingSystem") {
        if (_wcsicmp(name, L"SerialNumber") == 0)      return assign_string(pVal, pType, plFlavor, v.product_id);
        return false;
    }

    // Unknown class: only names that cannot belong to anything else.
    if (_wcsicmp(name, L"UUID") == 0)                  return assign_string(pVal, pType, plFlavor, v.product_uuid);
    if (_wcsicmp(name, L"ProcessorId") == 0)           return assign_string(pVal, pType, plFlavor, v.processor_id);
    return false;
}

bool is_fingerprint_name(LPCWSTR name) {
    static const wchar_t* kNames[] = {
        L"UUID", L"SerialNumber", L"IdentifyingNumber", L"ProcessorId",
        L"VolumeSerialNumber", L"Name", nullptr,
    };
    for (int i = 0; kNames[i]; ++i) {
        if (_wcsicmp(name, kNames[i]) == 0) return true;
    }
    return false;
}

DWORD hex32(const std::wstring& s) {
    DWORD v = 0;
    for (wchar_t c : s) {
        int nibble = (c >= L'0' && c <= L'9') ? c - L'0'
                   : (c >= L'A' && c <= L'F') ? c - L'A' + 10
                   : (c >= L'a' && c <= L'f') ? c - L'a' + 10 : -1;
        if (nibble >= 0) v = (v << 4) | static_cast<DWORD>(nibble);
    }
    return v;
}

struct FakeWbemObject : IWbemClassObject {
    IWbemClassObject* real_;
    ULONG refs_ = 1;
    std::wstring cls_;

    std::wstring read_string(LPCWSTR prop) {
        std::wstring out;
        VARIANT v;
        VariantInit(&v);
        CIMTYPE type = 0;
        long flavor = 0;
        if (real_ && SUCCEEDED(real_->Get(prop, 0, &v, &type, &flavor)) &&
            V_VT(&v) == VT_BSTR && V_BSTR(&v)) {
            out.assign(V_BSTR(&v), SysStringLen(V_BSTR(&v)));
        }
        VariantClear(&v);
        return out;
    }

    explicit FakeWbemObject(IWbemClassObject* r) : real_(r) {
        cls_ = read_string(L"__CLASS");
    }

    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IWbemClassObject) {
            *ppv = static_cast<IWbemClassObject*>(this);
            AddRef();
            return S_OK;
        }
        return real_->QueryInterface(riid, ppv);
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return ++refs_; }
    ULONG STDMETHODCALLTYPE Release() override {
        ULONG r = --refs_;
        if (r == 0) { real_->Release(); delete this; }
        return r;
    }

    HRESULT STDMETHODCALLTYPE GetQualifierSet(IWbemQualifierSet** qualifier) override {
        return real_->GetQualifierSet(qualifier);
    }
    HRESULT STDMETHODCALLTYPE Get(LPCWSTR name, long flags, VARIANT* pVal,
                                  CIMTYPE* pType, long* plFlavor) override {
        if (name && pVal && is_fingerprint_name(name)) {
            if (cls_ == L"Win32_LogicalDisk" && _wcsicmp(name, L"VolumeSerialNumber") == 0) {
                DWORD mixed = mix_volume_serial(hex32(read_string(L"VolumeSerialNumber")));
                wchar_t buf[9];
                swprintf(buf, 9, L"%08lX", static_cast<unsigned long>(mixed));
                if (assign_string(pVal, pType, plFlavor, buf)) return WBEM_S_NO_ERROR;
            }
            if (fill_fingerprint(cls_, name, pVal, pType, plFlavor)) {
                return WBEM_S_NO_ERROR;
            }
        }
        return real_->Get(name, flags, pVal, pType, plFlavor);
    }
    HRESULT STDMETHODCALLTYPE Put(LPCWSTR name, long flags, VARIANT* pVal, CIMTYPE type) override {
        return real_->Put(name, flags, pVal, type);
    }
    HRESULT STDMETHODCALLTYPE Delete(LPCWSTR name) override {
        return real_->Delete(name);
    }
    HRESULT STDMETHODCALLTYPE Clone(IWbemClassObject** copy) override {
        HRESULT hr = real_->Clone(copy);
        if (SUCCEEDED(hr) && copy && *copy) *copy = wrap_object(*copy);
        return hr;
    }
    HRESULT STDMETHODCALLTYPE GetObjectText(long flags, BSTR* text) override {
        return real_->GetObjectText(flags, text);
    }
    HRESULT STDMETHODCALLTYPE SpawnDerivedClass(long flags, IWbemClassObject** new_class) override {
        HRESULT hr = real_->SpawnDerivedClass(flags, new_class);
        if (SUCCEEDED(hr) && new_class && *new_class) *new_class = wrap_object(*new_class);
        return hr;
    }
    HRESULT STDMETHODCALLTYPE SpawnInstance(long flags, IWbemClassObject** new_instance) override {
        HRESULT hr = real_->SpawnInstance(flags, new_instance);
        if (SUCCEEDED(hr) && new_instance && *new_instance) *new_instance = wrap_object(*new_instance);
        return hr;
    }
    HRESULT STDMETHODCALLTYPE CompareTo(long flags, IWbemClassObject* other) override {
        auto* ours = dynamic_cast<FakeWbemObject*>(other);
        return real_->CompareTo(flags, ours ? ours->real_ : other);
    }
    HRESULT STDMETHODCALLTYPE GetNames(LPCWSTR qualifier, long flags, VARIANT* qualifier_val,
                                       SAFEARRAY** names) override {
        return real_->GetNames(qualifier, flags, qualifier_val, names);
    }
    HRESULT STDMETHODCALLTYPE BeginEnumeration(long flags) override {
        return real_->BeginEnumeration(flags);
    }
    HRESULT STDMETHODCALLTYPE Next(long flags, BSTR* name, VARIANT* pVal, CIMTYPE* pType,
                                   long* plFlavor) override {
        return real_->Next(flags, name, pVal, pType, plFlavor);
    }
    HRESULT STDMETHODCALLTYPE EndEnumeration() override { return real_->EndEnumeration(); }
    HRESULT STDMETHODCALLTYPE GetPropertyQualifierSet(LPCWSTR property,
                                                      IWbemQualifierSet** qualifier) override {
        return real_->GetPropertyQualifierSet(property, qualifier);
    }
    HRESULT STDMETHODCALLTYPE GetPropertyOrigin(LPCWSTR name, BSTR* cls) override {
        return real_->GetPropertyOrigin(name, cls);
    }
    HRESULT STDMETHODCALLTYPE InheritsFrom(LPCWSTR ancestor) override {
        return real_->InheritsFrom(ancestor);
    }
    HRESULT STDMETHODCALLTYPE GetMethod(LPCWSTR name, long flags, IWbemClassObject** in,
                                        IWbemClassObject** out) override {
        return real_->GetMethod(name, flags, in, out);
    }
    HRESULT STDMETHODCALLTYPE PutMethod(LPCWSTR name, long flags, IWbemClassObject* in,
                                        IWbemClassObject* out) override {
        return real_->PutMethod(name, flags, in, out);
    }
    HRESULT STDMETHODCALLTYPE DeleteMethod(LPCWSTR name) override {
        return real_->DeleteMethod(name);
    }
    HRESULT STDMETHODCALLTYPE BeginMethodEnumeration(long flags) override {
        return real_->BeginMethodEnumeration(flags);
    }
    HRESULT STDMETHODCALLTYPE NextMethod(long flags, BSTR* name, IWbemClassObject** in,
                                         IWbemClassObject** out) override {
        return real_->NextMethod(flags, name, in, out);
    }
    HRESULT STDMETHODCALLTYPE EndMethodEnumeration() override {
        return real_->EndMethodEnumeration();
    }
    HRESULT STDMETHODCALLTYPE GetMethodQualifierSet(LPCWSTR method,
                                                    IWbemQualifierSet** qualifier) override {
        return real_->GetMethodQualifierSet(method, qualifier);
    }
    HRESULT STDMETHODCALLTYPE GetMethodOrigin(LPCWSTR method, BSTR* cls) override {
        return real_->GetMethodOrigin(method, cls);
    }
};

// Takes over the caller's reference: whoever held `real` now holds the wrapper,
// so no AddRef here and no Release of the original until the wrapper dies.
IWbemClassObject* wrap_object(IWbemClassObject* real) {
    return real ? new FakeWbemObject(real) : nullptr;
}

// ---- proxy IEnumWbemClassObject --------------------------------------------
struct FakeEnum : IEnumWbemClassObject {
    IEnumWbemClassObject* real_;
    ULONG refs_ = 1;
    explicit FakeEnum(IEnumWbemClassObject* r) : real_(r) {}

    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IEnumWbemClassObject) {
            *ppv = static_cast<IEnumWbemClassObject*>(this); AddRef(); return S_OK;
        }
        return real_->QueryInterface(riid, ppv);
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return ++refs_; }
    ULONG STDMETHODCALLTYPE Release() override {
        ULONG r = --refs_;
        if (r==0) { real_->Release(); delete this; }
        return r;
    }
    HRESULT STDMETHODCALLTYPE Reset() override { return real_->Reset(); }
    HRESULT STDMETHODCALLTYPE Next(long t, ULONG n, IWbemClassObject** objs, ULONG* ret) override {
        HRESULT hr = real_->Next(t, n, objs, ret);
        if (SUCCEEDED(hr) && objs && ret && *ret > 0) {
            for (ULONG i = 0; i < *ret; ++i) {
                objs[i] = wrap_object(objs[i]);
            }
        }
        return hr;
    }
    HRESULT STDMETHODCALLTYPE Clone(IEnumWbemClassObject** o) override {
        HRESULT hr = real_->Clone(o);
        if (SUCCEEDED(hr) && o && *o) *o = new FakeEnum(*o);
        return hr;
    }
    HRESULT STDMETHODCALLTYPE NextAsync(ULONG n, IWbemObjectSink* s) override { return real_->NextAsync(n, s); }
    HRESULT STDMETHODCALLTYPE Skip(long t, ULONG n) override { return real_->Skip(t, n); }
};

// ---- proxy IWbemServices ----------------------------------------------------
struct FakeWbemServices : IWbemServices {
    IWbemServices* real_;
    ULONG refs_ = 1;
    explicit FakeWbemServices(IWbemServices* r) : real_(r) {}

    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IWbemServices) {
            *ppv = static_cast<IWbemServices*>(this); AddRef(); return S_OK;
        }
        return real_->QueryInterface(riid, ppv);
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return ++refs_; }
    ULONG STDMETHODCALLTYPE Release() override {
        ULONG r = --refs_;
        if (r==0) { real_->Release(); delete this; }
        return r;
    }
    HRESULT STDMETHODCALLTYPE OpenNamespace(const BSTR ns, long f, IWbemContext* c, IWbemServices** s,
        IWbemCallResult** r) override { return real_->OpenNamespace(ns,f,c,s,r); }
    HRESULT STDMETHODCALLTYPE CancelAsyncCall(IWbemObjectSink* s) override { return real_->CancelAsyncCall(s); }
    HRESULT STDMETHODCALLTYPE GetObject(const BSTR p, long f, IWbemContext* c, IWbemClassObject** o,
        IWbemCallResult** r) override {
        HRESULT hr = real_->GetObject(p, f, c, o, r);
        // Singleton path (Win32_ComputerSystemProduct / Win32_BIOS ...): the returned
        // object must be wrapped too, otherwise Get() is never intercepted.
        if (SUCCEEDED(hr) && o && *o) *o = wrap_object(*o);
        return hr;
    }
    HRESULT STDMETHODCALLTYPE PutInstance(IWbemClassObject* i, long f, IWbemContext* c, IWbemCallResult** r) override { return real_->PutInstance(i,f,c,r); }
    HRESULT STDMETHODCALLTYPE DeleteInstance(const BSTR p, long f, IWbemContext* c, IWbemCallResult** r) override { return real_->DeleteInstance(p,f,c,r); }
    HRESULT STDMETHODCALLTYPE DeleteClass(const BSTR p, long f, IWbemContext* c, IWbemCallResult** r) override { return real_->DeleteClass(p,f,c,r); }
    HRESULT STDMETHODCALLTYPE CreateClassEnum(const BSTR p, long f, IWbemContext* c, IEnumWbemClassObject** e) override {
        HRESULT hr = real_->CreateClassEnum(p,f,c,e);
        if (SUCCEEDED(hr) && e && *e) *e = new FakeEnum(*e);
        return hr;
    }
    HRESULT STDMETHODCALLTYPE CreateInstanceEnum(const BSTR p, long f, IWbemContext* c, IEnumWbemClassObject** e) override {
        HRESULT hr = real_->CreateInstanceEnum(p,f,c,e);
        if (SUCCEEDED(hr) && e && *e) *e = new FakeEnum(*e);
        return hr;
    }
    HRESULT STDMETHODCALLTYPE ExecQuery(const BSTR q, const BSTR lang, long f, IWbemContext* c, IEnumWbemClassObject** e) override {
        HRESULT hr = real_->ExecQuery(q, lang, f, c, e);
        if (SUCCEEDED(hr) && e && *e) *e = new FakeEnum(*e);
        return hr;
    }
    HRESULT STDMETHODCALLTYPE ExecQueryAsync(const BSTR q, const BSTR lang, long f, IWbemContext* c, IWbemObjectSink* s) override { return real_->ExecQueryAsync(q,lang,f,c,s); }
    HRESULT STDMETHODCALLTYPE ExecNotificationQuery(const BSTR q, const BSTR lang, long f, IWbemContext* c, IEnumWbemClassObject** e) override {
        HRESULT hr = real_->ExecNotificationQuery(q, lang, f, c, e);
        if (SUCCEEDED(hr) && e && *e) *e = new FakeEnum(*e);
        return hr;
    }
    HRESULT STDMETHODCALLTYPE ExecNotificationQueryAsync(const BSTR q, const BSTR lang, long f, IWbemContext* c, IWbemObjectSink* s) override { return real_->ExecNotificationQueryAsync(q, lang, f, c, s); }
    HRESULT STDMETHODCALLTYPE ExecMethod(const BSTR c, const BSTR m, long f, IWbemContext* x, IWbemClassObject* i, IWbemClassObject** o, IWbemCallResult** r) override { return real_->ExecMethod(c,m,f,x,i,o,r); }
    HRESULT STDMETHODCALLTYPE ExecMethodAsync(const BSTR c, const BSTR m, long f, IWbemContext* x, IWbemClassObject* i, IWbemObjectSink* s) override { return real_->ExecMethodAsync(c,m,f,x,i,s); }
    HRESULT STDMETHODCALLTYPE QueryObjectSink(long f, IWbemObjectSink** s) override { return real_->QueryObjectSink(f, s); }
    HRESULT STDMETHODCALLTYPE GetObjectAsync(const BSTR p, long f, IWbemContext* c, IWbemObjectSink* s) override { return real_->GetObjectAsync(p, f, c, s); }
    HRESULT STDMETHODCALLTYPE PutClass(IWbemClassObject* o, long f, IWbemContext* c, IWbemCallResult** r) override { return real_->PutClass(o, f, c, r); }
    HRESULT STDMETHODCALLTYPE PutClassAsync(IWbemClassObject* o, long f, IWbemContext* c, IWbemObjectSink* s) override { return real_->PutClassAsync(o, f, c, s); }
    HRESULT STDMETHODCALLTYPE DeleteClassAsync(const BSTR p, long f, IWbemContext* c, IWbemObjectSink* s) override { return real_->DeleteClassAsync(p, f, c, s); }
    HRESULT STDMETHODCALLTYPE CreateClassEnumAsync(const BSTR p, long f, IWbemContext* c, IWbemObjectSink* s) override { return real_->CreateClassEnumAsync(p, f, c, s); }
    HRESULT STDMETHODCALLTYPE PutInstanceAsync(IWbemClassObject* i, long f, IWbemContext* c, IWbemObjectSink* s) override { return real_->PutInstanceAsync(i, f, c, s); }
    HRESULT STDMETHODCALLTYPE DeleteInstanceAsync(const BSTR p, long f, IWbemContext* c, IWbemObjectSink* s) override { return real_->DeleteInstanceAsync(p, f, c, s); }
    HRESULT STDMETHODCALLTYPE CreateInstanceEnumAsync(const BSTR p, long f, IWbemContext* c, IWbemObjectSink* s) override { return real_->CreateInstanceEnumAsync(p, f, c, s); }
};

// ---- proxy IWbemLocator ----------------------------------------------------
struct FakeWbemLocator : IWbemLocator {
    IWbemLocator* real_;
    ULONG refs_ = 1;
    explicit FakeWbemLocator(IWbemLocator* r) : real_(r) {}

    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IWbemLocator) {
            *ppv = static_cast<IWbemLocator*>(this); AddRef(); return S_OK;
        }
        return real_->QueryInterface(riid, ppv);
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return ++refs_; }
    ULONG STDMETHODCALLTYPE Release() override {
        ULONG r = --refs_;
        if (r==0) { real_->Release(); delete this; }
        return r;
    }
    HRESULT STDMETHODCALLTYPE ConnectServer(const BSTR ns, const BSTR user, const BSTR pwd,
        const BSTR locale, long flags, const BSTR auth, IWbemContext* c, IWbemServices** srv) override {
        HRESULT hr = real_->ConnectServer(ns, user, pwd, locale, flags, auth, c, srv);
        if (SUCCEEDED(hr) && srv && *srv) *srv = new FakeWbemServices(*srv);
        return hr;
    }
};

CoCreateInstance_t real_co_create();

// ---- CoCreateInstance hook -------------------------------------------------
HRESULT STDAPICALLTYPE HookCoCreateInstance(
    REFCLSID rclsid, LPUNKNOWN outer, DWORD ctx, REFIID riid, LPVOID* ppv)
{
    CoCreateInstance_t real = real_co_create();
    if (!real) return REGDB_E_CLASSNOTREG;
    HRESULT hr = real(rclsid, outer, ctx, riid, ppv);
    if (FAILED(hr) || !ppv || !*ppv) return hr;

    // Only wrap the WBEM locator when the caller asked for it.
    if (IsEqualCLSID(rclsid, CLSID_WbemLocator) &&
        IsEqualIID(riid, IID_IWbemLocator) && ppv && *ppv) {
        auto realLoc = static_cast<IWbemLocator*>(*ppv);
        *ppv = new FakeWbemLocator(realLoc);
        if (!g_seen_wbem.exchange(true)) {
            openzen::log::info("HWID WMI spoof: first WbemLocator creation intercepted");
        }
    }
    return hr;
}

CoCreateInstance_t real_co_create() {
    if (g_real_CoCreate) return g_real_CoCreate;
    HMODULE owner = GetModuleHandleA("ole32.dll");
    if (!owner) owner = GetModuleHandleA("combase.dll");
    if (!owner) return nullptr;
    g_real_CoCreate = reinterpret_cast<CoCreateInstance_t>(
        GetProcAddress(owner, "CoCreateInstance"));
    return g_real_CoCreate;
}

} // namespace

int install_wmi() {
    std::lock_guard<std::mutex> lk(g_mtx);
    if (g_wmi_active) return g_wmi_patched;

    // Same set the registry hook serves, generated on first access.
    values();

    int patched = install_import("OLE32.dll", "CoCreateInstance",
                                 reinterpret_cast<void*>(&HookCoCreateInstance));
    patched += install_import("COMBase.dll", "CoCreateInstance",
                              reinterpret_cast<void*>(&HookCoCreateInstance));
    g_wmi_patched = patched;
    g_wmi_active = patched > 0;
    return patched;
}

} // namespace hwid
