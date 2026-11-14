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
//   IWbemServices::ExecQuery        (index 20)
//   IEnumWbemClassObject::Next      (index 4)
//
// Those tables are process-global static data, so one patch covers every
// IWbemServices the process will ever hold - including the one the anticheat
// creates through its own, untouched, IAT entry. The objects the anticheat
// receives are the real provider objects: same vptr, same refcount, same
// GetObjectText, same Clone. Only the value of the spoofed properties differs,
// and those are rewritten in place with IWbemClassObject::Put, which is a
// normal COM call the object is built to accept.
//
// The one thing this cannot survive is Control Flow Guard: wbemcomn's indirect
// call will reject a vtable slot pointing at an address outside the CFG bitmap.
// install_wmi_vtbl() checks the mitigation and refuses to arm in that case
// rather than crashing the game.

namespace hwid {

// >0 = both vtables patched, 0 = nothing to patch, -1 = not available
// (CFG enabled, or wbemcomn not loaded yet).
int install_wmi_vtbl();

// True once the vtable patch is live.
bool wmi_vtbl_active();

} // namespace hwid
