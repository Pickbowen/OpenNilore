#include "hwid_wmi_vtbl.h"

#include <windows.h>
#include <objbase.h>
#include <wbemidl.h>

#include <cstring>
#include <atomic>
#include <mutex>
#include <string>

#include "openzen.h"
#include "spoof_values.h"

#pragma comment(lib, "oleaut32.lib")
// ole32.lib is deliberately not linked: on x64 the real implementation of
// CoCreateInstance/CoInitializeEx lives in combase.dll, and linking the ole32
// import library would make the loader drag ole32.dll in on first use. That is
// exactly the load that crashed the game - a plain JVM has combase but no
// ole32, so the import would force a module in at the worst possible moment.

namespace hwid {

namespace {

// ---- method indices -------------------------------------------------------
// Verified against wbemidl.h: the vtable is laid out in declaration order and
// the three IUnknown methods come first.
//
//   IWbemServices: ... GetObject is index 6, ExecQuery is index 20
//   IEnumWbemClassObject: Reset(3), Next(4), ...
//
// GetObject matters as much as ExecQuery: Win32_ComputerSystemProduct and
// Win32_BaseBoard are singletons, and reading a singleton by path
// (GetObject("Win32_ComputerSystemProduct=@")) never touches ExecQuery. A
// spoof that only covers ExecQuery reports the real UUID on exactly the class
// the anticheat cares most about.
constexpr int kGetObjectIndex = 6;
constexpr int kExecQueryIndex = 20;
constexpr int kEnumNextIndex = 4;
constexpr int kVtblScanSlots = 48;

using GetObject_t = HRESULT (STDMETHODCALLTYPE*)(
    IWbemServices*, const BSTR, long, IWbemContext*, IWbemClassObject**, IWbemCallResult**);
using ExecQuery_t = HRESULT (STDMETHODCALLTYPE*)(
    IWbemServices*, const BSTR, const BSTR, long, IWbemContext*, IEnumWbemClassObject**);
using EnumNext_t = HRESULT (STDMETHODCALLTYPE*)(
    IEnumWbemClassObject*, long, ULONG, IWbemClassObject**, ULONG*);

GetObject_t g_real_get_object = nullptr;
ExecQuery_t g_real_exec_query = nullptr;
EnumNext_t g_real_enum_next = nullptr;

std::mutex g_mtx;
std::atomic<bool> g_active{false};
std::atomic<bool> g_services_hooked{false};
std::atomic<bool> g_enum_hooked{false};
std::atomic<long> g_put_ok{0};
std::atomic<long> g_put_fail{0};

bool set_page_protect(void* addr, size_t bytes, DWORD protect, DWORD* old_protect) {
    SYSTEM_INFO si{};
    GetSystemInfo(&si);
    const uintptr_t page = si.dwPageSize ? si.dwPageSize : 0x1000;
    uintptr_t start = reinterpret_cast<uintptr_t>(addr) & ~(page - 1);
    uintptr_t end = (reinterpret_cast<uintptr_t>(addr) + bytes + page - 1) & ~(page - 1);
    return VirtualProtect(reinterpret_cast<void*>(start), end - start,
                          protect, old_protect) != FALSE;
}

// Control Flow Guard rejects an indirect call to an address outside the CFG
// bitmap, and wbemcomn's call through the vtable is exactly such a call. When
// the mitigation is on, register our hook as a valid target instead of
// letting the first ExecQuery crash the process.
//
// The structures and the two exports are declared locally rather than pulled
// from winnt.h: they only exist in recent SDKs, and the layout is ABI-fixed.
struct CfgCallTargetInfo {
    ULONG_PTR Offset;
    ULONG Flags;
};
constexpr ULONG kCfgCallTargetValid = 0x00000001;
constexpr int kPolicyControlFlowGuard = 7;   // PROCESS_MITIGATION_POLICY value

bool register_cfg_target(void* fn) {
    using GetMitigation_t = BOOL (WINAPI*)(HANDLE, int, PVOID, SIZE_T);
    using SetTargets_t = BOOL (WINAPI*)(HANDLE, PVOID, SIZE_T, ULONG, CfgCallTargetInfo*);

    HMODULE k32 = GetModuleHandleA("kernel32.dll");
    if (!k32) return true;
    auto get_policy = reinterpret_cast<GetMitigation_t>(
        GetProcAddress(k32, "GetProcessMitigationPolicy"));
    auto set_targets = reinterpret_cast<SetTargets_t>(
        GetProcAddress(k32, "SetProcessValidCallTargets"));
    if (!get_policy || !set_targets) return true;   // pre-CFG Windows

    // PROCESS_MITIGATION_CONTROL_FLOW_GUARD_POLICY is a union whose DWORD is
    // all we need; bit 0 is EnableControlFlowGuard.
    ULONG flags = 0;
    if (!get_policy(GetCurrentProcess(), kPolicyControlFlowGuard, &flags, sizeof flags)) {
        return true;
    }
    if (!(flags & 0x1u)) return true;

    SYSTEM_INFO si{};
    GetSystemInfo(&si);
    const uintptr_t page = si.dwPageSize ? si.dwPageSize : 0x1000;
    void* base = reinterpret_cast<void*>(reinterpret_cast<uintptr_t>(fn) & ~(page - 1));

    CfgCallTargetInfo info{};
    info.Offset = static_cast<ULONG_PTR>(reinterpret_cast<uintptr_t>(fn)
                                         - reinterpret_cast<uintptr_t>(base));
    info.Flags = kCfgCallTargetValid;
    if (!set_targets(GetCurrentProcess(), base, page, 1, &info)) {
        openzen::log::error("WMI vtbl: CFG is on and the hook could not be registered");
        return false;
    }
    openzen::log::info("WMI vtbl: hook registered as a CFG call target");
    return true;
}

bool replace_slot(void** vtbl, int index, void* hook, void** original) {
    if (!vtbl || index < 0) return false;
    const size_t bytes = sizeof(void*) * kVtblScanSlots;
    DWORD old = 0;
    if (!set_page_protect(vtbl, bytes, PAGE_READWRITE, &old)) return false;
    if (original && !*original) *original = vtbl[index];
    vtbl[index] = hook;
    DWORD ignored = 0;
    set_page_protect(vtbl, bytes, old, &ignored);
    FlushInstructionCache(GetCurrentProcess(), vtbl, bytes);
    return true;
}

// ---- value rewriting ------------------------------------------------------
// The objects handed back by the provider are the real ones. Instead of
// wrapping them (which is what exposed the vptr), the spoofed property is
// written straight into the object with its own Put. Everything else the
// anticheat can read - __CLASS, __PATH, other properties, GetObjectText,
// Clone, CompareTo - then agrees with the value we report, because it is the
// same object.

std::wstring read_class(IWbemClassObject* obj) {
    VARIANT v;
    VariantInit(&v);
    CIMTYPE type = 0;
    long flavor = 0;
    std::wstring out;
    if (SUCCEEDED(obj->Get(L"__CLASS", 0, &v, &type, &flavor)) &&
        V_VT(&v) == VT_BSTR && V_BSTR(&v)) {
        out.assign(V_BSTR(&v), SysStringLen(V_BSTR(&v)));
    }
    VariantClear(&v);
    return out;
}

bool put_string(IWbemClassObject* obj, const wchar_t* prop, const std::wstring& value) {
    if (value.empty()) return false;
    VARIANT v;
    VariantInit(&v);
    V_VT(&v) = VT_BSTR;
    V_BSTR(&v) = SysAllocString(value.c_str());
    if (!V_BSTR(&v)) {
        VariantInit(&v);
        return false;
    }
    HRESULT hr = obj->Put(prop, 0, &v, CIM_STRING);
    VariantClear(&v);
    return SUCCEEDED(hr);
}

void patch_instance(IWbemClassObject* obj) {
    if (!obj) return;
    const std::wstring cls = read_class(obj);
    const SpoofValues& v = values();
    bool ok = false;

    if (cls == L"Win32_ComputerSystemProduct") {
        ok = put_string(obj, L"UUID", v.product_uuid) || ok;
        // IdentifyingNumber is the SMBIOS type-1 serial on a real box; keep it
        // agreeing with Win32_BIOS.SerialNumber.
        ok = put_string(obj, L"IdentifyingNumber", v.system_serial) || ok;
    } else if (cls == L"Win32_BIOS") {
        ok = put_string(obj, L"SerialNumber", v.system_serial) || ok;
    } else if (cls == L"Win32_BaseBoard") {
        ok = put_string(obj, L"SerialNumber", v.baseboard_serial) || ok;
    } else if (cls == L"Win32_DiskDrive" || cls == L"Win32_PhysicalMedia") {
        ok = put_string(obj, L"SerialNumber", v.disk_serial) || ok;
    }
    // Win32_Processor is deliberately absent: ProcessorId is a CPUID dump and
    // a spoofed value contradicts the instruction the scanner can run itself.

    if (ok) ++g_put_ok;
    else if (!cls.empty()) ++g_put_fail;

    // Report periodically rather than per call: this runs inside a COM call
    // the game is waiting on, and the counts are what tell us whether the
    // provider accepted the in-place Put (it is a plain COM call, but a
    // provider is free to reject a write to a projected property).
    const long total = g_put_ok.load() + g_put_fail.load();
    if (total > 0 && total % 32 == 0) {
        openzen::log::info("WMI vtbl: put ok=%ld fail=%ld", g_put_ok.load(), g_put_fail.load());
    }
}

void hook_enum_vtable(IEnumWbemClassObject* en);

HRESULT STDMETHODCALLTYPE HookNext(IEnumWbemClassObject* self, long timeout, ULONG count,
                                   IWbemClassObject** objects, ULONG* returned) {
    HRESULT hr = g_real_enum_next(self, timeout, count, objects, returned);
    if (SUCCEEDED(hr) && objects && returned && *returned > 0) {
        for (ULONG i = 0; i < *returned; ++i) patch_instance(objects[i]);
    }
    return hr;
}

void hook_enum_vtable(IEnumWbemClassObject* en) {
    if (!en) return;
    bool expected = false;
    if (!g_enum_hooked.compare_exchange_strong(expected, true)) return;
    void** vtbl = *reinterpret_cast<void***>(en);
    if (replace_slot(vtbl, kEnumNextIndex, reinterpret_cast<void*>(&HookNext),
                     reinterpret_cast<void**>(&g_real_enum_next))) {
        openzen::log::info("WMI vtbl: IEnumWbemClassObject::Next hooked (%p -> %p)",
                           reinterpret_cast<void*>(g_real_enum_next),
                           reinterpret_cast<void*>(&HookNext));
    }
}

HRESULT STDMETHODCALLTYPE HookGetObject(IWbemServices* self, const BSTR path, long flags,
                                        IWbemContext* ctx, IWbemClassObject** object,
                                        IWbemCallResult** call_result) {
    HRESULT hr = g_real_get_object(self, path, flags, ctx, object, call_result);
    if (SUCCEEDED(hr) && object && *object) patch_instance(*object);
    return hr;
}

// NOTE on ExecQuery's argument order. The MSDN prose lists (strQuery,
// strQueryLanguage), but the actual vtable that MIDL generates is
// (strQueryLanguage, strQuery) - the language comes FIRST:
//
//   HRESULT ExecQuery(IWbemServices*, const BSTR strQueryLanguage,
//                     const BSTR strQuery, long, IWbemContext*,
//                     IEnumWbemClassObject**);
//
// Verified against um/wbemcli.h (IWbemServicesVtbl). Calling it the documented
// way returns WBEM_E_INVALID_QUERY_TYPE (0x80041018) for every query. Because
// this hook forwards by position, it is transparent either way, but the
// parameters are named for what they actually are so nobody "fixes" them.
HRESULT STDMETHODCALLTYPE HookExecQuery(IWbemServices* self, const BSTR lang, const BSTR query,
                                        long flags, IWbemContext* ctx,
                                        IEnumWbemClassObject** out) {
    HRESULT hr = g_real_exec_query(self, lang, query, flags, ctx, out);
    if (SUCCEEDED(hr) && out && *out) hook_enum_vtable(*out);
    return hr;
}

// ---- vtable acquisition ---------------------------------------------------

using CoCreateInstance_t = HRESULT (STDAPICALLTYPE*)(REFCLSID, LPUNKNOWN, DWORD, REFIID, LPVOID*);
using CoInitializeEx_t = HRESULT (STDAPICALLTYPE*)(LPVOID, DWORD);

struct ComApis {
    CoCreateInstance_t create = nullptr;
    CoInitializeEx_t init = nullptr;
};

// Resolve COM entry points from a module that is ALREADY mapped.
//
// This is the fix for the crash: a plain JVM maps combase.dll, rpcrt4.dll and
// oleaut32.dll, but NOT ole32.dll. The previous code called LoadLibrary on
// ole32 when GetModuleHandle came back empty, which pulled the whole COM
// facade plus its dependency chain into a process that was still starting the
// JVM - faulting at 0xc0000005 in no mapped module. combase exports both
// CoCreateInstance and CoInitializeEx, so ole32 is never needed.
//
// Nothing here loads a module. If neither host is mapped yet, the caller
// defers and retries later, which is always safe.
bool resolve_com(ComApis& apis) {
    HMODULE host = GetModuleHandleA("combase.dll");
    const char* host_name = "combase.dll";
    if (!host) {
        host = GetModuleHandleA("ole32.dll");
        host_name = "ole32.dll";
    }
    if (!host) {
        openzen::log::info("WMI vtbl: neither combase nor ole32 mapped yet; deferring");
        return false;
    }
    apis.create = reinterpret_cast<CoCreateInstance_t>(
        GetProcAddress(host, "CoCreateInstance"));
    apis.init = reinterpret_cast<CoInitializeEx_t>(
        GetProcAddress(host, "CoInitializeEx"));
    openzen::log::info("WMI vtbl: com host=%s base=%p create=%p init=%p",
                       host_name, (void*)host,
                       reinterpret_cast<void*>(apis.create),
                       reinterpret_cast<void*>(apis.init));
    return apis.create && apis.init;
}

bool hook_services_vtable() {
    ComApis apis;
    if (!resolve_com(apis)) return false;
    if (!apis.create || !apis.init) {
        openzen::log::error("WMI vtbl: COM entry points not exported by the host");
        return false;
    }

    openzen::log::info("WMI vtbl: calling CoInitializeEx");
    HRESULT hr = apis.init(nullptr, COINIT_MULTITHREADED);
    // RPC_E_CHANGED_MODE is fine: the process already lives in an apartment
    // (AWT uses COM), and CoCreateInstance still works from this thread.
    openzen::log::info("WMI vtbl: CoInitializeEx -> 0x%08lX", (unsigned long)hr);
    if (FAILED(hr) && hr != static_cast<HRESULT>(RPC_E_CHANGED_MODE)) return false;

    openzen::log::info("WMI vtbl: calling CoCreateInstance(CLSID_WbemLocator)");
    IWbemLocator* locator = nullptr;
    hr = apis.create(CLSID_WbemLocator, nullptr, CLSCTX_INPROC_SERVER, IID_IWbemLocator,
                     reinterpret_cast<void**>(&locator));
    openzen::log::info("WMI vtbl: CoCreateInstance(WbemLocator) -> 0x%08lX locator=%p",
                       (unsigned long)hr, (void*)locator);
    if (FAILED(hr) || !locator) return false;

    openzen::log::info("WMI vtbl: calling ConnectServer(ROOT\\CIMV2)");
    BSTR ns = SysAllocString(L"ROOT\\CIMV2");
    IWbemServices* services = nullptr;
    hr = locator->ConnectServer(ns, nullptr, nullptr, nullptr, 0, nullptr, nullptr, &services);
    SysFreeString(ns);
    openzen::log::info("WMI vtbl: ConnectServer -> 0x%08lX services=%p",
                       (unsigned long)hr, (void*)services);
    if (FAILED(hr) || !services) {
        locator->Release();
        return false;
    }

    void** vtbl = *reinterpret_cast<void***>(services);
    openzen::log::info("WMI vtbl: services vtable=%p (module base of slot0=%p)",
                       (void*)vtbl, (void*)vtbl[0]);

    bool ok = replace_slot(vtbl, kGetObjectIndex, reinterpret_cast<void*>(&HookGetObject),
                           reinterpret_cast<void**>(&g_real_get_object));
    ok = replace_slot(vtbl, kExecQueryIndex, reinterpret_cast<void*>(&HookExecQuery),
                      reinterpret_cast<void**>(&g_real_exec_query)) || ok;
    services->Release();
    locator->Release();

    if (ok) {
        openzen::log::info("WMI vtbl: IWbemServices hooked (GetObject %p->%p, ExecQuery %p->%p)",
                           reinterpret_cast<void*>(g_real_get_object),
                           reinterpret_cast<void*>(&HookGetObject),
                           reinterpret_cast<void*>(g_real_exec_query),
                           reinterpret_cast<void*>(&HookExecQuery));
    }
    return ok;
}

} // namespace

// The install path, with no fault handling of its own: the guard below runs it
// inside __try so any access violation while probing COM is caught.
static int install_wmi_vtbl_inner() {
    // No pre-check for wbemcomn: creating a WbemLocator loads it, and that is
    // the only module the vtable lives in.
    openzen::log::info("WMI vtbl: step 1/4 CFG targets");
    if (!register_cfg_target(reinterpret_cast<void*>(&HookGetObject))) return -1;
    if (!register_cfg_target(reinterpret_cast<void*>(&HookExecQuery))) return -1;
    if (!register_cfg_target(reinterpret_cast<void*>(&HookNext))) return -1;

    openzen::log::info("WMI vtbl: step 2/4 reaching WBEM services");
    if (!hook_services_vtable()) return -1;

    g_active.store(true);
    openzen::log::info("WMI vtbl: active");
    return 1;
}

// This DLL runs inside a live game process. Bringing COM up there can fault
// for reasons outside our control: a loader state that is not settled, an
// apartment the JVM already claimed, or a protector's hook on combase. A
// failed probe must degrade to "no spoof" - it must never take the game down
// with it, which is exactly what happened twice before this guard existed
// (faulting module "unknown", 0xc0000005).
//
// __try/__except is the right tool: it catches an access violation inside the
// probe and turns it into a logged failure. It has to live in a function with
// no C++ locals, because a function that needs object unwinding cannot carry
// SEH (C2712) - hence the split from install_wmi_vtbl(), which holds the lock.
static int install_wmi_vtbl_guarded() {
    DWORD fault = 0;
    int rc = -1;
    __try {
        rc = install_wmi_vtbl_inner();
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        fault = GetExceptionCode();
    }

    if (fault) {
        openzen::log::error("WMI vtbl: fault 0x%08lX during install; spoof disabled, "
                            "process left running", fault);
        return -1;
    }
    return rc;
}

int install_wmi_vtbl() {
    std::lock_guard<std::mutex> lk(g_mtx);
    if (g_active.load()) return 1;
    return install_wmi_vtbl_guarded();
}

bool wmi_vtbl_active() { return g_active.load(); }

} // namespace hwid
