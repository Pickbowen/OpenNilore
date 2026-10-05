#pragma once

#include <windows.h>

#include <string>

// NOT WIRED UP - kept for reference.
//
// The "restart the instance suspended and map the DLL in before it resumes"
// approach below is sound in isolation, but it loses the launcher's child
// process. NetEase-style launchers pass -DlauncherControlPort/-DlauncherGameId/
// -DToken and watch that child, so killing it makes the launcher report "fatal
// error during startup" and tear the game down. Early Mode is now a plain attach
// that happens early (the moment a java process appears) instead; see
// MainWindow::maybeAutoInject and InjectionOverlay::start.

namespace loader {

// Early Mode.
//
// The DLL only ends up "before" anything the game loads later if we are the one
// that starts the process: only the creator can touch it while its main thread
// is still frozen. So this restarts the given instance from its own command
// line with CREATE_SUSPENDED, maps the embedded DLL into the fresh process
// (manual map, so the loader lock is never involved), stops the old instance
// and only then lets the new one run.
//
// Ordering matters for "don't kill the running game on failure": the old
// instance is terminated *after* the injection succeeded, and a failed inject
// tears the suspended child down instead of resuming it half-set-up.
// Returns an empty string on success, or a human-readable error message.
std::wstring inject_early(DWORD existing_pid, const std::wstring& command_line,
                          DWORD& out_new_pid);

} // namespace loader
