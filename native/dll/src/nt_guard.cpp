#include "nt_guard.h"

#include <windows.h>
#include <winternl.h>

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <mutex>

#include "inline_hook.h"
#include "openzen.h"

namespace ntguard {

namespace {

constexpr size_t kPatchSize   = 5;    // E9 rel32 first stage
constexpr size_t kRelaySize   = 16;   // FF25 + imm64 (+ padding)
constexpr size_t kThunkSize   = 14;   // FF25 + imm64 (jmp back)
constexpr size_t kMaxDisplace = 32;
constexpr int    kMaxHooks    = 16;
constexpr int    kMaxCaves    = 8;
constexpr int    kMaxHidden   = 16;

struct HookRec {
    BYTE* target  = nullptr;
    BYTE* relay   = nullptr;   // inside ntdll
    void* detour  = nullptr;
    BYTE* tramp   = nullptr;   // inside ntdll, or private when the cave is spent
    bool  tramp_private = false;
    int   displaced = 0;
    BYTE  saved[kMaxDisplace] = {0};
    bool  active  = false;
};

HookRec      g_hooks[kMaxHooks];
std::mutex   g_mtx;

struct Cave {
    BYTE*  base = nullptr;
    size_t size = 0;
    size_t used = 0;
};
Cave g_caves[kMaxCaves];

// Ranges that must not be visible to a VirtualQuery sweep. Fixed capacity and a
// release-store counter: the detour below reads this on every query, and taking
// a lock from inside an arbitrary thread's NtQueryVirtualMemory is a deadlock
// waiting for the one caller that already holds it.
struct Range {
    uintptr_t base;
    size_t    size;
};
Range            g_hidden[kMaxHidden];
std::atomic<int> g_hidden_count{0};

std::atomic<long> g_rearms{0};
std::atomic<long> g_relay_reseats{0};

std::mutex        g_watch_mtx;
HANDLE            g_watch = nullptr;
std::atomic<bool> g_watch_run{false};
unsigned          g_interval = 40;

// ---------------------------------------------------------------------------
// Protection helper.
//
// ntdll's .text is PAGE_EXECUTE_READ. Leaving it RWX after a patch is itself a
// signature for a page-permission sweep, so every write window is closed again
// on scope exit.
// ---------------------------------------------------------------------------
class CodeWriter {
public:
    CodeWriter(void* addr, size_t len)
        : addr_(static_cast<BYTE*>(addr)), len_(len), old_(0), ok_(false) {
        ok_ = VirtualProtect(addr_, len_, PAGE_EXECUTE_READWRITE, &old_) != FALSE;
    }
    ~CodeWriter() {
        DWORD ignored = 0;
        VirtualProtect(addr_, len_, old_ ? old_ : PAGE_EXECUTE_READ, &ignored);
        FlushInstructionCache(GetCurrentProcess(), addr_, len_);
    }
    bool ok() const { return ok_; }
    CodeWriter(const CodeWriter&) = delete;
    CodeWriter& operator=(const CodeWriter&) = delete;

private:
    BYTE*  addr_;
    size_t len_;
    DWORD  old_;
    bool   ok_;
};

// ---------------------------------------------------------------------------
// ntdll image bounds
// ---------------------------------------------------------------------------
BYTE*  g_nt_base = nullptr;
size_t g_nt_size = 0;

bool image_bounds(BYTE** base, size_t* size) {
    if (!g_nt_base) g_nt_base = reinterpret_cast<BYTE*>(GetModuleHandleA("ntdll.dll"));
    if (!g_nt_base) return false;
    if (!g_nt_size) {
        auto* dos = reinterpret_cast<IMAGE_DOS_HEADER*>(g_nt_base);
        if (dos->e_magic != IMAGE_DOS_SIGNATURE) return false;
        auto* nt = reinterpret_cast<IMAGE_NT_HEADERS*>(g_nt_base + dos->e_lfanew);
        if (nt->Signature != IMAGE_NT_SIGNATURE) return false;
        g_nt_size = nt->OptionalHeader.SizeOfImage;
    }
    *base = g_nt_base;
    *size = g_nt_size;
    return true;
}

bool is_pad(BYTE b) {
    return b == 0x00 || b == 0xCC || b == 0x90;
}

bool overlaps_cave(const BYTE* p, size_t n) {
    for (const Cave& c : g_caves) {
        if (!c.base) continue;
        if (p < c.base + c.size && c.base < p + n) return true;
    }
    return false;
}

// ---------------------------------------------------------------------------
// Cave discovery.
//
// A run of 0x00/0xCC/0x90 inside an executable ntdll section is alignment
// filler: the compiler emits it between functions and after the last one in a
// section. Overwriting it cannot corrupt a neighbour, which is why the padding
// test is deliberately narrow - a single non-pad byte ends the candidate.
//
// Measured on a stock Windows 11 ntdll: .text holds 1224 runs of >=16 padding
// bytes and one unbroken 2309-byte run (the section tail), so the relay pool is
// never the limiting factor.
//
// Section choice is not "any executable section". ntdll also marks fothk (the
// forwarder thunk table), SCPCFG* and RT as executable, and those hold CFG data
// and thunks that the loader and the CFG check themselves read. Writing a relay
// into one of them is a crash that only shows up under control-flow-guard
// checks. Pass 0 therefore looks only at .text; other executable sections are
// considered only if .text has nothing, and fothk is never used.
// ---------------------------------------------------------------------------
bool is_text_section(const IMAGE_SECTION_HEADER& s) {
    return std::memcmp(s.Name, ".text", 5) == 0;
}

bool is_forbidden_section(const IMAGE_SECTION_HEADER& s) {
    return std::memcmp(s.Name, "fothk", 5) == 0;
}

// The candidate must be committed, executable, and entirely inside one region:
// a run can otherwise straddle the section tail into a page that was never
// mapped, and the first write there faults.
bool mapped_exec(const BYTE* p, size_t n) {
    MEMORY_BASIC_INFORMATION mbi{};
    if (VirtualQuery(p, &mbi, sizeof(mbi)) != sizeof(mbi)) return false;
    if (mbi.State != MEM_COMMIT) return false;
    const DWORD prot = mbi.Protect & 0xFF;
    if (prot != PAGE_EXECUTE && prot != PAGE_EXECUTE_READ &&
        prot != PAGE_EXECUTE_READWRITE && prot != PAGE_EXECUTE_WRITECOPY) {
        return false;
    }
    const BYTE* end = static_cast<const BYTE*>(mbi.BaseAddress) + mbi.RegionSize;
    return end >= p + n;
}

BYTE* find_cave(size_t want, size_t* got) {
    BYTE* base = nullptr;
    size_t size = 0;
    if (!image_bounds(&base, &size)) return nullptr;

    auto* dos = reinterpret_cast<IMAGE_DOS_HEADER*>(base);
    auto* nt  = reinterpret_cast<IMAGE_NT_HEADERS*>(base + dos->e_lfanew);
    auto* sec = IMAGE_FIRST_SECTION(nt);

    for (int pass = 0; pass < 2; ++pass) {
        BYTE*  best = nullptr;
        size_t best_len = 0;

        for (int i = 0; i < nt->FileHeader.NumberOfSections; ++i) {
            if (!(sec[i].Characteristics & IMAGE_SCN_MEM_EXECUTE)) continue;
            if (is_forbidden_section(sec[i])) continue;
            if (pass == 0 && !is_text_section(sec[i])) continue;

            BYTE*  s = base + sec[i].VirtualAddress;
            size_t n = sec[i].Misc.VirtualSize;
            if (n < sec[i].SizeOfRawData) n = sec[i].SizeOfRawData;
            if (sec[i].VirtualAddress + n > size) n = size - sec[i].VirtualAddress;
            if (!n) continue;

            size_t run = 0;
            for (size_t k = 0; k <= n; ++k) {
                const bool pad = (k < n) && is_pad(s[k]);
                if (pad) { ++run; continue; }
                if (run) {
                    if (run >= want) {
                        const size_t start   = k - run;
                        const size_t aligned = (start + 15) & ~static_cast<size_t>(15);
                        const size_t avail   = run - (aligned - start);
                        BYTE* cand = s + aligned;
                        if (avail >= want && avail > best_len &&
                            !overlaps_cave(cand, avail) && mapped_exec(cand, want)) {
                            best     = cand;
                            best_len = avail;
                        }
                    }
                    run = 0;
                }
            }
        }

        if (best) {
            *got = best_len;
            return best;
        }
    }
    return nullptr;
}

// Carve `want` bytes out of ntdll. Allocation is append-only inside a cave and
// the pool is never returned: the relay and the trampoline must stay resident
// for the life of the process, so there is nothing to give back.
BYTE* cave_alloc(size_t want) {
    want = (want + 15) & ~static_cast<size_t>(15);

    for (Cave& c : g_caves) {
        if (c.base && c.size >= c.used + want) {
            BYTE* p = c.base + c.used;
            c.used += want;
            return p;
        }
    }

    for (Cave& c : g_caves) {
        if (c.base) continue;
        size_t got = 0;
        BYTE* fresh = find_cave(want + 0x80, &got);
        if (!fresh) fresh = find_cave(want, &got);
        if (!fresh) continue;
        c.base = fresh;
        c.size = got;
        c.used = want;
        openzen::log::info("ntguard: ntdll cave %p (%zu bytes), carving %zu bytes",
                           static_cast<void*>(fresh), got, want);
        return fresh;
    }
    return nullptr;
}

bool fits_rel32(const void* from_next, const void* to) {
    const int64_t d = static_cast<int64_t>(reinterpret_cast<uintptr_t>(to))
                    - static_cast<int64_t>(reinterpret_cast<uintptr_t>(from_next));
    return d >= INT32_MIN && d <= INT32_MAX;
}

// Last-resort trampoline when no ntdll cave can reach the target: private
// memory near it, so the E9 back into the real function still fits. This path
// is visible to a VirtualQuery sweep, which is why it is only a fallback.
BYTE* alloc_near(void* hint, size_t size) {
    SYSTEM_INFO si{};
    GetSystemInfo(&si);
    const uintptr_t gran = si.dwAllocationGranularity ? si.dwAllocationGranularity : 0x10000;
    const uintptr_t base = reinterpret_cast<uintptr_t>(hint) & ~(gran - 1);

    for (uintptr_t step = gran; step < 0x40000000ull; step += gran) {
        void* p = VirtualAlloc(reinterpret_cast<void*>(base - step), size,
                               MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
        if (p) return static_cast<BYTE*>(p);
        p = VirtualAlloc(reinterpret_cast<void*>(base + step), size,
                         MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
        if (p) return static_cast<BYTE*>(p);
    }
    return nullptr;
}

// ---------------------------------------------------------------------------
// Payload writers
// ---------------------------------------------------------------------------

// FF 25 00 00 00 00   jmp qword ptr [rip+0]
// <detour, 8 bytes>
//
// The absolute form is used inside the relay because the detour can live
// anywhere; only the first stage has to stay inside rel32 range.
bool write_relay(BYTE* relay, void* detour) {
    BYTE buf[kRelaySize];
    std::memset(buf, 0xCC, sizeof(buf));
    buf[0] = 0xFF;
    buf[1] = 0x25;
    const int32_t zero = 0;
    std::memcpy(buf + 2, &zero, 4);
    std::memcpy(buf + 6, &detour, 8);

    // Checked: ntdll's padding is PAGE_EXECUTE_READ. If the page cannot be
    // opened (ACG/HVCI policy, or a cave that turned out to be protected image
    // memory) the write would fault the process instead of returning an error.
    // A failed relay must abort the hook: the first stage would otherwise point
    // at the 0xCC filler and raise int3 on the first call.
    CodeWriter w(relay, kRelaySize);
    if (!w.ok()) {
        openzen::log::error("ntguard: cannot open relay %p for writing (page is not writable)",
                            static_cast<void*>(relay));
        return false;
    }
    std::memcpy(relay, buf, kRelaySize);
    return true;
}

// Displaced prologue bytes + a jump back into the live function.
void write_trampoline(HookRec& h) {
    BYTE  buf[kMaxDisplace + kThunkSize];
    size_t n = static_cast<size_t>(h.displaced);
    std::memcpy(buf, h.saved, n);

    if (fits_rel32(h.tramp + n + kPatchSize, h.target + n)) {
        const int32_t back = static_cast<int32_t>(
            reinterpret_cast<uintptr_t>(h.target + n)
            - reinterpret_cast<uintptr_t>(h.tramp + n + kPatchSize));
        buf[n] = 0xE9;
        std::memcpy(buf + n + 1, &back, 4);
        n += kPatchSize;
    } else {
        void* cont = h.target + n;
        buf[n] = 0xFF;
        buf[n + 1] = 0x25;
        const int32_t zero = 0;
        std::memcpy(buf + n + 2, &zero, 4);
        std::memcpy(buf + n + 6, &cont, 8);
        n += kThunkSize;
    }

    CodeWriter w(h.tramp, n);
    if (!w.ok()) {
        openzen::log::error("ntguard: cannot write trampoline for %p",
                            static_cast<void*>(h.target));
        return;
    }
    std::memcpy(h.tramp, buf, n);
}

// The single hop the anticheat actually inspects: a 5-byte jmp whose
// destination resolves into ntdll.dll.
bool write_first_stage(HookRec& h) {
    BYTE buf[kMaxDisplace];
    std::memcpy(buf, h.saved, static_cast<size_t>(h.displaced));

    const int32_t rel = static_cast<int32_t>(
        reinterpret_cast<uintptr_t>(h.relay) - (reinterpret_cast<uintptr_t>(h.target) + kPatchSize));
    buf[0] = 0xE9;
    std::memcpy(buf + 1, &rel, 4);
    for (int i = static_cast<int>(kPatchSize); i < h.displaced; ++i) buf[i] = 0x90;

    CodeWriter w(h.target, static_cast<size_t>(h.displaced));
    if (!w.ok()) {
        openzen::log::error("ntguard: cannot open %p for writing (ACG/HVCI?)",
                            static_cast<void*>(h.target));
        return false;
    }
    std::memcpy(h.target, buf, static_cast<size_t>(h.displaced));
    return true;
}

bool first_stage_intact(const HookRec& h) {
    if (h.target[0] != 0xE9) return false;
    int32_t rel = 0;
    std::memcpy(&rel, h.target + 1, 4);
    if (h.target + kPatchSize + rel != h.relay) return false;
    for (int i = static_cast<int>(kPatchSize); i < h.displaced; ++i) {
        if (h.target[i] != 0x90) return false;
    }
    return true;
}

bool relay_intact(const HookRec& h) {
    if (h.relay[0] != 0xFF || h.relay[1] != 0x25) return false;
    void* target = nullptr;
    std::memcpy(&target, h.relay + 6, 8);
    return target == h.detour;
}

// ---------------------------------------------------------------------------
// Watchdog
// ---------------------------------------------------------------------------
DWORD WINAPI watchdog_main(LPVOID) {
    openzen::log::info("ntguard: watchdog up (interval %u ms, jittered)", g_interval);

    while (g_watch_run.load(std::memory_order_acquire)) {
        {
            std::lock_guard<std::mutex> lk(g_mtx);
            for (HookRec& h : g_hooks) {
                if (!h.active) continue;

                // Someone restored ntdll's padding (an integrity scan that
                // rewrites known-good bytes) - put the relay back first, or the
                // first stage would point at garbage.
                if (!relay_intact(h)) {
                    if (write_relay(h.relay, h.detour)) {
                        g_relay_reseats.fetch_add(1);
                        openzen::log::info("ntguard: relay %p was wiped -> re-seated",
                                           static_cast<void*>(h.relay));
                    }
                }

                // The common case: the anticheat verified the prologue and put
                // the original bytes back, or installed its own hook over ours.
                if (!first_stage_intact(h)) {
                    if (write_first_stage(h)) {
                        g_rearms.fetch_add(1);
                        openzen::log::info("ntguard: first stage of %p was restored/replaced "
                                           "-> re-written", static_cast<void*>(h.target));
                    }
                }
            }
        }

        // A perfectly periodic writer is itself a signature: the interval stays
        // in [interval, interval + interval/2) so the reseat cannot be predicted
        // and reliably outrun.
        const DWORD jitter = g_interval / 2;
        const DWORD wait   = g_interval + (jitter ? (GetTickCount() % jitter) : 0);
        Sleep(wait);
    }
    return 0;
}

// ---------------------------------------------------------------------------
// PEB unlink
//
// x64 LDR_DATA_TABLE_ENTRY. Declared locally because winternl.h replaces the
// load-order links with Reserved[] placeholders, and the unlink has to walk all
// three lists (InLoadOrder is what most enumerators use, InMemoryOrder is what
// LdrEnumerateLoadedModules uses, InInitializationOrder catches the rest).
// ---------------------------------------------------------------------------
// Minimal PEB_LDR_DATA: only the three list heads matter here, and the SDK's
// own copy names them Reserved[] on recent Windows SDKs.
struct LdrData {
    ULONG      Length;                            // 0x00
    ULONG      Initialized;                       // 0x04
    PVOID      SsHandle;                          // 0x08
    LIST_ENTRY InLoadOrderModuleList;             // 0x10
    LIST_ENTRY InMemoryOrderModuleList;           // 0x20
    LIST_ENTRY InInitializationOrderModuleList;   // 0x30
};
static_assert(offsetof(LdrData, InLoadOrderModuleList) == 0x10,
              "x64 PEB_LDR_DATA layout changed");

constexpr size_t kPebLdrOffset = 0x18;   // PEB->Ldr, x64

struct LdrEntry {
    LIST_ENTRY     InLoadOrderLinks;             // 0x00
    LIST_ENTRY     InMemoryOrderLinks;           // 0x10
    LIST_ENTRY     InInitializationOrderLinks;   // 0x20
    PVOID          DllBase;                      // 0x30
    PVOID          EntryPoint;                   // 0x38
    ULONG          SizeOfImage;                  // 0x40
    ULONG          _pad;                         // 0x44
    UNICODE_STRING FullDllName;                  // 0x48
    UNICODE_STRING BaseDllName;                  // 0x58
    ULONG          Flags;                        // 0x68
    WORD           ObsoleteLoadCount;            // 0x6C
    WORD           TlsIndex;                     // 0x6E
    LIST_ENTRY     HashLinks;                    // 0x70
};
static_assert(offsetof(LdrEntry, DllBase) == 0x30, "x64 LDR_DATA_TABLE_ENTRY layout changed");
static_assert(offsetof(LdrEntry, HashLinks) == 0x70, "x64 LDR_DATA_TABLE_ENTRY layout changed");

bool unlink_module(HMODULE self) {
    // Walked by offset instead of through winternl.h's PEB_LDR_DATA: the SDK
    // 10.0.22621 header declares the module lists as Reserved placeholders, so
    // the named members do not exist. PEB->Ldr is 0x18 on every x64 build since
    // Vista.
    auto* teb = NtCurrentTeb();
    BYTE* peb = teb ? reinterpret_cast<BYTE*>(teb->ProcessEnvironmentBlock) : nullptr;
    if (!peb) return false;
    auto* ldr = *reinterpret_cast<LdrData**>(peb + kPebLdrOffset);
    if (!ldr) return false;

    LIST_ENTRY* heads[3]   = {&ldr->InLoadOrderModuleList,
                              &ldr->InMemoryOrderModuleList,
                              &ldr->InInitializationOrderModuleList};
    const size_t offsets[3] = {offsetof(LdrEntry, InLoadOrderLinks),
                               offsetof(LdrEntry, InMemoryOrderLinks),
                               offsetof(LdrEntry, InInitializationOrderLinks)};

    bool removed = false;
    for (int k = 0; k < 3; ++k) {
        LIST_ENTRY* head = heads[k];
        for (LIST_ENTRY* p = head->Flink; p && p != head;) {
            LIST_ENTRY* next = p->Flink;
            auto* e = reinterpret_cast<LdrEntry*>(reinterpret_cast<BYTE*>(p) - offsets[k]);
            if (e->DllBase == self) {
                p->Blink->Flink = p->Flink;
                p->Flink->Blink = p->Blink;
                p->Flink = p;   // self-loop: a stale walker terminates instead of
                p->Blink = p;   // chasing a freed link

                // LdrpHashTable bucket. Only touched when the double-linked
                // invariants hold, so a layout change degrades to "not removed"
                // rather than corrupting an unrelated list.
                LIST_ENTRY* hash = &e->HashLinks;
                if (hash->Flink && hash->Blink &&
                    hash->Flink->Blink == hash && hash->Blink->Flink == hash) {
                    hash->Blink->Flink = hash->Flink;
                    hash->Flink->Blink = hash->Blink;
                    hash->Flink = hash;
                    hash->Blink = hash;
                }
                removed = true;
                break;   // this list no longer contains us
            }
            p = next;
        }
    }
    return removed;
}

// ---------------------------------------------------------------------------
// NtQueryVirtualMemory redaction
//
// This is the query a native anticheat uses to walk the target's address space.
// Reporting our own pages as MEM_FREE makes the walk skip them: RegionSize is
// preserved so the scanner's own pointer arithmetic stays consistent, and the
// allocation fields are cleared so nothing about the mapping leaks through.
// ---------------------------------------------------------------------------
typedef NTSTATUS (NTAPI* NtQueryVirtualMemory_t)(
    HANDLE, PVOID, ULONG, PVOID, SIZE_T, PSIZE_T);

constexpr ULONG kMemoryBasicInformation = 0;
constexpr NTSTATUS kStatusInvalidParameter = static_cast<NTSTATUS>(0xC000000DL);

std::atomic<NtQueryVirtualMemory_t> g_real_ntqvm{nullptr};

bool is_hidden(const void* p) {
    const uintptr_t v = reinterpret_cast<uintptr_t>(p);
    const int n = g_hidden_count.load(std::memory_order_acquire);
    for (int i = 0; i < n; ++i) {
        if (v >= g_hidden[i].base && v < g_hidden[i].base + g_hidden[i].size) return true;
    }
    return false;
}

NTSTATUS NTAPI HookNtQueryVirtualMemory(HANDLE proc, PVOID base_addr, ULONG info_class,
                                        PVOID info, SIZE_T info_len, PSIZE_T ret_len) {
    // The first stage is written before the caller can publish the trampoline,
    // so a query racing the install spins briefly instead of failing.
    NtQueryVirtualMemory_t real = g_real_ntqvm.load(std::memory_order_acquire);
    for (int i = 0; !real && i < 200; ++i) {
        SwitchToThread();
        real = g_real_ntqvm.load(std::memory_order_acquire);
    }
    if (!real) return kStatusInvalidParameter;

    const NTSTATUS st = real(proc, base_addr, info_class, info, info_len, ret_len);
    if (st < 0 || info_class != kMemoryBasicInformation || !info) return st;

    auto* mbi = static_cast<MEMORY_BASIC_INFORMATION*>(info);
    if (!is_hidden(mbi->BaseAddress) && !is_hidden(base_addr)) return st;

    mbi->AllocationBase    = nullptr;
    mbi->AllocationProtect = 0;
    mbi->State             = MEM_FREE;
    mbi->Protect           = PAGE_NOACCESS;
    mbi->Type              = 0;
    return st;
}

} // namespace

// ---------------------------------------------------------------------------
// Public surface
// ---------------------------------------------------------------------------
void* export_of(HMODULE mod, const char* name) {
    if (!mod || !name) return nullptr;
    auto* base = reinterpret_cast<BYTE*>(mod);
    auto* dos  = reinterpret_cast<IMAGE_DOS_HEADER*>(base);
    if (dos->e_magic != IMAGE_DOS_SIGNATURE) return nullptr;
    auto* nt = reinterpret_cast<IMAGE_NT_HEADERS*>(base + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE) return nullptr;

    const auto& dir = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_EXPORT];
    if (!dir.VirtualAddress || !dir.Size) return nullptr;

    auto* exp   = reinterpret_cast<IMAGE_EXPORT_DIRECTORY*>(base + dir.VirtualAddress);
    auto* names = reinterpret_cast<DWORD*>(base + exp->AddressOfNames);
    auto* funcs = reinterpret_cast<DWORD*>(base + exp->AddressOfFunctions);
    auto* ords  = reinterpret_cast<WORD*>(base + exp->AddressOfNameOrdinals);

    // Resolved by hand rather than with GetProcAddress: the PEB lists have been
    // unlinked by the time anything here runs, so the loader's own lookup can no
    // longer see this process's modules.
    for (DWORD i = 0; i < exp->NumberOfNames; ++i) {
        const char* n = reinterpret_cast<const char*>(base + names[i]);
        if (std::strcmp(n, name) == 0) return base + funcs[ords[i]];
    }
    return nullptr;
}

void hide_range(void* base, size_t size) {
    if (!base || !size) return;
    const int n = g_hidden_count.load(std::memory_order_acquire);
    if (n >= kMaxHidden) return;
    g_hidden[n].base = reinterpret_cast<uintptr_t>(base);
    g_hidden[n].size = size;
    g_hidden_count.store(n + 1, std::memory_order_release);
    openzen::log::info("ntguard: hidden range %p+0x%zx",
                       base, size);
}

bool hook(void* target_v, void* detour, void** original) {
    if (!target_v || !detour || !original) return false;
    BYTE* target = static_cast<BYTE*>(target_v);

    std::lock_guard<std::mutex> lk(g_mtx);
    for (const HookRec& h : g_hooks) {
        if (h.active && h.target == target) return false;
    }

    // 1. Split the prologue into whole instructions covering the 5-byte jmp.
    int need = 0;
    while (need < static_cast<int>(kPatchSize)) {
        bool unsafe = false;
        const int len = hwid::insn_length(target + need, &unsafe);
        if (len <= 0) {
            openzen::log::error("ntguard: cannot decode prologue of %p (offset %d)",
                                static_cast<void*>(target), need);
            return false;
        }
        if (unsafe) {
            openzen::log::error("ntguard: prologue of %p is not relocatable "
                                "(rip-relative or branch at offset %d)",
                                static_cast<void*>(target), need);
            return false;
        }
        need += len;
        if (need > static_cast<int>(kMaxDisplace)) return false;
    }

    // 2. Claim ntdll memory for the relay and the trampoline. Both live inside
    //    ntdll's image, so no private executable page is created.
    BYTE* relay = cave_alloc(kRelaySize);
    if (!relay) {
        openzen::log::error("ntguard: ntdll has no usable cave for a relay");
        return false;
    }
    if (!fits_rel32(target + kPatchSize, relay)) {
        openzen::log::error("ntguard: relay %p is out of rel32 range of %p",
                            static_cast<void*>(relay), static_cast<void*>(target));
        return false;
    }

    BYTE* tramp = cave_alloc(static_cast<size_t>(need) + kThunkSize);
    bool  tramp_private = false;
    if (!tramp) {
        tramp = alloc_near(target, 0x100);
        tramp_private = true;
        if (tramp) {
            hide_range(tramp, 0x100);
            openzen::log::info("ntguard: ntdll cave spent, trampoline fell back to %p",
                               static_cast<void*>(tramp));
        }
    }
    if (!tramp) {
        openzen::log::error("ntguard: no memory for a trampoline of %p",
                            static_cast<void*>(target));
        return false;
    }

    HookRec* slot = nullptr;
    for (HookRec& h : g_hooks) {
        if (!h.active) { slot = &h; break; }
    }
    if (!slot) {
        openzen::log::error("ntguard: hook table full (%d)", kMaxHooks);
        return false;
    }

    slot->target  = target;
    slot->relay   = relay;
    slot->detour  = detour;
    slot->tramp   = tramp;
    slot->tramp_private = tramp_private;
    slot->displaced = need;
    std::memcpy(slot->saved, target, static_cast<size_t>(need));

    // 3. Order matters: the relay must be live before the first stage points at
    //    it, and the trampoline must be readable before any thread can forward.
    if (!write_relay(relay, detour)) {
        if (tramp_private) VirtualFree(tramp, 0, MEM_RELEASE);
        *slot = HookRec{};
        return false;
    }
    write_trampoline(*slot);
    *original = tramp;

    if (!write_first_stage(*slot)) {
        if (tramp_private) VirtualFree(tramp, 0, MEM_RELEASE);
        *slot = HookRec{};
        return false;
    }

    slot->active = true;
    openzen::log::info("ntguard: %p -> ntdll relay %p -> detour %p "
                       "(tramp %p%s, %d bytes displaced)",
                       static_cast<void*>(target), static_cast<void*>(relay), detour,
                       static_cast<void*>(tramp), tramp_private ? ", private" : "", need);
    return true;
}

bool unhook(void* target_v) {
    if (!target_v) return false;
    BYTE* target = static_cast<BYTE*>(target_v);

    std::lock_guard<std::mutex> lk(g_mtx);
    for (HookRec& h : g_hooks) {
        if (!h.active || h.target != target) continue;
        CodeWriter w(target, static_cast<size_t>(h.displaced));
        if (!w.ok()) return false;
        std::memcpy(target, h.saved, static_cast<size_t>(h.displaced));
        if (h.tramp_private && h.tramp) VirtualFree(h.tramp, 0, MEM_RELEASE);
        h = HookRec{};
        return true;
    }
    return false;
}

bool start_watchdog(unsigned interval_ms) {
    std::lock_guard<std::mutex> lk(g_watch_mtx);
    if (g_watch) return true;

    g_interval = interval_ms ? interval_ms : 40;
    if (g_interval < 10) g_interval = 10;

    g_watch_run.store(true, std::memory_order_release);
    g_watch = CreateThread(nullptr, 0, watchdog_main, nullptr, 0, nullptr);
    if (!g_watch) {
        g_watch_run.store(false, std::memory_order_release);
        openzen::log::error("ntguard: CreateThread for the watchdog failed");
        return false;
    }
    return true;
}

void stop_watchdog() {
    HANDLE t = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_watch_mtx);
        t = g_watch;
        g_watch = nullptr;
    }
    if (!t) return;
    g_watch_run.store(false, std::memory_order_release);
    WaitForSingleObject(t, 1000);
    CloseHandle(t);
}

bool install() {
    HMODULE self = openzen::g_self_module;

    // 1. PEB unlink. Every "which modules are loaded" check starts here, and it
    //    is the cheapest one an anticheat can run.
    if (self) {
        if (unlink_module(self)) {
            openzen::log::info("ntguard: %p unlinked from the PEB module lists",
                               static_cast<void*>(self));
        } else {
            openzen::log::error("ntguard: PEB unlink did not find this module");
        }
    }

    // 2. Blank this image out of the VirtualQuery view.
    if (self) {
        size_t image_size = 0;
        auto* dos = reinterpret_cast<IMAGE_DOS_HEADER*>(self);
        if (dos->e_magic == IMAGE_DOS_SIGNATURE) {
            auto* nt = reinterpret_cast<IMAGE_NT_HEADERS*>(
                reinterpret_cast<BYTE*>(self) + dos->e_lfanew);
            if (nt->Signature == IMAGE_NT_SIGNATURE) image_size = nt->OptionalHeader.SizeOfImage;
        }
        if (image_size) hide_range(self, image_size);
    }

    // 3. The filter itself, hooked through an ntdll relay so the hook's own
    //    location is inside the very image it is impersonating.
    bool filtered = false;
    HMODULE ntdll = GetModuleHandleA("ntdll.dll");
    if (ntdll) {
        void* ntqvm = export_of(ntdll, "NtQueryVirtualMemory");
        void* orig  = nullptr;
        if (ntqvm && hook(ntqvm, reinterpret_cast<void*>(&HookNtQueryVirtualMemory), &orig)) {
            g_real_ntqvm.store(reinterpret_cast<NtQueryVirtualMemory_t>(orig),
                               std::memory_order_release);
            filtered = true;
            openzen::log::info("ntguard: NtQueryVirtualMemory redaction armed");
        } else {
            openzen::log::error("ntguard: NtQueryVirtualMemory hook failed");
        }
    } else {
        openzen::log::error("ntguard: ntdll.dll is not loaded");
    }

    // 4. Keep it that way.
    const bool watch = start_watchdog();

    const Stats s = stats();
    openzen::log::info("ntguard: install done (hooks=%d relay_bytes=%d filter=%d watchdog=%d)",
                       s.hooks, s.relay_bytes, filtered ? 1 : 0, watch ? 1 : 0);
    return watch || filtered;
}

void uninstall() {
    stop_watchdog();

    std::lock_guard<std::mutex> lk(g_mtx);
    for (HookRec& h : g_hooks) {
        if (!h.active) continue;
        CodeWriter w(h.target, static_cast<size_t>(h.displaced));
        if (w.ok()) std::memcpy(h.target, h.saved, static_cast<size_t>(h.displaced));
        if (h.tramp_private && h.tramp) VirtualFree(h.tramp, 0, MEM_RELEASE);
        h = HookRec{};
    }
}

Stats stats() {
    Stats s{};
    std::lock_guard<std::mutex> lk(g_mtx);
    for (const HookRec& h : g_hooks) {
        if (h.active) ++s.hooks;
    }
    for (const Cave& c : g_caves) {
        if (c.base) s.relay_bytes += static_cast<int>(c.used);
    }
    s.rearms        = g_rearms.load();
    s.relay_reseats = g_relay_reseats.load();
    s.watchdog      = g_watch != nullptr;
    return s;
}

} // namespace ntguard
