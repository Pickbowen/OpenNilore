#pragma once

#include <windows.h>

#include <string>

// Injection-support layer.
//
// What the loader used to do on a machine that fails: report "OpenProcess failed
// (5): Access is denied" and stop. On a low-end or heavily-managed machine the
// three realistic causes are (a) the game was started elevated and the loader was
// not, (b) an AV/HIPS product blocks CreateRemoteThread, (c) the JRE is 32-bit
// while the payload is x64. Each one needs a different action from the user, so
// each one gets its own sentence and its own recovery path.

namespace loader {

// Enable SeDebugPrivilege on the loader process. Without it, OpenProcess on a
// game launched by an elevated launcher fails with error 5 - the most common
// "it simply will not inject" on a machine where the client otherwise works.
// Returns false when the token cannot be adjusted (not elevated, or the account
// does not hold the privilege).
bool enable_debug_privilege();

// True when this loader itself runs under WOW64 (32-bit process on 64-bit
// Windows). Relevant because the payload is x64 either way.
bool loader_is_wow64();

// Bitness of the target. Uses IsWow64Process2 when available (Win10 1803+) and
// falls back to IsWow64Process. Returns false when the query is not permitted.
bool target_bitness(HANDLE process, bool& is_x86);

// Run fn(arg) on a new thread inside `process`. Three mechanisms are tried in
// order, because each one is blocked by a different class of product:
//
//   1. CreateRemoteThread      - blocked by most HIPS/AV
//   2. NtCreateThreadEx        - same effect, not routed through kernel32, so a
//                                user-mode hook on CreateRemoteThread misses it
//   3. QueueUserAPC            - no new thread at all; needs an alertable thread
//                                in the target, which a JVM usually has
//
// *out_exit receives the thread's exit code when the mechanism produces one;
// APC delivery is asynchronous and reports 0. *out_error receives the last
// Win32/NTSTATUS failure when every mechanism failed.
bool remote_call(HANDLE process, DWORD target_pid, void* fn, void* arg,
                 DWORD timeout_ms, DWORD* out_exit, DWORD* out_error);

// Translate a Win32 error from OpenProcess / VirtualAllocEx / thread creation
// into a sentence that says what the user should change.
std::wstring explain_win32(const wchar_t* where, DWORD err);

} // namespace loader
