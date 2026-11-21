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

// Mark a page range that must never appear in a VirtualQuery sweep. These are
// the ranges NtQueryVirtualMemory redaction (see install) blanks out.
void hide_range(void* base, size_t size);

// Self-healing thread. Idempotent; both calls are safe from any thread.
bool start_watchdog(unsigned interval_ms = 40);
void stop_watchdog();

// Full install:
//   1. unlink this module from the PEB module lists
//   2. hook ntdll!NtQueryVirtualMemory, blanking the ranges above to MEM_FREE
//   3. arm the watchdog
// Every step is best-effort and logged; a partial install still returns true
// when at least the watchdog came up.
bool install();
void uninstall();

Stats stats();

} // namespace ntguard
