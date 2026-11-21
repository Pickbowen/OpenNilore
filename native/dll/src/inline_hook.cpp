#include "inline_hook.h"

#include <cstdint>
#include <cstring>
#include <mutex>

#include "openzen.h"

namespace hwid {

namespace {

constexpr int kPatchSize   = 5;    // E9 rel32
constexpr int kMaxDisplace = 32;   // bytes we are willing to relocate
constexpr int kMaxHooks    = 8;

struct HookRecord {
    BYTE* target = nullptr;
    BYTE* trampoline = nullptr;
    int   displaced = 0;
    BYTE  saved[kMaxDisplace] = {0};
};

HookRecord g_hooks[kMaxHooks];
std::mutex g_mtx;

// ---------------------------------------------------------------------------
// Instruction length decode.
//
// Only what can appear in a normal Win32 export prologue is supported. When an
// instruction cannot be decoded with confidence the whole hook is refused -
// guessing here means patching half an instruction and crashing the game.
//
// `unsafe` is set when the instruction cannot be relocated as-is: RIP-relative
// operands and relative branches encode a displacement that would point at the
// wrong place after being copied to a trampoline.
// ---------------------------------------------------------------------------
int insn_len(const BYTE* p, bool* unsafe) {
    const BYTE* start = p;
    *unsafe = false;
    bool opsz = false, adsz = false;

    for (int i = 0; i < 5; ++i) {
        BYTE b = *p;
        if (b == 0x66) { opsz = true; ++p; }
        else if (b == 0x67) { adsz = true; ++p; }
        else if (b == 0xF0 || b == 0xF2 || b == 0xF3) { ++p; }
        else if (b == 0x2E || b == 0x36 || b == 0x3E || b == 0x26 ||
                 b == 0x64 || b == 0x65) { ++p; }
        else break;
    }

    bool rex_w = false;
    if (*p >= 0x40 && *p <= 0x4F) {
        rex_w = (*p & 8) != 0;
        ++p;
    }

    BYTE op = *p++;

    // ---- no ModRM -----------------------------------------------------------
    if (op >= 0x50 && op <= 0x5F) return static_cast<int>(p - start);   // push/pop
    if (op >= 0x90 && op <= 0x97) return static_cast<int>(p - start);   // xchg/nop
    if (op >= 0x98 && op <= 0x9F) return static_cast<int>(p - start);   // cwde/cdq/...
    if (op >= 0xA4 && op <= 0xA7) return static_cast<int>(p - start);   // movs/cmps
    if (op >= 0xAA && op <= 0xAF) return static_cast<int>(p - start);   // stos/lods/scas
    if (op >= 0xEC && op <= 0xEF) return static_cast<int>(p - start);   // in/out
    if (op == 0xC3 || op == 0xC9 || op == 0xCB || op == 0xCC ||
        op == 0xCE || op == 0xCF) return static_cast<int>(p - start);
    if (op == 0xF4 || op == 0xF5 || op == 0xF8 || op == 0xF9 ||
        op == 0xFA || op == 0xFB || op == 0xFC || op == 0xFD)
        return static_cast<int>(p - start);

    // ---- immediate, no ModRM ------------------------------------------------
    if (op >= 0xB0 && op <= 0xB7) return static_cast<int>(p - start) + 1;
    if (op >= 0xB8 && op <= 0xBF)
        return static_cast<int>(p - start) + (rex_w ? 8 : (opsz ? 2 : 4));
    if (op >= 0x70 && op <= 0x7F) { *unsafe = true; return static_cast<int>(p - start) + 1; }
    if (op == 0xE8 || op == 0xE9) { *unsafe = true; return static_cast<int>(p - start) + 4; }
    if (op == 0xEB) { *unsafe = true; return static_cast<int>(p - start) + 1; }
    if (op >= 0xE0 && op <= 0xE3) { *unsafe = true; return static_cast<int>(p - start) + 1; }
    if (op == 0x68) return static_cast<int>(p - start) + (opsz ? 2 : 4);
    if (op == 0x6A) return static_cast<int>(p - start) + 1;
    if (op == 0xC2 || op == 0xCA) return static_cast<int>(p - start) + 2;
    if (op >= 0xA0 && op <= 0xA3) {
        *unsafe = true;
        return static_cast<int>(p - start) + (adsz ? 4 : 8);
    }
    if (op == 0xA8) return static_cast<int>(p - start) + 1;
    if (op == 0xA9) return static_cast<int>(p - start) + (opsz ? 2 : 4);

    // ---- every opcode below carries a ModRM --------------------------------
    if (op == 0x0F) return 0;   // multi-byte opcodes: not needed by the targets

    int imm = 0;
    bool group3 = false;
    if (op == 0x04 || op == 0x0C || op == 0x14 || op == 0x1C ||
        op == 0x24 || op == 0x2C || op == 0x34 || op == 0x3C) imm = 1;
    else if (op == 0x05 || op == 0x0D || op == 0x15 || op == 0x1D ||
             op == 0x25 || op == 0x2D || op == 0x35 || op == 0x3D)
        imm = opsz ? 2 : 4;
    else if (op >= 0x00 && op <= 0x3F) imm = 0;              // add/or/adc/sbb/and/sub/xor/cmp
    else if (op >= 0x84 && op <= 0x8F) imm = 0;              // test/xchg/mov/lea/pop
    else if (op == 0x80 || op == 0x82 || op == 0x83) imm = 1;
    else if (op == 0x81) imm = opsz ? 2 : 4;
    else if (op >= 0xD0 && op <= 0xD3) imm = 0;              // shifts by 1 / cl
    else if (op == 0xC0 || op == 0xC1 || op == 0xC6) imm = 1;
    else if (op == 0xC7) imm = opsz ? 2 : 4;
    else if (op == 0x69) imm = opsz ? 2 : 4;
    else if (op == 0x6B) imm = 1;
    else if (op >= 0xD8 && op <= 0xDF) imm = 0;              // x87
    else if (op == 0xF6 || op == 0xF7) group3 = true;
    else if (op == 0xFE || op == 0xFF) imm = 0;
    else return 0;                                           // unknown -> refuse

    BYTE m = *p++;
    int mod = m >> 6, rm = m & 7, reg = (m >> 3) & 7;
    if (mod != 3) {
        if (rm == 4) {                       // SIB
            BYTE sib = *p++;
            if (mod == 0 && (sib & 7) == 5) p += 4;
            else if (mod == 1) p += 1;
            else if (mod == 2) p += 4;
        } else if (mod == 0 && rm == 5) {    // RIP-relative
            p += 4;
            *unsafe = true;
        } else if (mod == 1) {
            p += 1;
        } else if (mod == 2) {
            p += 4;
        }
    }
    if (group3 && (reg == 0 || reg == 1)) imm = (op == 0xF6) ? 1 : (opsz ? 2 : 4);
    p += imm;

    return static_cast<int>(p - start);
}

// Allocate executable memory within +/-2GB of `hint`, so a rel32 jmp can reach.
// (The parameter cannot be called "near": MSVC still defines that as a legacy
// empty macro, which silently deletes it from the declaration.)
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

} // namespace

bool install_inline_hook(void* target_v, void* hook_v, void** original) {
    if (!target_v || !hook_v || !original) return false;
    BYTE* target = static_cast<BYTE*>(target_v);
    BYTE* hook = static_cast<BYTE*>(hook_v);

    std::lock_guard<std::mutex> lk(g_mtx);

    for (const HookRecord& h : g_hooks) {
        if (h.target == target) return false;   // already hooked
    }

    // 1. decode enough whole instructions to cover the 5-byte patch
    int need = 0;
    while (need < kPatchSize) {
        bool unsafe = false;
        int len = insn_len(target + need, &unsafe);
        if (len <= 0) {
            openzen::log::error("inline hook: cannot decode prologue of %p (offset %d)",
                                static_cast<void*>(target), need);
            return false;
        }
        if (unsafe) {
            openzen::log::error("inline hook: prologue of %p is not relocatable "
                                "(rip-relative or branch at offset %d)",
                                static_cast<void*>(target), need);
            return false;
        }
        need += len;
        if (need > kMaxDisplace) return false;
    }

    // 2. trampoline: displaced bytes + jmp back
    BYTE* tramp = alloc_near(target, 0x100);
    if (!tramp) {
        openzen::log::error("inline hook: no executable memory near %p",
                            static_cast<void*>(target));
        return false;
    }
    std::memcpy(tramp, target, static_cast<size_t>(need));
    {
        int64_t back64 = reinterpret_cast<int64_t>(target + need)
                       - reinterpret_cast<int64_t>(tramp + need + 5);
        if (back64 < INT32_MIN || back64 > INT32_MAX) {
            VirtualFree(tramp, 0, MEM_RELEASE);
            return false;
        }
        int32_t back = static_cast<int32_t>(back64);
        tramp[need] = 0xE9;
        std::memcpy(tramp + need + 1, &back, 4);
    }
    FlushInstructionCache(GetCurrentProcess(), tramp, static_cast<size_t>(need) + 5);

    // 3. work out where the 5-byte jmp actually goes.
    //
    // E9 takes a rel32, so it can only reach +/-2GB. The hook lives in this
    // DLL while the target lives in a system DLL, and those are routinely
    // tens of gigabytes apart (observed: iphlpapi at 0x7FFE85DD1DC0, this
    // image at 0x7FF6B65C3550 - about 33GB). Without a thunk the displacement
    // silently wraps and the jump lands nowhere, which is exactly the crash
    // this replaces.
    BYTE* jump_target = hook;
    int64_t delta = reinterpret_cast<int64_t>(hook) - reinterpret_cast<int64_t>(target + 5);
    if (delta < INT32_MIN || delta > INT32_MAX) {
        BYTE* thunk = alloc_near(target, 0x20);
        if (!thunk) {
            openzen::log::error("inline hook: hook too far from %p and no thunk memory",
                                static_cast<void*>(target));
            VirtualFree(tramp, 0, MEM_RELEASE);
            return false;
        }
        // FF 25 00 00 00 00   jmp qword ptr [rip+0]
        // <8-byte absolute address>
        thunk[0] = 0xFF;
        thunk[1] = 0x25;
        int32_t zero = 0;
        std::memcpy(thunk + 2, &zero, 4);
        std::memcpy(thunk + 6, &hook, 8);
        FlushInstructionCache(GetCurrentProcess(), thunk, 14);
        jump_target = thunk;
        delta = reinterpret_cast<int64_t>(jump_target) - reinterpret_cast<int64_t>(target + 5);
        if (delta < INT32_MIN || delta > INT32_MAX) {
            VirtualFree(tramp, 0, MEM_RELEASE);
            return false;
        }
    }

    // 4. record the original bytes for removal
    HookRecord* slot = nullptr;
    for (HookRecord& h : g_hooks) {
        if (!h.target) { slot = &h; break; }
    }
    if (!slot) {
        VirtualFree(tramp, 0, MEM_RELEASE);
        return false;
    }
    slot->target = target;
    slot->trampoline = tramp;
    slot->displaced = need;
    std::memcpy(slot->saved, target, static_cast<size_t>(need));

    // 5. patch. The displacement bytes go in first and the 0xE9 opcode last:
    // a single byte store is atomic, so a thread already inside the function
    // either sees the old prologue or the new jump, never half of each.
    int32_t rel = static_cast<int32_t>(delta);
    DWORD old = 0;
    if (!VirtualProtect(target, static_cast<size_t>(need), PAGE_EXECUTE_READWRITE, &old)) {
        VirtualFree(tramp, 0, MEM_RELEASE);
        slot->target = nullptr;
        return false;
    }
    target[1] = static_cast<BYTE>(rel);
    target[2] = static_cast<BYTE>(rel >> 8);
    target[3] = static_cast<BYTE>(rel >> 16);
    target[4] = static_cast<BYTE>(rel >> 24);
    for (int i = kPatchSize; i < need; ++i) target[i] = 0x90;
    target[0] = 0xE9;
    DWORD ignored = 0;
    VirtualProtect(target, static_cast<size_t>(need), old, &ignored);
    FlushInstructionCache(GetCurrentProcess(), target, static_cast<size_t>(need));

    *original = tramp;
    openzen::log::info("inline hook: %p hooked -> %p (displaced %d bytes, tramp %p)",
                       static_cast<void*>(target), static_cast<void*>(hook), need,
                       static_cast<void*>(tramp));
    return true;
}

bool remove_inline_hook(void* target_v) {
    if (!target_v) return false;
    BYTE* target = static_cast<BYTE*>(target_v);
    std::lock_guard<std::mutex> lk(g_mtx);

    for (HookRecord& h : g_hooks) {
        if (h.target != target) continue;
        DWORD old = 0;
        if (!VirtualProtect(target, static_cast<size_t>(h.displaced),
                            PAGE_EXECUTE_READWRITE, &old)) {
            return false;
        }
        std::memcpy(target, h.saved, static_cast<size_t>(h.displaced));
        DWORD ignored = 0;
        VirtualProtect(target, static_cast<size_t>(h.displaced), old, &ignored);
        FlushInstructionCache(GetCurrentProcess(), target, static_cast<size_t>(h.displaced));
        if (h.trampoline) VirtualFree(h.trampoline, 0, MEM_RELEASE);
        h = HookRecord{};
        return true;
    }
    return false;
}

// Public face of the anonymous-namespace decoder: nt_guard needs the exact same
// prologue split when it displaces instructions into an ntdll trampoline.
int insn_length(const BYTE* p, bool* unsafe) {
    return insn_len(p, unsafe);
}

} // namespace hwid
