#pragma once

#include <windows.h>

// Minimal x64 inline hook (function-entry patch), the same shape the anticheat
// and Cheart's own spoof DLL use (Microsoft Detours).
//
// Why this exists: an IAT patch only rewrites the import slots of modules that
// were already loaded, so a WinLicense-protected module (the anticheat) has to
// be skipped - and then it reads the real value. An inline hook rewrites the
// first bytes of the target function itself, so *every* caller in the process
// goes through our code, including the anticheat, and no import table is
// touched.
//
// Scope is deliberately narrow: this handles the instruction forms that appear
// at the start of a normal Win32 export (mov [rsp+x], reg / push / sub rsp /
// lea / mov rax). If the prologue does not decode safely the hook is refused
// rather than risking a half-patched function.

namespace hwid {

// Length of the single instruction at `p`, or 0 when it cannot be decoded
// with confidence. `unsafe` is set for relocatable-hostile forms (rip-relative
// operands, relative branches): copying those bytes to another address changes
// their meaning.
//
// Exposed because nt_guard.cpp has to displace exactly the same prologues, and
// two decoders drifting apart is how a half-patched function gets shipped.
int insn_length(const BYTE* p, bool* unsafe);

// Install a 5-byte jmp at `target`. On success `*original` receives the
// trampoline, which executes the displaced instructions and continues into the
// rest of the real function - call it to forward.
//
// Returns false (and leaves the target untouched) when the prologue cannot be
// decoded, when no trampoline can be allocated within rel32 range, or when the
// target is already patched.
bool install_inline_hook(void* target, void* hook, void** original);

// Remove a hook installed by install_inline_hook, restoring the original bytes.
bool remove_inline_hook(void* target);

} // namespace hwid
