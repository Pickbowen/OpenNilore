#pragma once

namespace hwid {

// GetAdaptersAddresses is the only MAC surface MaxHook reaches for, and it is
// not covered by the registry or WMI hooks. >0 = modules patched, 0 = armed.
int install_net();

} // namespace hwid
