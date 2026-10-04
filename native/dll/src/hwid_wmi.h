#pragma once

#include <windows.h>

// WMI-level HWID spoof for the injected Minecraft process.
//
// Background: some servers/anticheat read the machine fingerprint through WMI
// (e.g. `Win32_ComputerSystemProduct.UUID` / `SerialNumber`), NOT through
// RegQueryValueExW. WMI queries execute inside the *game process* as COM
// (IWbemLocator -> IWbemServices::ExecQuery -> IEnumWbemClassObject ->
// IWbemClassObject::Get), even though the actual SMBIOS read happens in a
// separate WmiPrvSE.exe provider process. So the only place we can intercept
// is the COM layer inside the game process, before results are handed back.
//
// Strategy (deliberately low-footprint):
//   * IAT-patch `CoCreateInstance` in the game's own modules (NOT ole32's
//     code page) so that when anyone creates CLSID_WbemLocator we hand out a
//     thin proxy wrapping the real IWbemLocator.
//   * The proxy chain (IWbemLocator -> IWbemServices -> IEnumWbemClassObject
//     -> IWbemClassObject) forwards every vtable method to the real object,
//     except IWbemClassObject::Get: when the property name matches a
//     fingerprint field (UUID / SerialNumber / IdentifyingNumber /
//     MachineGUID), we return the per-run random fake instead of the real one.
//   * If the IAT patch finds no importer, we silently disable (default OFF).
//
// This complements hwid_hook.cpp (registry path). Between the two, classic
// fingerprint surfaces - registry MachineGuid/ProductId and WMI
// ComputerSystemProduct UUID/Serial - are both covered.

namespace hwid {

// Install the WMI COM spoof. Returns true if the CoCreateInstance IAT hook is
// live. Silent failure = disabled (default OFF).
bool install_wmi();

} // namespace hwid
