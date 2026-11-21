#include "jni_probe.h"

#include <windows.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <cwchar>
#include <mutex>
#include <string>

#include "openzen.h"
#include "hwid_wmi_vtbl.h"
#include "nt_guard.h"

namespace jnipro {

namespace {

// JNINativeInterface_ is a flat table of function pointers, so its size divided
// by the pointer size is exactly the number of entries a JNIEnv exposes. Taken
// from the header rather than hardcoded: a JDK that appends entries is covered
// without touching this file.
constexpr int kEnvSlots = static_cast<int>(sizeof(JNINativeInterface_) / sizeof(void*));
constexpr int kVmSlots  = static_cast<int>(sizeof(JNIInvokeInterface_) / sizeof(void*));
constexpr int kMaxEnvSlots = 256;
constexpr int kMaxVmSlots = 16;
constexpr unsigned kWatchIntervalMs = 2000;

static_assert(kEnvSlots <= kMaxEnvSlots, "grow kMaxEnvSlots");
static_assert(kVmSlots <= kMaxVmSlots, "grow kMaxVmSlots");

std::mutex g_mtx;
void** g_env_table = nullptr;
void** g_vm_table = nullptr;
bool   g_captured = false;
bool   g_snapshot_seeded = false;

void*  g_env_snapshot[kMaxEnvSlots] = {0};
void*  g_vm_snapshot[kMaxVmSlots] = {0};
void*  g_env_reported[kMaxEnvSlots] = {0};
void*  g_vm_reported[kMaxVmSlots] = {0};

std::atomic<int>  g_last_foreign{-1};
std::atomic<bool> g_watch_started{false};

const char* vm_slot_name(int i) {
    switch (i) {
        case 3: return "DestroyJavaVM";
        case 4: return "AttachCurrentThread";
        case 5: return "DetachCurrentThread";
        case 6: return "GetEnv";
        case 7: return "AttachCurrentThreadAsDaemon";
        default: return "reserved";
    }
}

size_t page_size() {
    static const size_t kPage = [] {
        SYSTEM_INFO si{};
        GetSystemInfo(&si);
        return si.dwPageSize ? static_cast<size_t>(si.dwPageSize) : 0x1000u;
    }();
    return kPage;
}

// Every read below touches a short prologue at an address that came out of a
// function table. A slot can legitimately hold a pointer into torn-down memory
// (protection software removing its own trampoline), so nothing is dereferenced
// before the page is confirmed committed, unguarded, and long enough for the
// read to stay inside it.
bool readable(const void* p, size_t n) {
    if (!p) return false;
    MEMORY_BASIC_INFORMATION mbi{};
    if (VirtualQuery(p, &mbi, sizeof mbi) == 0) return false;
    if (mbi.State != MEM_COMMIT) return false;
    if (mbi.Protect & (PAGE_NOACCESS | PAGE_GUARD)) return false;

    const uintptr_t start = reinterpret_cast<uintptr_t>(p);
    const uintptr_t page = page_size();
    const uintptr_t page_end = (start & ~(page - 1)) + page;
    return start + n <= page_end;
}

struct Jump {
    bool is_jump = false;
    void* target = nullptr;
};

// The trampoline shapes a hooking library generates at an entry point: rel32
// jmp, short jmp, rip-relative indirect jmp, and the asmjit favourite
// `mov rax, imm64; jmp rax`.
Jump decode_jump(const void* fn) {
    Jump j;
    if (!readable(fn, 16)) return j;
    const BYTE* p = static_cast<const BYTE*>(fn);

    if (p[0] == 0xE9) {
        int32_t rel = 0;
        std::memcpy(&rel, p + 1, 4);
        j.is_jump = true;
        j.target = const_cast<BYTE*>(p + 5 + rel);
        return j;
    }
    if (p[0] == 0xEB) {
        const int8_t rel = static_cast<int8_t>(p[1]);
        j.is_jump = true;
        j.target = const_cast<BYTE*>(p + 2 + rel);
        return j;
    }
    if (p[0] == 0xFF && p[1] == 0x25) {
        int32_t rel = 0;
        std::memcpy(&rel, p + 2, 4);
        const void* const* slot = reinterpret_cast<const void* const*>(p + 6 + rel);
        if (readable(slot, sizeof(void*))) {
            j.is_jump = true;
            j.target = const_cast<void*>(*slot);
        }
        return j;
    }
    if (p[0] == 0x48 && p[1] == 0xB8) {
        void* imm = nullptr;
        std::memcpy(&imm, p + 2, 8);
        if (p[10] == 0xFF && p[11] == 0xE0) {          // jmp rax
            j.is_jump = true;
            j.target = imm;
        } else if (p[10] == 0xFF && p[11] == 0x20) {   // jmp qword ptr [rax]
            if (readable(imm, sizeof(void*))) {
                j.is_jump = true;
                j.target = *static_cast<void* const*>(imm);
            }
        }
        return j;
    }
    return j;
}

// Basename of the image that owns `addr`, or empty when the address belongs to
// no image at all - the interesting case: JIT'd code, a private VirtualAlloc, a
// detour buffer.
std::wstring image_of(const void* addr) {
    if (!addr) return std::wstring();
    HMODULE mod = nullptr;
    if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                                GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                            reinterpret_cast<LPCWSTR>(addr), &mod) ||
        !mod) {
        return std::wstring();
    }
    wchar_t path[MAX_PATH] = {0};
    if (GetModuleFileNameW(mod, path, MAX_PATH) == 0) return std::wstring();
    const wchar_t* base = std::wcsrchr(path, L'\\');
    return base ? std::wstring(base + 1) : std::wstring(path);
}

void copy_image(wchar_t* dst, size_t cap, const std::wstring& src) {
    if (!dst || cap == 0) return;
    const size_t n = src.size() < cap - 1 ? src.size() : cap - 1;
    std::wmemcpy(dst, src.c_str(), n);
    dst[n] = L'\0';
}

struct TableView {
    const char* name;
    void** table;
    int count;
    void** snapshot;
    void** reported;
};

void inspect_table(const TableView& tv, Scan* out) {
    for (int i = 0; i < tv.count; ++i) {
        void* entry = tv.table[i];
        if (!entry) continue;

        if (!g_snapshot_seeded) {
            tv.snapshot[i] = entry;
        } else if (tv.snapshot[i] != entry) {
            // A change is rare and always worth a line; the dedupe array below
            // belongs to the foreign-entry report and is not shared with it.
            ++out->changed;
            openzen::log::info("JNI probe: %s[%d] entry changed %p -> %p",
                               tv.name, i, tv.snapshot[i], entry);
            tv.snapshot[i] = entry;
        }

        const Jump j = decode_jump(entry);
        void* target = j.is_jump ? j.target : entry;
        const std::wstring img = image_of(target);
        if (_wcsicmp(img.c_str(), L"jvm.dll") == 0) continue;

        ++out->foreign;
        if (tv.reported[i] == target) continue;   // this shape is already logged
        tv.reported[i] = target;

        if (out->hit_count < static_cast<int>(sizeof(out->hits) / sizeof(out->hits[0]))) {
            SlotHit& h = out->hits[out->hit_count++];
            h.table = tv.name;
            h.index = i;
            h.entry = entry;
            h.target = target;
            copy_image(h.image, sizeof(h.image) / sizeof(h.image[0]), img);
        }

        char label[64];
        if (std::strcmp(tv.name, "JavaVM") == 0) {
            std::snprintf(label, sizeof label, "JavaVM.%s[%d]", vm_slot_name(i), i);
        } else {
            std::snprintf(label, sizeof label, "JNIEnv[%d]", i);
        }
        openzen::log::info("JNI probe: %s entry=%p -> %p (%ls)%s",
                           label, entry, target,
                           img.empty() ? L"<no image>" : img.c_str(),
                           j.is_jump ? "" : " [not a stub: entry itself]");
    }
}

DWORD WINAPI watch_thread(LPVOID) {
    ULONGLONG last_wmi_report = GetTickCount64();
    for (;;) {
        Sleep(kWatchIntervalMs);

        // Heartbeat: proves this process is still running our code and says
        // whether the anticheat is actually calling into the WMI hooks. Without
        // it, a frozen client and an idle one produce the same log.
        const ULONGLONG now = GetTickCount64();
        if (now - last_wmi_report >= 15000) {
            last_wmi_report = now;
            const hwid::WmiVtblStats ws = hwid::wmi_vtbl_stats();
            openzen::log::info("WMI vtbl: alive, hook entries exec=%ld get_obj=%ld next=%ld get=%ld",
                               ws.exec_query, ws.get_object, ws.next, ws.object_get);
        }

        const Scan s = scan(nullptr);
        // Speak up only when the count moves: a hook that stays put is already
        // in the log, and this thread runs for the whole session.
        if (s.foreign != g_last_foreign.load()) {
            g_last_foreign.store(s.foreign);
            openzen::log::info("JNI probe: %d foreign entries now (vm=%d env=%d slots)",
                               s.foreign, s.vm_slots, s.env_slots);
        }
    }
    return 0;
}

} // namespace

Scan scan(JavaVM* vm) {
    Scan out;

    void** env_table = nullptr;
    void** vm_table = nullptr;
    bool seeded = false;
    {
        std::lock_guard<std::mutex> lk(g_mtx);
        env_table = g_env_table;
        vm_table = g_vm_table;
        seeded = g_snapshot_seeded;
    }

    // Allow a self-contained pass before install() has run.
    if ((!env_table || !vm_table) && vm) {
        JNIEnv* env = nullptr;
        if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) == JNI_OK && env) {
            env_table = *reinterpret_cast<void***>(env);
            vm_table = *reinterpret_cast<void***>(vm);
            seeded = false;
        }
    }
    if (!env_table || !vm_table) return out;

    out.vm_slots = kVmSlots;
    out.env_slots = kEnvSlots;

    const std::lock_guard<std::mutex> lk(g_mtx);
    TableView vm_view{"JavaVM", vm_table, kVmSlots, g_vm_snapshot, g_vm_reported};
    TableView env_view{"JNIEnv", env_table, kEnvSlots, g_env_snapshot, g_env_reported};

    inspect_table(vm_view, &out);
    inspect_table(env_view, &out);

    g_snapshot_seeded = true;
    return out;
}

bool install(JavaVM* vm) {
    if (!vm) return false;

    {
        std::lock_guard<std::mutex> lk(g_mtx);
        if (g_captured) return true;
    }

    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK || !env) {
        // Not attached here: the caller attaches first and retries.
        openzen::log::info("JNI probe: no JNIEnv on this thread; not installed");
        return false;
    }

    void** vm_table = *reinterpret_cast<void***>(vm);
    void** env_table = *reinterpret_cast<void***>(env);
    if (!vm_table || !env_table) return false;

    {
        std::lock_guard<std::mutex> lk(g_mtx);
        g_vm_table = vm_table;
        g_env_table = env_table;
        g_captured = true;
        g_snapshot_seeded = false;
    }

    openzen::log::info("JNI probe: tables captured (JavaVM=%p JNIEnv=%p, %d + %d slots)",
                       static_cast<void*>(vm_table), static_cast<void*>(env_table),
                       kVmSlots, kEnvSlots);

    // The first pass also seeds the snapshot, so anything reported here was
    // already in place before this DLL looked - i.e. planted by someone else.
    // scan() takes the lock itself, so nothing is held across this call.
    const Scan first = scan(vm);
    g_last_foreign.store(first.foreign);

    if (first.foreign == 0) {
        openzen::log::info("JNI probe: every entry resolves into jvm.dll - "
                           "no table-level interception visible right now");
    } else {
        openzen::log::info("JNI probe: %d entr%s resolve outside jvm.dll",
                           first.foreign, first.foreign == 1 ? "y" : "ies");
        for (int i = 0; i < first.hit_count; ++i) {
            const SlotHit& h = first.hits[i];
            openzen::log::info("JNI probe:   %s[%d] entry=%p target=%p image=%ls",
                               h.table, h.index, h.entry, h.target,
                               h.image[0] ? h.image : L"<no image>");
        }
    }

    if (!g_watch_started.exchange(true)) {
        // Hidden entry: this thread lives for the whole session and would
        // otherwise answer "which module started you?" with this DLL.
        HANDLE t = ntguard::spawn_hidden(watch_thread, nullptr);
        if (t) {
            CloseHandle(t);
        } else {
            g_watch_started.store(false);
            openzen::log::error("JNI probe: watchdog thread could not be created");
        }
    }
    return true;
}

} // namespace jnipro
