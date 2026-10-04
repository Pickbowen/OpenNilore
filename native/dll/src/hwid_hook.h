#pragma once

#include <atomic>
#include <windows.h>

// IAT-level registry spoof for the injected Minecraft process.
//
// Installs a per-process IAT hook on RegQueryValueExW inside the injected
// target so that reads of the classic HWID fingerprint keys return
// randomly-generated fake values instead of the machine's real ones:
//
//   HKLM\SOFTWARE\Microsoft\Cryptography\MachineGuid
//   HKLM\Software\Microsoft\Windows NT\CurrentVersion\ProductId
//   HKLM\SYSTEM\CurrentControlSet\Control\SystemInformation\BIOS\... (future)
//   VolumeSerialNumber (via GetVolumeInformationW is NOT covered - registry only)
//
// Implementation notes (all deliberate for low detectability):
//  * We patch only the *target's own* IAT slots (the modules that actually
//    import RegQueryValueExW), NOT advapi32's exported code page. MaxHook
//    (WinLicense VM) is unlikely to do Windows-IAT integrity checks, and the
//    target's own imports are the least visible surface we can touch.
//  * If the install can't find any IAT slot or can't write it, we silently
//    disable (default OFF) - the user explicitly asked: "read ok -> spoof,
//    can't read -> default OFF". No error, no crash.
//  * Each injection run generates ONE random set of fake values cached for the
//    lifetime of the process, so repeated reads are consistent (a real machine
//    would report a stable fingerprint).
//
// Called from the DLL bootstrap (inject_thread) BEFORE JVM attach.

namespace hwid {

// Number of IAT slots patched (for logging / verification).
extern std::atomic<LONG> g_patch_count;

// Install the spoof. Returns true if at least one IAT slot was patched and
// is active. Never throws / logs - silent failure = disabled.
bool install();

// True if install() succeeded and the hook is currently live.
bool active();

} // namespace hwid
