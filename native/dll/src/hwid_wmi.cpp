#include "hwid_wmi.h"

#include <windows.h>
#include <objbase.h>
#include <wbemidl.h>
#include <psapi.h>

#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "spoof_values.h"

#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "oleaut32.lib")
#pragma comment(lib, "psapi.lib")
#pragma comment(lib, "wbemuuid.lib")

namespace hwid {

namespace {

// ---- real CoCreateInstance ------------------------------------------------
typedef HRESULT (STDAPICALLTYPE* CoCreateInstance_t)(
    REFCLSID, LPUNKNOWN, DWORD, REFIID, LPVOID*);

CoCreateInstance_t g_real_CoCreate = nullptr;
bool g_wmi_active = false;
std::mutex g_mtx;

// The fake set lives in spoof_values.cpp so the registry hook and this one
// report the same machine, and so each device class gets its own value.

// ---- fake IWbemClassObject ------------------------------------------------
// We forward the real one but override Get() for fingerprint properties.

// ---- fake IWbemClassObject (vtable patch) ---------------------------------
// Instead of re-implementing the whole 27-method COM interface, we swap the object's
// vtable pointer for a copy whose Get() slot is redirected to us. Every other method
// keeps hitting the real implementation untouched.

using WbemGetFn = HRESULT (STDMETHODCALLTYPE*)(IWbemClassObject*, LPCWSTR, long,
                                              VARIANT*, CIMTYPE*, long*);

WbemGetFn g_real_wbem_get = nullptr;
void** g_patched_wbem_vtable = nullptr;
constexpr size_t kWbemVtableSlots = 27;   // IUnknown(3) + IWbemClassObject(24)
constexpr size_t kWbemGetSlot = 4;        // IUnknown(3) + GetQualifierSet(1)
std::once_flag g_vtable_once;

/** Class of the object being read, e.g. "Win32_BIOS". Empty when unavailable. */
std::wstring wbem_class(IWbemClassObject* obj) {
    VARIANT v;
    VariantInit(&v);
    std::wstring out;
    if (g_real_wbem_get
            && SUCCEEDED(g_real_wbem_get(obj, L"__CLASS", 0, &v, nullptr, nullptr))
            && V_VT(&v) == VT_BSTR && V_BSTR(&v)) {
        out.assign(V_BSTR(&v), SysStringLen(V_BSTR(&v)));
    }
    VariantClear(&v);
    return out;
}

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
        if (_wcsicmp(name, L"SerialNumber") == 0)      return assign_string(pVal, pType, plFlavor, v.bios_serial);
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
    // Win32_LogicalDisk.VolumeSerialNumber is a string; Win32_Volume.SerialNumber is a
    // UINT32, so that class is deliberately not answered with a BSTR here.
    if (cls == L"Win32_LogicalDisk") {
        if (_wcsicmp(name, L"VolumeSerialNumber") == 0)
            return assign_string(pVal, pType, plFlavor, v.volume_serial);
        return false;
    }
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

HRESULT STDMETHODCALLTYPE HookWbemGet(IWbemClassObject* self, LPCWSTR name, long flags,
                                      VARIANT* pVal, CIMTYPE* pType, long* plFlavor) {
    if (g_real_wbem_get && name && pVal && is_fingerprint_name(name)) {
        if (fill_fingerprint(wbem_class(self), name, pVal, pType, plFlavor)) {
            return WBEM_S_NO_ERROR;
        }
    }
    return g_real_wbem_get(self, name, flags, pVal, pType, plFlavor);
}

// Idempotent: the first call builds the patched vtable, every call re-points the
// object. call_once because WMI queries run on whatever thread asked for them.
void hook_wbem_object(IWbemClassObject* obj) {
    if (!obj) return;
    void** vt = *reinterpret_cast<void***>(obj);
    if (!vt) return;
    std::call_once(g_vtable_once, [vt] {
        auto* copy = new void*[kWbemVtableSlots];
        for (size_t i = 0; i < kWbemVtableSlots; ++i) copy[i] = vt[i];
        g_real_wbem_get = reinterpret_cast<WbemGetFn>(vt[kWbemGetSlot]);
        copy[kWbemGetSlot] = reinterpret_cast<void*>(&HookWbemGet);
        g_patched_wbem_vtable = copy;
    });
    *reinterpret_cast<void***>(obj) = g_patched_wbem_vtable;
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
                if (objs[i]) hook_wbem_object(objs[i]);
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
        if (SUCCEEDED(hr) && o && *o) hook_wbem_object(*o);
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

// ---- CoCreateInstance hook -------------------------------------------------
HRESULT STDAPICALLTYPE HookCoCreateInstance(
    REFCLSID rclsid, LPUNKNOWN outer, DWORD ctx, REFIID riid, LPVOID* ppv)
{
    HRESULT hr = g_real_CoCreate(rclsid, outer, ctx, riid, ppv);
    if (FAILED(hr) || !ppv || !*ppv) return hr;

    // Only wrap the WBEM locator when the caller asked for it.
    if (IsEqualCLSID(rclsid, CLSID_WbemLocator) &&
        IsEqualIID(riid, IID_IWbemLocator) && ppv && *ppv) {
        auto realLoc = static_cast<IWbemLocator*>(*ppv);
        *ppv = new FakeWbemLocator(realLoc);
    }
    return hr;
}

// ---- IAT patch (same pattern as hwid_hook) --------------------------------
bool patch_iat_co_create(HMODULE mod) {
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
        if (_stricmp(dllName, "ole32.dll") != 0 && _stricmp(dllName, "combase.dll") != 0) {
            ++desc; continue;
        }

        // Same guard as the registry hook: no import-name table means the slots are
        // resolved addresses, so walking them as names faults.
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
            if (_stricmp(byName->Name, "CoCreateInstance") == 0) {
                DWORD oldProtect = 0;
                if (VirtualProtect(iat, sizeof(IMAGE_THUNK_DATA),
                                   PAGE_READWRITE, &oldProtect)) {
                    iat->u1.Function =
                        reinterpret_cast<ULONGLONG>(&HookCoCreateInstance);
                    VirtualProtect(iat, sizeof(IMAGE_THUNK_DATA),
                                   oldProtect, &oldProtect);
                    FlushInstructionCache(GetCurrentProcess(),
                                          iat, sizeof(IMAGE_THUNK_DATA));
                    patched = true;
                }
            }
            ++thunk; ++iat;
        }
        if (patched) return true;
        ++desc;
    }
    return false;
}

} // namespace

bool install_wmi() {
    std::lock_guard<std::mutex> lk(g_mtx);
    if (g_wmi_active) return true;

    HMODULE ole32 = GetModuleHandleA("ole32.dll");
    if (!ole32) return false;
    g_real_CoCreate = reinterpret_cast<CoCreateInstance_t>(
        GetProcAddress(ole32, "CoCreateInstance"));
    if (!g_real_CoCreate) return false;

    // Same set the registry hook serves, generated on first access.
    values();

    HMODULE mods[512];
    DWORD cb = 0;
    if (EnumProcessModules(GetCurrentProcess(), mods, sizeof mods, &cb)) {
        DWORD n = cb / sizeof(HMODULE);
        for (DWORD i = 0; i < n; ++i) {
            if (patch_iat_co_create(mods[i])) g_wmi_active = true;
        }
    }
    return g_wmi_active;
}

} // namespace hwid
