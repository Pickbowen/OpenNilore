#pragma once

#include <windows.h>
#include <string>

namespace loader {

// Map a DLL image directly into the target process and invoke its entry point,
// without writing the DLL to disk first. Implements a minimal PE loader:
// VirtualAllocEx + relocations + import resolution + DllMain stub via
// CreateRemoteThread shellcode.
//
// Returns an empty string on success, or a human-readable error message.
std::wstring inject_in_memory(DWORD pid, const void* dll_bytes, size_t dll_size);

} // namespace loader
