#pragma once

namespace hwid {

// Rewrite the raw SMBIOS table so GetSystemFirmwareTable('RSMB') agrees with
// what the WMI layer reports. Returns the number of structures rewritten, or 0
// when no importer was found (hook stays off, silent).
int install_firmware();

} // namespace hwid
