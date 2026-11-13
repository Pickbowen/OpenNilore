#pragma once

namespace hwid {

// Keep the raw disk enumeration paths in step with the WMI disk serial:
// IOCTL_STORAGE_QUERY_PROPERTY, SMART_RCV_DRIVE_DATA, the ATA pass-through
// IDENTIFY block, and GetVolumeInformationW. Returns the number of imports
// hooked, or 0 when nothing was found.
int install_disk();

} // namespace hwid
