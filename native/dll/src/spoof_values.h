#pragma once

#include <cstdint>
#include <string>

// One fake fingerprint set per process run, shared by every hook surface so the
// values a scanner collects through different APIs agree with each other.
//
// Each field is generated once (std::once_flag) and never changes for the life
// of the process, which is what a real machine looks like to a scanner that
// reads the same property twice.

namespace hwid {

struct SpoofValues {
    std::wstring machine_guid;       // {8-4-4-4-12}; registry MachineGuid
    std::wstring product_id;         // 5-3-7-5; registry ProductId / OS SerialNumber
    std::wstring computer_name;      // DESKTOP-XXXXXXX; registry + Win32_ComputerSystem.Name
    std::wstring volume_serial;      // 8 hex; Win32_LogicalDisk.VolumeSerialNumber

    std::wstring product_uuid;       // 8-4-4-4-12; Win32_ComputerSystemProduct.UUID
    std::wstring system_serial;      // Win32_ComputerSystemProduct.IdentifyingNumber + Win32_BIOS.SerialNumber
    std::wstring baseboard_serial;   // Win32_BaseBoard.SerialNumber
    std::wstring disk_serial;        // Win32_DiskDrive.SerialNumber
    std::wstring processor_id;       // BFEBFBFF + 8 hex; Win32_Processor.ProcessorId
};

const SpoofValues& values();

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

} // namespace hwid
