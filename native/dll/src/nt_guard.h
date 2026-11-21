#pragma once

#include <windows.h>

// ntdll-resident hook relay + self-healing watchdog.
//
// Why the hooks in inline_hook.cpp lose against a native anticheat:
//
//   target:   E9 <rel32>  ->  detour inside OpenZen.dll
//
// Two cheap checks kill that shape. (1) The prologue no longer matches the
// vendor build, so a byte-hash of the first N bytes fails. (2) The jmp target
// resolves into an unsigned, MEM_PRIVATE page - a five line VirtualQuery loop
// flags it, and so does "is the destination module a Microsoft-signed image?".
//
// What this file builds instead:
//
//   kernel32!Target -> E9 rel32 -> <relay inside ntdll .text> -> FF25 -> detour
//
// The first hop is exactly what Windows itself ships. kernel32!CreateFileW is
// literally `jmp ntdll!NtCreateFile`; a jump from kernel32 into ntdll is the
// single most ordinary byte pattern in the process. The destination resolves
// into ntdll.dll's image range, backed by a signed Microsoft section, so the
// "unsigned/private destination" test never even runs.
//
// The relay body and the trampoline (which re-enters the real function) both
// live in ntdll's own section padding, so this code creates no MEM_PRIVATE
// executable regions at all. A VirtualQuery sweep of the process finds nothing
// that was not already there.
//
// The second half of the story is time. An anticheat that verifies prologue
// integrity restores the bytes on every scan tick; a one-shot hook wins the
// first millisecond and then silently loses. The watchdog thread re-seats the
// relay and the first-stage jmp on a jittered interval, so a restore performed
// by someone else is undone before the next scan reaches the code it is about
// to read.

namespace ntguard {

struct Stats {
    int  hooks;          // hooks currently armed
    int  relay_bytes;    // bytes claimed inside ntdll's sections
    long rearms;         // first-stage jmp found restored, then re-written
    long relay_reseats;  // relay body found wiped, then re-written
    bool watchdog;
};

// Resolve an export through the PE export directory (no import table touched).
void* export_of(HMODULE mod, const char* name);

// Hook `target` so that the first hop lands inside ntdll:
//
//   target: E9 -> relay(in ntdll) -> FF25 -> detour
//
// `*original` receives a trampoline (also inside ntdll) that executes the
// displaced instructions and continues into the real function. Call it to
// forward.
//
// Returns false - leaving `target` untouched - when the prologue cannot be
// decoded, when ntdll has no cave a rel32 can reach, or when the function is
// already hooked.
bool hook(void* target, void* detour, void** original);

bool unhook(void* target);

// Allocate a relay inside ntdll that jumps to `detour`, and return its address.
//
// This exists for the places where a first-stage E9 is not possible - a vtable
// slot is the case it was written for. The slot then holds an address inside a
// signed Microsoft image instead of an address inside this DLL, so:
//
//   * "does this function pointer resolve to a mapped image?" answers yes;
//   * a prologue read of the slot target finds ntdll's own bytes, not ours;
//   * this DLL can go on being unlinked from the PEB and redacted to MEM_FREE
//     by NtQueryVirtualMemory without any pointer giving that away.
//
// The relay is resident for the life of the process (ntdll cave memory is
// never returned) and is not covered by the watchdog: it is not a hook record.
// Returns nullptr when no cave can reach a valid slot.
void* relay_for(void* detour);

// Create a thread whose reported start address lives inside ntdll.
//
// ThreadQuerySetWin32StartAddress (what a scanner reads to list "what is this
// thread doing") returns exactly the pointer handed to CreateThread. Handing it
// a relay inside ntdll instead of this DLL means a thread created here looks
// like a thread Windows started: start address inside a signed image, no module
// lookup needed. The relay then jumps to `proc` through an FF25, so `proc` still
// runs unchanged.
//
// Late-bound surfaces (watchdog, JNI work) exist for the whole session and are
// enumerated at any time; this is the difference between "a thread in an
// unsigned image" and "a thread in ntdll".
//
// Falls back to a plain CreateThread when no cave is reachable, so a caller
// never loses its thread. Returns nullptr only when CreateThread itself failed.
HANDLE spawn_hidden(LPTHREAD_START_ROUTINE proc, void* param, DWORD* thread_id = nullptr);

// Mark a page range that must never appear in a VirtualQuery sweep. These are
// the ranges NtQueryVirtualMemory redaction (see install) blanks out.
void hide_range(void* base, size_t size);

// Self-healing thread. Idempotent; both calls are safe from any thread.
bool start_watchdog(unsigned interval_ms = 40);
void stop_watchdog();

// Full install.
//
//   code_hooks = false (the shipped default)
//       Do NOT write a single byte into any signed module. No ntdll hook, no
//       relay caves, no redaction. What is left is data-level only: this image
//       is manual-mapped so it is not in the PEB lists to begin with, and the
//       client's own tables (the WMI vtable) are the only things it changes.
//
//       Why: a relay cave and a hooked prologue both live in ntdll's .text.
//       Any check that hashes a loaded module against the copy on disk finds
//       them immediately, and that check costs the scanner a few hundred
//       milliseconds of disk I/O - which is exactly the stutter that was
//       observed right before the client was caught. Nothing this module does
//       can hide from that; the only counter is not to modify the bytes.
//
//   code_hooks = true
//       The previous behaviour: first hop and trampoline inside ntdll, thread
//       entries behind relays, NtQueryVirtualMemory redaction. Denies pointer
//       and region scans, and is detectable by a byte hash.
//
// Every step is best-effort and logged.
bool install(bool code_hooks = false);

// True when install() was called with code_hooks = true. Callers that would
// otherwise patch a signed module (the MAC surface) check this first: in
// data-only mode they must use an import-table patch or stay off, never an
// inline hook.
bool code_hooks_enabled();
void uninstall();

Stats stats();

} // namespace ntguard
