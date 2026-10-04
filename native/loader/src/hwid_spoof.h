#pragma once

// HWID spoof for the injected target process.
//
// This is a *feature-shaped* request: the reverse-engineering of MaxHook's
// runtime (.boot-decrypted) rule set is not yet complete, so we cannot assert
// it is a MAXHOOK bypass on its own. It is intended to be combined with the
// injection-chain hardening work in native/dll. The spoof here is designed to
// be transparent to the *classic* HWID fingerprinting surfaces (registry
// machine GUID, SMB machine name, volume serial, MAC, PC name) that a
// Minecraft server anti-cheat would typically collect.
//
// All APIs are deliberately resolved at runtime so this module can be linked
// into the loader (a GUI process) as well as the DLL without a hard link-time
// dependency chain (keeps Qt loader imports clean).
//
// NOTE ON BUGLAND STATE:
// The .bugland region is encrypted runtime state, not a plaintext rule set.
// This module does NOT read it; it only provides the spoof primitives. Wiring
// the spoof into the injected runtime belongs to the DLL bootstrap, not here.

#include <cstdint>
#include <string>
#include <vector>

#include <windows.h>

typedef int (*hwid_spoof_hook_fn)(void* user, const wchar_t* key, const wchar_t* value);

namespace hwidspoof {

// ---- config ---------------------------------------------------------------
struct Config {
    bool     enabled = false;
    // Custom values override the generated ones; empty = auto-generate.
    std::wstring machine_guid;   // {XXXXXXXX-XXXX-...}
    std::wstring volume_serial;  // 8 hex upper
    std::wstring mac;            // 12 hex upper
    std::wstring computer_name;  // NetBIOS name, 15 chars max
    std::wstring product_id;     // optional, if a fingerprint includes it
};

bool init(const Config& cfg);

// ---- classic HWID surfaces -----------------------------------------------
// crypto RNG to avoid a bad-but-repeated value being the fingerprint keyword
bool read_machine_guid(std::wstring& out);       // HKLM\SOFTWARE\Microsoft\Cryptography\MachineGuid
bool get_volume_serial(std::wstring& out);        // from C:\ (or system drive)
std::wstring generate_machine_guid();
std::wstring generate_volume_serial();
std::wstring generate_mac();
std::wstring generate_computer_name();            // "PC" + 4 hex

// randomness that is *not* correlated with library load order / thread ids
std::wstring random_hex(size_t bytes);
std::vector<uint8_t> random_bytes(size_t n);

// ---- hook plumbing --------------------------------------------------------
// Called from the injected DLL bootstrap: wrap the given read callback and
// replace the value it would return with the spoofed one.
void set_spoof_hook(hwid_spoof_hook_fn fn, void* user);

// IAT-level registry spoof lives in the injected DLL (native/dll/src/hwid_hook).
// The loader-side API below is kept for reference/back-compat only; the actual
// spoofing is performed by hwid_hook in the target process.

// ---- native-path utilities (loader side) ----------------------------------
// Patch a remote process's PEB computer name buffer + registry machine GUID
// via the loader. Written but not wired yet; see NOTES in .cpp.
bool patch_remote_computer_name(DWORD pid, const std::wstring& name);
bool patch_remote_machine_guid(DWORD pid, const std::wstring& guid);

} // namespace hwidspoof