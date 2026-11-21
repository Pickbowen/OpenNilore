#include "hwid_wmi_vtbl.h"

#include <windows.h>
#include <objbase.h>
#include <wbemidl.h>

#include <atomic>
#include <cwchar>
#include <initializer_list>
#include <mutex>
#include <string>

#include "openzen.h"
#include "hwid_config.h"
#include "nt_guard.h"
#include "spoof_values.h"

#pragma comment(lib, "oleaut32.lib")
// ole32.lib is deliberately not linked: on x64 the real implementation of
// CoCreateInstance/CoInitializeEx lives in combase.dll, and linking the ole32
// import library would make the loader drag ole32.dll in on first use. That is
// exactly the load that crashed the game - a plain JVM has combase but no
// ole32, so the import would force a module in at the worst possible moment.

namespace hwid {

namespace {

// ---- retry quieting -------------------------------------------------------
// The early attempt retries once; those attempts write the whole step log and
// would otherwise duplicate it. Errors are never suppressed.
std::atomic<bool> g_quiet{false};

// ---- hook-path discipline -------------------------------------------------
// Every hook below runs on whatever thread called the COM method - which, when
// the anticheat queries WMI during a server join, is the thread the game is
// waiting on. Nothing on that path may block: no file I/O (the log opens the
// file and calls FlushFileBuffers on every line), no VirtualAlloc, no walk of
// the address space. A hook that stalls here does not look like a slow hook, it
// looks like a frozen client.
thread_local bool t_in_hook = false;

struct HookScope {
    const bool outer;
    HookScope() : outer(!t_in_hook) { t_in_hook = true; }
    ~HookScope() { if (outer) t_in_hook = false; }
};

template <typename... A>
void note(const char* fmt, A... a) {
    if (g_quiet.load(std::memory_order_relaxed)) return;
    if (t_in_hook) return;   // never touch the disk from inside a COM call
    openzen::log::info(fmt, a...);
}

// Relays are prepared during install, never on the hook path: relay_for()
// takes the ntguard guard and may carve a cave, both of which can take locks
// and log.
struct FastRelay {
    void* detour = nullptr;
    void* relay = nullptr;
};
constexpr int kFastRelays = 8;
FastRelay g_fast_relays[kFastRelays];
std::atomic<int> g_fast_relay_count{0};

void* relay_for_hook(void* detour) {
    const int n = g_fast_relay_count.load(std::memory_order_acquire);
    for (int i = 0; i < n; ++i) {
        if (g_fast_relays[i].detour == detour) return g_fast_relays[i].relay;
    }
    return nullptr;
}

// Install-time only.
void prepare_relay(void* detour) {
    if (!detour || relay_for_hook(detour)) return;
    void* relay = ntguard::relay_for(detour);
    if (!relay) return;
    const int i = g_fast_relay_count.load();
    if (i >= kFastRelays) return;
    g_fast_relays[i].detour = detour;
    g_fast_relays[i].relay = relay;
    g_fast_relay_count.store(i + 1, std::memory_order_release);
}

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
// IWbemClassObject: GetQualifierSet(3), Get(4), Put(5), ...
constexpr int kObjectGetIndex = 4;

// A single process routinely holds more than one table for the same interface:
//
//   * the in-proc provider object (wbemcomn), and
//   * the cross-apartment proxy combase marshals into the caller's apartment,
//
// with different Get/Next slots in each. Recording one table (and one saved
// original) meant every table after the first stayed unpatched, and worse: the
// saved original belonged to a different implementation, so forwarding would
// have called the wrong function on the wrong object. Eight is far more than
// the two or three that actually appear; overflow is logged, never silent.
constexpr int kMaxTablesPerInterface = 8;

// Most objects patched inside one Next()/GetObject() call. Bounds what a single
// caller can pay for; see the loop in HookNext.
constexpr ULONG kMaxObjectsPerCall = 64;

using GetObject_t = HRESULT (STDMETHODCALLTYPE*)(
    IWbemServices*, const BSTR, long, IWbemContext*, IWbemClassObject**, IWbemCallResult**);
using ExecQuery_t = HRESULT (STDMETHODCALLTYPE*)(
    IWbemServices*, const BSTR, const BSTR, long, IWbemContext*, IEnumWbemClassObject**);
using EnumNext_t = HRESULT (STDMETHODCALLTYPE*)(
    IEnumWbemClassObject*, long, ULONG, IWbemClassObject**, ULONG*);
using ObjGet_t = HRESULT (STDMETHODCALLTYPE*)(
    IWbemClassObject*, LPCWSTR, long, VARIANT*, CIMTYPE*, long*);

// ---- state ----------------------------------------------------------------
// g_mtx serialises install/retry passes. g_hook_mtx serialises everything that
// publishes a saved original or writes a vtable slot, because the hooks run on
// arbitrary COM threads and two of them can see a brand-new table at once.
std::mutex g_mtx;
std::mutex g_hook_mtx;

std::atomic<bool> g_patch_live{false};         // at least one slot is patched
std::atomic<bool> g_services_complete{false};  // both IWbemServices slots are patched
std::atomic<bool> g_vetoed{false};             // self check read the real value on every property
std::atomic<int>  g_status{kWmiVtblUnavailable};

// Install attempts are capped. Bringing COM up inside a game process is the
// riskiest thing this module does (it faulted twice before the SEH guard), so a
// failed attempt is worth one retry and no more; the client retries the whole
// surface pass later anyway.
constexpr int kMaxInstallAttempts = 2;
std::atomic<int> g_install_attempts{0};

// Writing the property in place is the cleanest spoof - the object stays the
// real one, so GetObjectText/Clone agree with Get. It only works when the
// IWbemClassObject is a direct in-process object.
//
// It is not always: inside the game process the anticheat's COM apartment is
// already established, CoCreateInstance returns a cross-apartment proxy, and
// every Put comes back rejected (observed: ok=0 fail=160). Once that happens
// we stop writing and intercept Get instead, which needs no write access.
std::atomic<bool> g_use_get_hook{false};

// Counters are per outcome, never merged: an unrelated class is not a write
// failure, a partial write is not a success, and an allocation failure is not
// a silent no-op.
// Entry counters, read by the heartbeat. Relaxed: they are diagnostics.
std::atomic<long> g_hook_get_object{0};
std::atomic<long> g_hook_exec_query{0};
std::atomic<long> g_hook_next{0};
std::atomic<long> g_hook_object_get{0};

std::atomic<long> g_put_ok{0};
std::atomic<long> g_put_fail{0};
std::atomic<long> g_put_partial{0};
std::atomic<long> g_put_unmatched{0};
std::atomic<long> g_put_absent{0};
std::atomic<long> g_alloc_fail{0};
std::atomic<long> g_protect_restore_fail{0};

void note_alloc_failure(const char* where) {
    const long n = ++g_alloc_fail;
    if (n <= 3 && !t_in_hook) {
        openzen::log::error("WMI vtbl: BSTR allocation failed in %s (count=%ld); "
                            "the real value is passed through unchanged", where, n);
    }
}

// ---- page protection ------------------------------------------------------
// The old code flipped PAGE_READWRITE over kVtblScanSlots*sizeof(void*) = 384
// bytes, which spans two pages, and restored a single `old` value over both.
// VirtualProtect only reports the protection of the first page it touches, so
// the second page could be left writable - or restored to the wrong protection
// - and the restore result was dropped entirely.
//
// This guard covers exactly the slot being written, records one protection per
// page, restores each page individually, and reports whether the restore
// worked. It also self-restores if the acquisition fails halfway.
class PageWriter {
public:
    ~PageWriter() {
        if (active_) restore();
    }

    bool acquire(void* addr, size_t bytes) {
        SYSTEM_INFO si{};
        GetSystemInfo(&si);
        page_ = si.dwPageSize ? static_cast<size_t>(si.dwPageSize) : 0x1000u;

        const uintptr_t begin = reinterpret_cast<uintptr_t>(addr) & ~(page_ - 1);
        const uintptr_t end =
            (reinterpret_cast<uintptr_t>(addr) + bytes + page_ - 1) & ~(page_ - 1);
        uintptr_t span = end > begin ? end - begin : page_;
        if (span > page_ * 2) span = page_ * 2;   // one slot never spans more than two

        count_ = static_cast<int>(span / page_);
        if (count_ < 1) count_ = 1;

        for (int i = 0; i < count_; ++i) {
            void* target = reinterpret_cast<void*>(
                begin + static_cast<uintptr_t>(i) * page_);
            DWORD old = 0;
            if (!VirtualProtect(target, page_, PAGE_READWRITE, &old)) {
                openzen::log::error("WMI vtbl: VirtualProtect(RW) failed at %p (0x%08lX)",
                                    target, static_cast<unsigned long>(GetLastError()));
                restore();   // undo the pages we already changed
                return false;
            }
            base_[i] = target;
            old_[i] = old;
            live_[i] = true;
            active_ = true;
        }
        return true;
    }

    // Returns false when at least one page could not be put back.
    bool restore() {
        if (!active_) return true;
        bool ok = true;
        for (int i = 0; i < count_; ++i) {
            if (!live_[i]) continue;
            DWORD ignored = 0;
            if (!VirtualProtect(base_[i], page_, old_[i], &ignored)) {
                ok = false;
                openzen::log::error(
                    "WMI vtbl: restoring page protection at %p failed (0x%08lX)",
                    base_[i], static_cast<unsigned long>(GetLastError()));
            }
            live_[i] = false;
        }
        active_ = false;
        return ok;
    }

private:
    void*  base_[2] = {nullptr, nullptr};
    DWORD  old_[2]  = {0, 0};
    bool   live_[2] = {false, false};
    size_t page_ = 0x1000;
    int    count_ = 0;
    bool   active_ = false;
};

enum class SlotOutcome { Failed, AlreadyHooked, Hooked };

struct SlotPatch {
    SlotOutcome outcome = SlotOutcome::Failed;
    void* previous = nullptr;
};

// Write one vtable slot with the page writable for exactly that slot.
SlotPatch install_slot(void** vtbl, int index, void* hook) {
    SlotPatch result;
    if (!vtbl) return result;

    void** slot = &vtbl[index];
    if (*slot == hook) {
        result.outcome = SlotOutcome::AlreadyHooked;
        result.previous = hook;
        return result;
    }

    PageWriter pages;
    if (!pages.acquire(slot, sizeof(void*))) return result;

    // Publish the hook atomically: a COM thread reading this slot either sees
    // the real method or ours, never a half-written pointer.
    result.previous = reinterpret_cast<void*>(InterlockedExchangePointer(
        reinterpret_cast<PVOID volatile*>(slot), reinterpret_cast<PVOID>(hook)));

    const bool restored = pages.restore();
    FlushInstructionCache(GetCurrentProcess(), slot, sizeof(void*));

    if (!restored) {
        // The patch is live; the page is simply still writable. Count it and
        // say so - the previous version dropped this result on the floor.
        const long n = ++g_protect_restore_fail;
        if (n <= 3) {
            openzen::log::error("WMI vtbl: slot %d patched but its page protection could "
                                "not be restored (count=%ld)", index, n);
        }
    }

    result.outcome = (result.previous == hook) ? SlotOutcome::AlreadyHooked
                                               : SlotOutcome::Hooked;
    return result;
}

// ---- table registry -------------------------------------------------------
// Lock-free for readers (the hooks), serialised for writers (install paths).
// An entry is published - `original` first, then the count with release
// ordering - BEFORE the slot is patched. A hook can therefore only ever be
// entered after the original it needs is visible: the slot write is what makes
// the hook reachable, and that happens strictly after the release store.
template <typename Fn, int Capacity>
class VtblTable {
public:
    Fn lookup(void** vtbl) const {
        if (!vtbl) return nullptr;
        const int n = count_.load(std::memory_order_acquire);
        for (int i = 0; i < n; ++i) {
            if (tables_[i].vtbl.load(std::memory_order_relaxed) == vtbl) {
                return tables_[i].original;
            }
        }
        return nullptr;
    }

    // Last-resort original for a table that was never registered. Should not be
    // reachable: a vtable is only hooked after its entry exists. Kept so a hook
    // entered through a table some other patch installed degrades to a call
    // instead of a crash.
    Fn primary() const {
        return count_.load(std::memory_order_acquire) > 0 ? tables_[0].original : nullptr;
    }

    int size() const { return count_.load(std::memory_order_acquire); }

    // Caller holds g_hook_mtx.
    bool publish(void** vtbl, Fn original) {
        const int n = count_.load(std::memory_order_relaxed);
        if (n >= Capacity) return false;
        tables_[n].vtbl.store(vtbl, std::memory_order_relaxed);
        tables_[n].original = original;
        count_.store(n + 1, std::memory_order_release);
        return true;
    }

    // Caller holds g_hook_mtx. Undo a publish whose slot write failed.
    void retire(void** vtbl) {
        const int n = count_.load(std::memory_order_relaxed);
        for (int i = 0; i < n; ++i) {
            if (tables_[i].vtbl.load(std::memory_order_relaxed) == vtbl) {
                tables_[i].vtbl.store(nullptr, std::memory_order_relaxed);
            }
        }
    }

private:
    struct Entry {
        std::atomic<void*> vtbl{nullptr};
        Fn original = nullptr;   // immutable once published
    };
    Entry tables_[Capacity]{};
    std::atomic<int> count_{0};
};

VtblTable<ObjGet_t, kMaxTablesPerInterface>    g_object_get;
VtblTable<EnumNext_t, kMaxTablesPerInterface>  g_enum_next;
VtblTable<GetObject_t, kMaxTablesPerInterface> g_services_get_object;
VtblTable<ExecQuery_t, kMaxTablesPerInterface> g_services_exec_query;

// ---- forward declarations -------------------------------------------------
HRESULT STDMETHODCALLTYPE HookObjGet(IWbemClassObject* self, LPCWSTR name, long flags,
                                     VARIANT* pVal, CIMTYPE* pType, long* plFlavor);
HRESULT STDMETHODCALLTYPE HookNext(IEnumWbemClassObject* self, long timeout, ULONG count,
                                   IWbemClassObject** objects, ULONG* returned);
HRESULT STDMETHODCALLTYPE HookGetObject(IWbemServices* self, const BSTR path, long flags,
                                        IWbemContext* ctx, IWbemClassObject** object,
                                        IWbemCallResult** call_result);
HRESULT STDMETHODCALLTYPE HookExecQuery(IWbemServices* self, const BSTR lang, const BSTR query,
                                        long flags, IWbemContext* ctx,
                                        IEnumWbemClassObject** out);
void patch_instance(IWbemClassObject* obj);

// ---- value rewriting ------------------------------------------------------
// The objects handed back by the provider are the real ones. Instead of
// wrapping them (which is what exposed the vptr), the spoofed property is
// written straight into the object with its own Put. Everything else the
// anticheat can read - __CLASS, __PATH, other properties, GetObjectText,
// Clone, CompareTo - then agrees with the value we report, because it is the
// same object.

// __CLASS is read through the original Get, bypassing the hook, so this cannot
// recurse into itself. If the table is not in the registry the slot is still
// the real one, so calling it directly is equivalent.
std::wstring read_class(IWbemClassObject* obj) {
    std::wstring out;
    if (!obj) return out;

    VARIANT v;
    VariantInit(&v);
    CIMTYPE type = 0;
    long flavor = 0;

    ObjGet_t real = g_object_get.lookup(*reinterpret_cast<void***>(obj));
    const HRESULT hr = real ? real(obj, L"__CLASS", 0, &v, &type, &flavor)
                            : obj->Get(L"__CLASS", 0, &v, &type, &flavor);

    if (SUCCEEDED(hr) && V_VT(&v) == VT_BSTR && V_BSTR(&v)) {
        out.assign(V_BSTR(&v), SysStringLen(V_BSTR(&v)));
    }
    VariantClear(&v);
    return out;
}

enum class PutOutcome {
    Skipped,        // nothing to write (no value derived) - not a failure
    NotApplicable,  // the instance does not carry the property, or it is read-only
    Ok,             // the object accepted the write
    Failed,         // the object exists and refused the write - proxy or access
};

PutOutcome put_string(IWbemClassObject* obj, LPCWSTR prop, const std::wstring& value) {
    if (!obj || !prop) return PutOutcome::Failed;
    if (value.empty()) return PutOutcome::Skipped;

    VARIANT v;
    VariantInit(&v);
    V_VT(&v) = VT_BSTR;
    V_BSTR(&v) = SysAllocString(value.c_str());
    if (!V_BSTR(&v)) {
        note_alloc_failure("Put");
        VariantClear(&v);
        return PutOutcome::Failed;
    }

    const HRESULT hr = obj->Put(prop, 0, &v, CIM_STRING);
    VariantClear(&v);
    if (FAILED(hr)) {
        // Not every refusal is a proxy problem.
        //
        // Win32_ComputerSystemProduct answers UUID on every machine but does
        // not always carry IdentifyingNumber, and a class property can be
        // read-only; those come back as NOT_FOUND / READ_ONLY /
        // INVALID_PARAMETER. Escalating the object to Get interception for them
        // buys nothing - the property is absent, so there is no value to
        // intercept either - and it costs a patched IWbemClassObject::Get plus
        // a nested COM call per read. Observed on this sample: IdentifyingNumber
        // refused, UUID written, and the anticheat only selects UUID.
        if (hr == WBEM_E_NOT_FOUND || hr == WBEM_E_READ_ONLY ||
            hr == WBEM_E_INVALID_PARAMETER) {
            static std::atomic<int> logged{0};
            if (logged.fetch_add(1) < 4) {
                note("WMI vtbl: Put(%ls) hr=0x%08lX -> property not applicable, "
                     "not a failure", prop, static_cast<unsigned long>(hr));
            }
            return PutOutcome::NotApplicable;
        }
        if (config().verbose) {
            note("WMI vtbl: Put(%ls) rejected hr=0x%08lX",
                               prop, static_cast<unsigned long>(hr));
        }
        return PutOutcome::Failed;
    }
    return PutOutcome::Ok;
}

// Which property of which class gets replaced, and with what. Only the classes
// and properties the anticheat actually queries are answered; everything else
// falls through untouched.
const std::wstring* pick_fake_for(const std::wstring& cls, LPCWSTR name) {
    if (!name) return nullptr;
    const SpoofValues& v = values();
    if (cls == L"Win32_ComputerSystemProduct") {
        if (_wcsicmp(name, L"UUID") == 0) return &v.product_uuid;
        if (_wcsicmp(name, L"IdentifyingNumber") == 0) return &v.system_serial;
        return nullptr;
    }
    if (cls == L"Win32_BIOS") {
        if (_wcsicmp(name, L"SerialNumber") == 0) return &v.system_serial;
        return nullptr;
    }
    if (cls == L"Win32_BaseBoard") {
        if (_wcsicmp(name, L"SerialNumber") == 0) return &v.baseboard_serial;
        return nullptr;
    }
    if (cls == L"Win32_DiskDrive" || cls == L"Win32_PhysicalMedia") {
        if (_wcsicmp(name, L"SerialNumber") == 0) return &v.disk_serial;
        return nullptr;
    }
    // Win32_Processor is deliberately absent: ProcessorId is a CPUID dump.
    return nullptr;
}

// Properties any of the handled classes can carry. Checked before __CLASS is
// even read: an unrelated Get (there are dozens per object) must not pay for a
// recursive COM call.
bool is_spoofable_name(LPCWSTR name) {
    if (!name) return false;
    return _wcsicmp(name, L"UUID") == 0 ||
           _wcsicmp(name, L"IdentifyingNumber") == 0 ||
           _wcsicmp(name, L"SerialNumber") == 0;
}

HRESULT STDMETHODCALLTYPE HookObjGet(IWbemClassObject* self, LPCWSTR name, long flags,
                                     VARIANT* pVal, CIMTYPE* pType, long* plFlavor) {
    if (!self) return E_POINTER;
    g_hook_object_get.fetch_add(1, std::memory_order_relaxed);

    // Re-entered from our own read_class(): forward without rewriting, so a
    // table that somehow escaped registration degrades to a pass-through
    // instead of recursing until the stack dies.
    const HookScope scope;

    // Forward through the original that belongs to *this* object's table.
    ObjGet_t real = g_object_get.lookup(*reinterpret_cast<void***>(self));
    if (!real) real = g_object_get.primary();
    if (!real) return E_FAIL;

    HRESULT hr = real(self, name, flags, pVal, pType, plFlavor);
    if (!scope.outer) return hr;
    if (FAILED(hr) || !name || !pVal) return hr;
    if (!is_spoofable_name(name)) return hr;
    if (V_VT(pVal) != VT_BSTR || !V_BSTR(pVal)) return hr;

    const std::wstring cls = read_class(self);
    const std::wstring* fake = pick_fake_for(cls, name);
    if (!fake || fake->empty()) return hr;

    // Allocate BEFORE touching the caller's variant. The old order cleared
    // pVal first, so a failed SysAllocString left an empty VT_BSTR behind
    // while still returning S_OK: the caller saw success with no value. Now a
    // failed allocation leaves the real value intact.
    BSTR replacement = SysAllocString(fake->c_str());
    if (!replacement) {
        note_alloc_failure("Get");
        return hr;
    }

    VariantClear(pVal);
    VariantInit(pVal);
    V_VT(pVal) = VT_BSTR;
    V_BSTR(pVal) = replacement;
    if (pType) *pType = CIM_STRING;
    if (plFlavor) *plFlavor = WBEM_FLAVOR_FLAG_PROPAGATE_TO_INSTANCE;
    return hr;
}

// ---- table hooking --------------------------------------------------------

// Caller holds g_hook_mtx. Registers the original, patches the slot, and
// un-registers if the write failed, so a retry is possible.
template <typename Fn, int Capacity>
bool hook_slot_with_table(void** vtbl, int index, Fn hook,
                          VtblTable<Fn, Capacity>& table, const char* what) {
    if (!vtbl) return false;
    if (table.lookup(vtbl)) return true;   // this table is covered already

    Fn original = reinterpret_cast<Fn>(vtbl[index]);
    if (!original || reinterpret_cast<void*>(original) == reinterpret_cast<void*>(hook)) {
        openzen::log::error("WMI vtbl: %s slot already holds a foreign pointer; leaving it", what);
        return false;
    }

    // Interpose a relay inside ntdll so the slot never points at this DLL.
    //
    // This DLL is unlinked from the PEB and NtQueryVirtualMemory reports its
    // pages as MEM_FREE (see nt_guard). A vtable slot holding an address inside
    // that hidden range is a contradiction on its own: "callable function
    // pointer located in an uncommitted page". Pointing the slot at an ntdll
    // relay keeps the visible state ordinary - a signed image, committed,
    // executable - while the actual detour stays hidden. It also means the slot
    // target's bytes are ntdll's, so a prologue read of it finds nothing.
    void* patch_target = relay_for_hook(reinterpret_cast<void*>(hook));
    const bool relayed = patch_target != nullptr;
    if (!patch_target) patch_target = reinterpret_cast<void*>(hook);

    // Publish first: the hook can only be reached once the slot points at it,
    // and by then the original must already be visible to the reader.
    if (!table.publish(vtbl, original)) {
        openzen::log::error("WMI vtbl: %s table registry is full (%d); not patching",
                            what, Capacity);
        return false;
    }

    const SlotPatch patch = install_slot(vtbl, index, patch_target);
    if (patch.outcome == SlotOutcome::Failed) {
        table.retire(vtbl);
        openzen::log::error("WMI vtbl: %s patch failed", what);
        return false;
    }
    if (patch.previous && patch.previous != reinterpret_cast<void*>(original)) {
        // Someone patched the slot between our read and our exchange. Our
        // saved original is still correct for the slot we intended to save,
        // but say so instead of pretending this was clean.
        openzen::log::error("WMI vtbl: %s slot changed during patch (%p != expected %p)",
                            what, patch.previous, reinterpret_cast<void*>(original));
    }

    note("WMI vtbl: %s hooked on table #%d (vtbl=%p real=%p slot->%p%s)",
                       what, table.size(), reinterpret_cast<void*>(vtbl),
                       reinterpret_cast<void*>(original), patch_target,
                       relayed ? " via ntdll relay" : " (direct; data-only mode)");
    return true;
}

// Patch IWbemClassObject::Get on the class vtable this instance belongs to.
// Every distinct table is recorded, not just the first: in-proc provider
// objects and cross-apartment proxies do not share one.
bool hook_object_vtable(IWbemClassObject* obj) {
    if (!obj) return false;
    void** vtbl = *reinterpret_cast<void***>(obj);
    if (!vtbl) return false;

    if (g_object_get.lookup(vtbl)) return false;   // lock-free fast path

    std::lock_guard<std::mutex> lk(g_hook_mtx);
    if (g_object_get.lookup(vtbl)) return false;   // lost the race, already covered
    return hook_slot_with_table(vtbl, kObjectGetIndex, &HookObjGet,
                                g_object_get, "IWbemClassObject::Get");
}

void patch_instance(IWbemClassObject* obj) {
    if (!obj) return;

    // Escape hatch from configuration: force the read-interception path even
    // where in-place Put works, so the fallback can be exercised on demand.
    if (config().force_get_hook) g_use_get_hook.store(true, std::memory_order_relaxed);

    // Once a Put has been rejected we know this apartment is giving us proxy
    // objects; stop trying to write and intercept reads instead.
    if (g_use_get_hook.load(std::memory_order_relaxed)) {
        hook_object_vtable(obj);
        return;
    }

    const std::wstring cls = read_class(obj);
    if (cls.empty()) {
        // __CLASS itself is unavailable: nothing can be decided about this
        // object, and that is not a write failure.
        ++g_put_unmatched;
        return;
    }

    const SpoofValues& v = values();
    int attempted = 0;   // properties the object should have carried
    int written = 0;
    int refused = 0;
    int absent = 0;      // no fake value to write

    auto write = [&](LPCWSTR prop, const std::wstring& value) {
        switch (put_string(obj, prop, value)) {
            case PutOutcome::Skipped:
            case PutOutcome::NotApplicable:
                // Nothing was written and nothing was refused: either there is
                // no fake value, or this instance does not carry the property.
                ++absent;
                break;
            case PutOutcome::Ok:
                ++attempted;
                ++written;
                break;
            case PutOutcome::Failed:
                ++attempted;
                ++refused;
                ++g_put_fail;
                break;
        }
    };

    if (cls == L"Win32_ComputerSystemProduct") {
        write(L"UUID", v.product_uuid);
        // IdentifyingNumber is the SMBIOS type-1 serial on a real box; keep it
        // agreeing with Win32_BIOS.SerialNumber.
        write(L"IdentifyingNumber", v.system_serial);
    } else if (cls == L"Win32_BIOS") {
        write(L"SerialNumber", v.system_serial);
    } else if (cls == L"Win32_BaseBoard") {
        write(L"SerialNumber", v.baseboard_serial);
    } else if (cls == L"Win32_DiskDrive" || cls == L"Win32_PhysicalMedia") {
        write(L"SerialNumber", v.disk_serial);
    } else {
        // Not a class this hook answers - Win32_Process, Win32_Service,
        // Win32_Processor (CPUID, deliberately unfaked) and the rest. Counting
        // these as write failures used to push the fallback threshold over on
        // ordinary enumeration traffic and flip the whole hook into Get mode.
        ++g_put_unmatched;
        return;
    }

    if (attempted == 0) {
        // Target class with no fake value to write. Also not a failure.
        ++g_put_absent;
        if (config().verbose) {
            note("WMI vtbl: %ls matched but every value was empty (%d)",
                               cls.c_str(), absent);
        }
        return;
    }

    if (refused == 0) {
        ++g_put_ok;
    } else if (written == 0) {
        // Whole object refused; the failure counters above already recorded it.
    } else {
        // Partially written: at least one property keeps the real value while
        // another reports the fake. This used to be counted as plain success
        // (`ok = put(a) || put(b)`), which hid exactly the leak the anticheat
        // would compare against.
        ++g_put_partial;
        note("WMI vtbl: %ls partially spoofed (%d of %d written, %d refused)",
                           cls.c_str(), written, attempted, refused);
    }

    if (refused > 0) {
        // Switch on the FIRST refusal, not the third.
        //
        // patch_instance runs inside Next()/GetObject(), before the caller has
        // been handed the object and therefore before it can call Get. Hooking
        // the table here means the very property that was just refused is still
        // answered by the Get hook - including the first object of the session.
        // Waiting for three refusals let those first objects through with the
        // real value, which is exactly the kind of "works sometimes" behaviour
        // that depends on where in the enumeration the proxy first shows up.
        const long fails = g_put_fail.load();
        if (!g_use_get_hook.exchange(true)) {
            note("WMI vtbl: Put refused (%ld); switching to Get interception", fails);
        }
        hook_object_vtable(obj);
    }

    // Report periodically rather than per call: this runs inside a COM call
    // the game is waiting on.
    const long total = g_put_ok.load() + g_put_fail.load() + g_put_unmatched.load() +
                       g_put_absent.load() + g_put_partial.load();
    if (total > 0 && total % 32 == 0) {
        note("WMI vtbl: put ok=%ld fail=%ld partial=%ld unmatched=%ld absent=%ld",
                           g_put_ok.load(), g_put_fail.load(), g_put_partial.load(),
                           g_put_unmatched.load(), g_put_absent.load());
    }
}

bool hook_enum_vtable(IEnumWbemClassObject* en) {
    if (!en) return false;
    void** vtbl = *reinterpret_cast<void***>(en);
    if (!vtbl) return false;

    if (g_enum_next.lookup(vtbl)) return false;

    std::lock_guard<std::mutex> lk(g_hook_mtx);
    if (g_enum_next.lookup(vtbl)) return false;
    // No pre-emptive "hooked" flag: the previous version set it with a CAS and
    // then patched, so a failed patch left the flag set and every later
    // enumerator was silently skipped.
    return hook_slot_with_table(vtbl, kEnumNextIndex, &HookNext,
                                g_enum_next, "IEnumWbemClassObject::Next");
}

HRESULT STDMETHODCALLTYPE HookNext(IEnumWbemClassObject* self, long timeout, ULONG count,
                                   IWbemClassObject** objects, ULONG* returned) {
    g_hook_next.fetch_add(1, std::memory_order_relaxed);
    EnumNext_t real = self ? g_enum_next.lookup(*reinterpret_cast<void***>(self)) : nullptr;
    if (!real) real = g_enum_next.primary();
    if (!real) return E_FAIL;

    const HookScope scope;
    HRESULT hr = real(self, timeout, count, objects, returned);
    if (scope.outer && SUCCEEDED(hr) && objects && returned) {
        // Bounded per call. This loop runs on the caller's thread, inside the
        // call it is waiting on; an enumeration of thousands of instances must
        // not turn into thousands of nested COM calls before it returns. The
        // objects past the budget keep the real value for this batch only -
        // their table is already hooked, so later reads are still answered.
        const ULONG n = *returned < kMaxObjectsPerCall ? *returned : kMaxObjectsPerCall;
        for (ULONG i = 0; i < n; ++i) patch_instance(objects[i]);
    }
    return hr;
}

HRESULT STDMETHODCALLTYPE HookGetObject(IWbemServices* self, const BSTR path, long flags,
                                        IWbemContext* ctx, IWbemClassObject** object,
                                        IWbemCallResult** call_result) {
    g_hook_get_object.fetch_add(1, std::memory_order_relaxed);
    GetObject_t real = self ? g_services_get_object.lookup(*reinterpret_cast<void***>(self))
                            : nullptr;
    if (!real) real = g_services_get_object.primary();
    if (!real) return E_FAIL;

    const HookScope scope;
    HRESULT hr = real(self, path, flags, ctx, object, call_result);
    if (scope.outer && SUCCEEDED(hr) && object && *object) patch_instance(*object);
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
    g_hook_exec_query.fetch_add(1, std::memory_order_relaxed);
    ExecQuery_t real = self ? g_services_exec_query.lookup(*reinterpret_cast<void***>(self))
                            : nullptr;
    if (!real) real = g_services_exec_query.primary();
    if (!real) return E_FAIL;

    const HookScope scope;
    HRESULT hr = real(self, lang, query, flags, ctx, out);
    if (scope.outer && SUCCEEDED(hr) && out && *out) hook_enum_vtable(*out);
    return hr;
}

// ---- CFG -------------------------------------------------------------------
// Control Flow Guard rejects an indirect call to an address outside the CFG
// bitmap, and every call the anticheat makes through these tables is exactly
// such a call. When the mitigation is on, register each hook as a valid target
// instead of letting the first query crash the process.
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
        openzen::log::error("WMI vtbl: CFG is on and a hook (%p) could not be registered "
                            "as a call target", fn);
        return false;
    }
    note("WMI vtbl: hook %p registered as a CFG call target", fn);
    return true;
}

// ---- COM -------------------------------------------------------------------

using CoCreateInstance_t = HRESULT (STDAPICALLTYPE*)(REFCLSID, LPUNKNOWN, DWORD, REFIID, LPVOID*);
using CoInitializeEx_t = HRESULT (STDAPICALLTYPE*)(LPVOID, DWORD);
using CoUninitialize_t = void (STDAPICALLTYPE*)();

struct ComApis {
    CoCreateInstance_t create = nullptr;
    CoInitializeEx_t init = nullptr;
    CoUninitialize_t uninit = nullptr;
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
        note("WMI vtbl: neither combase nor ole32 mapped yet; deferring");
        return false;
    }
    apis.create = reinterpret_cast<CoCreateInstance_t>(GetProcAddress(host, "CoCreateInstance"));
    apis.init = reinterpret_cast<CoInitializeEx_t>(GetProcAddress(host, "CoInitializeEx"));
    apis.uninit = reinterpret_cast<CoUninitialize_t>(GetProcAddress(host, "CoUninitialize"));
    note("WMI vtbl: com host=%s base=%p create=%p init=%p uninit=%p",
                       host_name, (void*)host,
                       reinterpret_cast<void*>(apis.create),
                       reinterpret_cast<void*>(apis.init),
                       reinterpret_cast<void*>(apis.uninit));
    return apis.create && apis.init;
}

// RAII around CoInitializeEx/CoUninitialize.
//
// The previous code called CoInitializeEx and never balanced it, so every
// install pass (there are two) leaked an apartment reference for the life of
// the process. Anything that inspects the COM init count - a protector, a
// shutdown path, or the next component that tries to uninitialize - sees a
// count that never returns to zero. S_OK and S_FALSE both add a reference and
// must be released; RPC_E_CHANGED_MODE adds none and must not be.
class ComApartment {
public:
    ComApartment() {
        ComApis apis;
        if (!resolve_com(apis) || !apis.init || !apis.uninit) {
            hr_ = E_FAIL;
            return;
        }
        hr_ = apis.init(nullptr, COINIT_MULTITHREADED);
        uninit_ = apis.uninit;
        owned_ = SUCCEEDED(hr_);
        note("WMI vtbl: CoInitializeEx -> 0x%08lX (reference owned: %d)",
                           static_cast<unsigned long>(hr_), owned_ ? 1 : 0);
    }

    ~ComApartment() {
        if (owned_ && uninit_) {
            uninit_();
            note("WMI vtbl: CoUninitialize (reference released)");
        }
    }

    ComApartment(const ComApartment&) = delete;
    ComApartment& operator=(const ComApartment&) = delete;

    // RPC_E_CHANGED_MODE is usable: the process already lives in an apartment
    // (AWT uses COM) and CoCreateInstance still works from this thread.
    bool usable() const {
        return owned_ || hr_ == static_cast<HRESULT>(RPC_E_CHANGED_MODE);
    }
    HRESULT hr() const { return hr_; }

private:
    CoUninitialize_t uninit_ = nullptr;
    HRESULT hr_ = E_FAIL;
    bool owned_ = false;
};

struct ServicesPatch {
    bool get_object = false;
    bool exec_query = false;
    bool complete() const { return get_object && exec_query; }
};

// Reaches WBEM services once, purely to obtain a vtable to patch. The
// apartment is owned by the caller.
ServicesPatch hook_services_vtable() {
    ServicesPatch out;
    ComApis apis;
    if (!resolve_com(apis) || !apis.create) return out;

    note("WMI vtbl: calling CoCreateInstance(CLSID_WbemLocator)");
    IWbemLocator* locator = nullptr;
    HRESULT hr = apis.create(CLSID_WbemLocator, nullptr, CLSCTX_INPROC_SERVER, IID_IWbemLocator,
                             reinterpret_cast<void**>(&locator));
    note("WMI vtbl: CoCreateInstance(WbemLocator) -> 0x%08lX locator=%p",
                       static_cast<unsigned long>(hr), (void*)locator);
    if (FAILED(hr) || !locator) return out;

    note("WMI vtbl: calling ConnectServer(ROOT\\CIMV2)");
    BSTR ns = SysAllocString(L"ROOT\\CIMV2");
    if (!ns) {
        note_alloc_failure("ConnectServer namespace");
        locator->Release();
        return out;
    }
    IWbemServices* services = nullptr;
    hr = locator->ConnectServer(ns, nullptr, nullptr, nullptr, 0, nullptr, nullptr, &services);
    SysFreeString(ns);
    note("WMI vtbl: ConnectServer -> 0x%08lX services=%p",
                       static_cast<unsigned long>(hr), (void*)services);
    if (FAILED(hr) || !services) {
        locator->Release();
        return out;
    }

    void** vtbl = *reinterpret_cast<void***>(services);
    note("WMI vtbl: services vtable=%p (slot0=%p)", (void*)vtbl,
                       vtbl ? vtbl[0] : nullptr);

    {
        // The lock covers only the two publishes and slot writes - no COM call
        // is made while it is held, so a hook running on another thread can
        // never deadlock against the install.
        std::lock_guard<std::mutex> lk(g_hook_mtx);
        out.get_object = hook_slot_with_table(vtbl, kGetObjectIndex, &HookGetObject,
                                              g_services_get_object, "IWbemServices::GetObject");
        out.exec_query = hook_slot_with_table(vtbl, kExecQueryIndex, &HookExecQuery,
                                              g_services_exec_query, "IWbemServices::ExecQuery");
    }

    services->Release();
    locator->Release();

    note("WMI vtbl: IWbemServices coverage GetObject=%d ExecQuery=%d",
                       out.get_object ? 1 : 0, out.exec_query ? 1 : 0);
    return out;
}

// ---- in-process self check ------------------------------------------------

using CoSetProxyBlanket_t = HRESULT (STDAPICALLTYPE*)(
    IUnknown*, DWORD, DWORD, LPOLESTR, DWORD, DWORD, void*, DWORD);

// The self check creates its own IWbemServices, and on a freshly-created
// apartment that proxy has no credentials: every ExecQuery comes back
// WBEM_E_ACCESS_DENIED (0x80041003). The anticheat does not hit this because it
// sets up its own COM security; ours has to do the same or the check is blind.
void apply_proxy_blanket(IWbemServices* svc) {
    HMODULE host = GetModuleHandleA("combase.dll");
    if (!host) host = GetModuleHandleA("ole32.dll");
    if (!host) return;
    auto fn = reinterpret_cast<CoSetProxyBlanket_t>(
        GetProcAddress(host, "CoSetProxyBlanket"));
    if (!fn) return;
    HRESULT hr = fn(reinterpret_cast<IUnknown*>(svc), RPC_C_AUTHN_WINNT, RPC_C_AUTHZ_NONE,
                    nullptr, RPC_C_AUTHN_LEVEL_CALL, RPC_C_IMP_LEVEL_IMPERSONATE,
                    nullptr, EOAC_NONE);
    note("WMI selfcheck: CoSetProxyBlanket -> 0x%08lX",
                       static_cast<unsigned long>(hr));
}

std::wstring query_one(IWbemServices* svc, const wchar_t* cls, const wchar_t* prop) {
    std::wstring got;
    if (!svc) return got;

    BSTR lang = SysAllocString(L"WQL");
    std::wstring q = std::wstring(L"SELECT ") + prop + L" FROM " + cls;
    BSTR query = SysAllocString(q.c_str());
    if (!lang || !query) {
        note_alloc_failure("self check query");
        SysFreeString(query);
        SysFreeString(lang);
        return got;
    }

    IEnumWbemClassObject* en = nullptr;
    // (language, query) - see the note on HookExecQuery.
    HRESULT hr = svc->ExecQuery(lang, query, 0, nullptr, &en);
    if (FAILED(hr)) {
        note("WMI selfcheck: ExecQuery(%ls) failed hr=0x%08lX",
                           cls, static_cast<unsigned long>(hr));
    } else if (en) {
        IWbemClassObject* obj = nullptr;
        ULONG n = 0;
        HRESULT nhr = en->Next(2000, 1, &obj, &n);
        if (FAILED(nhr) || n != 1 || !obj) {
            note("WMI selfcheck: Next(%ls) hr=0x%08lX n=%lu",
                               cls, static_cast<unsigned long>(nhr), n);
        } else {
            VARIANT v;
            VariantInit(&v);
            CIMTYPE t = 0;
            long f = 0;
            HRESULT ghr = obj->Get(prop, 0, &v, &t, &f);
            if (SUCCEEDED(ghr) && V_VT(&v) == VT_BSTR && V_BSTR(&v)) {
                got.assign(V_BSTR(&v), SysStringLen(V_BSTR(&v)));
            } else {
                note("WMI selfcheck: Get(%ls.%ls) hr=0x%08lX vt=%u",
                                   cls, prop, static_cast<unsigned long>(ghr),
                                   static_cast<unsigned>(V_VT(&v)));
            }
            VariantClear(&v);
            obj->Release();
        }
        en->Release();
    } else {
        note("WMI selfcheck: ExecQuery(%ls) returned a null enumerator", cls);
    }

    SysFreeString(query);
    SysFreeString(lang);
    return got;
}

enum class PropCheck { Unknown, Match, Mismatch };

struct SelfCheck {
    PropCheck uuid = PropCheck::Unknown;
    PropCheck disk = PropCheck::Unknown;
    PropCheck board = PropCheck::Unknown;

    int checked() const {
        int n = 0;
        for (PropCheck c : {uuid, disk, board}) {
            if (c != PropCheck::Unknown) ++n;
        }
        return n;
    }
    int matched() const {
        int n = 0;
        for (PropCheck c : {uuid, disk, board}) {
            if (c == PropCheck::Match) ++n;
        }
        return n;
    }
};

PropCheck compare_value(const std::wstring& got, const std::wstring& want) {
    if (got.empty() || want.empty()) return PropCheck::Unknown;
    return _wcsicmp(got.c_str(), want.c_str()) == 0 ? PropCheck::Match : PropCheck::Mismatch;
}

const char* verdict_name(PropCheck c) {
    switch (c) {
        case PropCheck::Match: return "OK";
        case PropCheck::Mismatch: return "MISMATCH";
        default: return "UNKNOWN";
    }
}

// Read the spoofed properties back through the same COM path the anticheat
// uses, right after the vtable is patched. This is the only meaningful
// "did it work" signal: another process reading WMI is unaffected by design,
// and the F3 debug screen shows Java-level data (os.name, OpenGL, MXBean)
// that never goes near WMI.
//
// Unlike the previous version this returns a verdict; the caller folds it into
// the install status instead of logging it and reporting success regardless.
SelfCheck self_check_in_process() {
    SelfCheck out;
    ComApis apis;
    if (!resolve_com(apis) || !apis.create) return out;

    IWbemLocator* loc = nullptr;
    if (FAILED(apis.create(CLSID_WbemLocator, nullptr, CLSCTX_INPROC_SERVER,
                           IID_IWbemLocator, reinterpret_cast<void**>(&loc))) || !loc) {
        return out;
    }

    BSTR ns = SysAllocString(L"ROOT\\CIMV2");
    IWbemServices* svc = nullptr;
    HRESULT hr = ns ? loc->ConnectServer(ns, nullptr, nullptr, nullptr, 0, nullptr, nullptr, &svc)
                    : E_OUTOFMEMORY;
    SysFreeString(ns);
    loc->Release();
    if (FAILED(hr) || !svc) return out;

    apply_proxy_blanket(svc);

    const SpoofValues& v = values();
    out.uuid = compare_value(query_one(svc, L"Win32_ComputerSystemProduct", L"UUID"),
                             v.product_uuid);
    out.disk = compare_value(query_one(svc, L"Win32_DiskDrive", L"SerialNumber"),
                             v.disk_serial);
    out.board = compare_value(query_one(svc, L"Win32_BaseBoard", L"SerialNumber"),
                              v.baseboard_serial);
    svc->Release();

    note("WMI selfcheck UUID  = %s", verdict_name(out.uuid));
    note("WMI selfcheck Disk  = %s", verdict_name(out.disk));
    note("WMI selfcheck Board = %s", verdict_name(out.board));
    note("WMI selfcheck summary matched %d of %d checked; tables: object=%d enum=%d",
                       out.matched(), out.checked(),
                       g_object_get.size(), g_enum_next.size());
    return out;
}

} // namespace

// The install path, with no fault handling of its own: the guard below runs it
// inside __try so any access violation while probing COM is caught.
static int install_wmi_vtbl_inner() {
    // Register the CFG targets before any slot can point at them. HookObjGet is
    // included on purpose: the anticheat calls IWbemClassObject::Get through
    // its own code, so that call is CFG-checked too, and the previous list
    // omitted it - armed on a CFG process, the first property read faulted.
    note("WMI vtbl: step 1/4 CFG targets");
    if (!register_cfg_target(reinterpret_cast<void*>(&HookGetObject))) return kWmiVtblUnavailable;
    if (!register_cfg_target(reinterpret_cast<void*>(&HookExecQuery))) return kWmiVtblUnavailable;
    if (!register_cfg_target(reinterpret_cast<void*>(&HookNext))) return kWmiVtblUnavailable;
    if (!register_cfg_target(reinterpret_cast<void*>(&HookObjGet))) return kWmiVtblUnavailable;

    // Every relay the hooks will need, built now. From here on the hook path
    // only reads this table: no cave carving, no guard lock, no logging.
    openzen::log::info("WMI vtbl: step 1b/4 relay table");
    prepare_relay(reinterpret_cast<void*>(&HookGetObject));
    prepare_relay(reinterpret_cast<void*>(&HookExecQuery));
    prepare_relay(reinterpret_cast<void*>(&HookNext));
    prepare_relay(reinterpret_cast<void*>(&HookObjGet));

    // The apartment is owned for exactly as long as this function runs, and
    // released even on the early-return paths below.
    note("WMI vtbl: step 2/4 COM apartment");
    ComApartment com;
    if (!com.usable()) {
        openzen::log::error("WMI vtbl: COM apartment unavailable (hr=0x%08lX); deferring",
                            static_cast<unsigned long>(com.hr()));
        return kWmiVtblUnavailable;
    }

    note("WMI vtbl: step 3/4 IWbemServices vtable");
    const ServicesPatch patch = hook_services_vtable();
    g_patch_live.store(patch.get_object || patch.exec_query);
    g_services_complete.store(patch.complete());

    if (!patch.get_object && !patch.exec_query) {
        openzen::log::error("WMI vtbl: no IWbemServices slot could be patched");
        return kWmiVtblUnavailable;
    }

    // Prove it on the spot: query the three properties back through the same
    // COM path the anticheat uses. The result decides the status below.
    note("WMI vtbl: step 4/4 self check");
    const SelfCheck check = self_check_in_process();

    const bool vetoed = check.checked() > 0 && check.matched() == 0;
    const bool verified = check.checked() > 0 && check.matched() == check.checked();
    g_vetoed.store(vetoed);

    if (!patch.complete()) {
        openzen::log::error("WMI vtbl: partial install - GetObject=%d ExecQuery=%d",
                            patch.get_object ? 1 : 0, patch.exec_query ? 1 : 0);
        return kWmiVtblPartial;
    }
    if (vetoed) {
        openzen::log::error("WMI vtbl: self check saw the real value on every property; "
                            "the patch is patched but ineffective, reporting partial");
        return kWmiVtblPartial;
    }
    if (!verified) {
        note("WMI vtbl: self check could not confirm every property "
                           "(%d of %d); reporting partial rather than full",
                           check.matched(), check.checked());
        return kWmiVtblPartial;
    }

    note("WMI vtbl: active (every slot patched, self check confirmed)");
    return kWmiVtblFull;
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
// no C++ locals that need unwinding (C2712) - hence the split from
// install_wmi_vtbl(), which holds the lock.
static int install_wmi_vtbl_guarded() {
    DWORD fault = 0;
    int rc = kWmiVtblUnavailable;
    __try {
        rc = install_wmi_vtbl_inner();
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        fault = GetExceptionCode();
    }

    if (fault) {
        openzen::log::error("WMI vtbl: fault 0x%08lX during install; spoof disabled, "
                            "process left running", fault);
        return kWmiVtblUnavailable;
    }
    return rc;
}

int install_wmi_vtbl(bool quiet) {
    std::lock_guard<std::mutex> lk(g_mtx);
    // Only a full install is cached. Anything less can be retried, but only up
    // to kMaxInstallAttempts in total: a failure here means COM or the WBEM
    // provider is not ready, and hammering it is how a probe turns into a crash.
    if (g_status.load() == kWmiVtblFull) return g_status.load();

    const int attempts = g_install_attempts.load();
    if (attempts >= kMaxInstallAttempts) {
        note("WMI vtbl: attempt cap reached (%d); reporting the last result %d",
             attempts, g_status.load());
        return g_status.load();
    }
    g_install_attempts.store(attempts + 1);

    struct QuietScope {
        bool previous;
        explicit QuietScope(bool q) : previous(g_quiet.exchange(q)) {}
        ~QuietScope() { g_quiet.store(previous); }
    } scope(quiet);

    const int rc = install_wmi_vtbl_guarded();
    g_status.store(rc);
    return rc;
}

bool wmi_vtbl_active() {
    // A half-covered or self-check-vetoed patch reports inactive on purpose:
    // the caller uses this to decide whether to arm a fallback surface, and a
    // partial patch is exactly the case that needs one.
    return g_patch_live.load() && g_services_complete.load() && !g_vetoed.load();
}

bool wmi_vtbl_verified() { return g_status.load() == kWmiVtblFull; }

WmiVtblStats wmi_vtbl_stats() {
    WmiVtblStats s;
    s.get_object = g_hook_get_object.load(std::memory_order_relaxed);
    s.exec_query = g_hook_exec_query.load(std::memory_order_relaxed);
    s.next = g_hook_next.load(std::memory_order_relaxed);
    s.object_get = g_hook_object_get.load(std::memory_order_relaxed);
    return s;
}

} // namespace hwid
