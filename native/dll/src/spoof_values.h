#pragma once

#include <cstdint>
#include <string>

// One fake fingerprint set, shared by every hook surface so the values a
// scanner collects through different APIs agree with each other.
//
// Two rules drive the shape of this file, and both of them come from the same
// observation: an anticheat does not need to prove that a value is *wrong*, it
// only needs to prove that two values disagree. So:
//
//   1. The fake set is DERIVED FROM THE REAL ONE, deterministically. Every
//      field keeps the real length, the real character classes and the real
//      manufacturer prefix; only the free-form tail is reseeded. A serial that
//      is sixteen characters on a real drive stays sixteen characters here,
//      and the same machine always derives the same fake (a fixed seed over
//      the real fingerprint), so the machine does not "change" between runs.
//
//   2. Only fields that can actually be faked are faked. Win32_Processor.
//      ProcessorId is a direct CPUID dump - no user-mode spoof survives a
//      `cpuid` instruction - so it is reported verbatim; faking it would
//      manufacture exactly the contradiction the anticheat looks for.
//
// Everything the scanners collect as one "machine" must therefore stay one
// machine: UUID, BIOS serial, board serial and disk serial all come from this
// single struct.

namespace hwid {

struct SpoofValues {
    // ---- registry-side values -------------------------------------------
    std::wstring machine_guid;       // 8-4-4-4-12, NO braces (registry form)
    std::wstring product_id;         // same shape as the real ProductId
    std::wstring computer_name;      // same prefix/length as the real name
    std::wstring volume_serial;      // 8 hex

    // ---- SMBIOS type 1 (system) -----------------------------------------
    std::wstring product_uuid;       // 8-4-4-4-12, same layout as the real one
    std::wstring product_vendor;     // verbatim: changing it contradicts the model
    std::wstring product_name;       // verbatim
    std::wstring product_version;    // verbatim
    std::wstring system_serial;      // same shape as the real type-1 serial

    // ---- SMBIOS type 2 (baseboard) --------------------------------------
    std::wstring baseboard_serial;   // same shape as the real type-2 serial
    std::wstring baseboard_manufacturer;  // verbatim
    std::wstring baseboard_product;       // verbatim
    std::wstring baseboard_version;       // verbatim

    // ---- storage --------------------------------------------------------
    std::wstring disk_serial;        // same length/alphabet as the real one
    std::wstring disk_model;         // verbatim

    // ---- CPU ------------------------------------------------------------
    std::wstring processor_id;       // verbatim: CPUID is readable by the scanner
};

const SpoofValues& values();

// The real fingerprints the fake set was derived from. Exposed so diagnostics
// can diff "what the machine is" against "what we claim it is" without going
// through a hooked API.
struct RealValues {
    std::wstring product_uuid;
    std::wstring product_vendor;
    std::wstring product_name;
    std::wstring product_version;
    std::wstring system_serial;
    std::wstring baseboard_serial;
    std::wstring baseboard_manufacturer;
    std::wstring baseboard_product;
    std::wstring baseboard_version;
    std::wstring disk_serial;
    std::wstring disk_model;
    std::wstring processor_id;
    std::wstring machine_guid;
    bool smbios_ok = false;
    bool disk_ok = false;
};
const RealValues& real();

// product_uuid in its 16-byte SMBIOS type 1 form; the first three fields are
// little-endian on the wire, which is why this cannot just be a hex decode.
void product_uuid_bytes(unsigned char out[16]);

// disk_serial as an ATA IDENTIFY DEVICE string: 20 bytes, space padded, and
// byte-swapped inside every 16-bit word the way a drive reports it.
void disk_serial_identify(unsigned char out[20]);

// Narrow copy for the byte-level surfaces (SMBIOS string-set, storage
// descriptor). Spoofed values are ASCII by construction.
std::string narrow_ascii(const std::wstring& w);

// Volume serials are derived from the real serial, not from the drive letter:
// a projected WMI query (SELECT VolumeSerialNumber ...) carries no DeviceID or
// __PATH, but it always carries the value itself, so both surfaces agree.
uint32_t mix_volume_serial(uint32_t real_serial);

// MACs keep the real OUI and only reseed the device part: a frame with the
// locally-administered bit set is a virtual adapter, and several anticheats
// treat that bit as a VM marker.
void mix_mac(const unsigned char real[6], unsigned char out[6]);

} // namespace hwid
