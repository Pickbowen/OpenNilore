#pragma once

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
    std::wstring system_serial;      // Win32_ComputerSystemProduct.IdentifyingNumber
    std::wstring bios_serial;        // Win32_BIOS.SerialNumber
    std::wstring baseboard_serial;   // Win32_BaseBoard.SerialNumber
    std::wstring disk_serial;        // Win32_DiskDrive.SerialNumber
    std::wstring processor_id;       // BFEBFBFF + 8 hex; Win32_Processor.ProcessorId
};

const SpoofValues& values();

} // namespace hwid
