#pragma once

#include <windows.h>

// WMI spoof with no import-table footprint.
//
// The previous approach (hwid_wmi.cpp) replaced CoCreateInstance in every
// module's IAT and handed the caller a proxy object. Both halves are visible
// from inside the anticheat client:
//
//   * the IAT slot it calls through no longer equals
//     GetProcAddress(ole32, "CoCreateInstance"), and it is not inside ole32's
//     image either - that is a one-instruction check;
//   * the IWbemLocator it receives has its vptr inside our DLL, not inside
//     ole32/combase/wbemcomn.
//
// This module leaves every IAT alone. It patches the *vtable* of the WBEM COM
// classes inside wbemcomn.dll instead:
//
//   IWbemServices::GetObject         (index 6)
//   IWbemServices::ExecQuery         (index 20)
//   IEnumWbemClassObject::Next       (index 4)
//   IWbemClassObject::Get            (index 4)
//
// Those tables are process-global static data, so one patch covers every
// object the process will ever hold - including the ones the anticheat creates
// through its own, untouched, IAT entry. The objects the anticheat receives are
// the real provider objects: same vptr, same refcount, same GetObjectText, same
// Clone. Only the value of the spoofed properties differs, and those are
// rewritten in place with IWbemClassObject::Put, which is a normal COM call the
// object is built to accept.
//
// One table is not always enough: a process can hold the in-proc provider
// object and the cross-apartment proxy combase marshals for the same interface,
// and those have different vtables with different Get slots. The install
// therefore keeps a registry of every table it has patched, and each hook
// forwards through the original that belongs to the table it was entered from.
//
// The one thing this cannot survive is Control Flow Guard: wbemcomn's indirect
// call will reject a vtable slot pointing at an address outside the CFG bitmap.
// install_wmi_vtbl() registers every hook as a valid call target and refuses to
// arm if that fails, rather than crashing the game.

namespace hwid {

// Result of install_wmi_vtbl(). A partial install is never reported as a full
// one, and a self check that could not read the spoofed values back is never
// reported as a full one either.
enum WmiVtblStatus {
    kWmiVtblUnavailable = -1,  // CFG refused a target, COM/WBEM unavailable, or a hard fault
    kWmiVtblNothing     =  0,  // nothing patched yet (provider not reachable)
    kWmiVtblPartial     =  1,  // some slots patched, or the self check left a value unconfirmed
    kWmiVtblFull        =  2,  // every slot patched and the self check read the spoofed values back
};

// Install, or retry, the vtable patch. Idempotent: once kWmiVtblFull is
// reached, later calls return it without touching COM again. Anything short of
// kWmiVtblFull is retried, because the first pass can run before the WBEM
// provider (or ole32/combase) is mapped.
//
// `quiet` suppresses the informational step lines for that attempt. The early
// arm thread passes true: it retries every few hundred milliseconds and only
// the first attempt (or a success) is worth reading.
int install_wmi_vtbl(bool quiet = false);

// True only when every slot is patched AND the self check did not contradict
// the spoof. A partial or vetoed install reports false, so the caller can arm a
// fallback surface instead of believing a half-covered patch is complete.
bool wmi_vtbl_active();

// True when the last install reached kWmiVtblFull.
bool wmi_vtbl_verified();

// How many times each hook has actually been entered. Read from a background
// thread and logged periodically: it is the only way to tell "the anticheat
// never touched WMI" apart from "it did, and we were in its path".
struct WmiVtblStats {
    long get_object = 0;
    long exec_query = 0;
    long next = 0;
    long object_get = 0;
};
WmiVtblStats wmi_vtbl_stats();

} // namespace hwid
