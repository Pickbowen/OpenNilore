#pragma once

#include <windows.h>
#include <string>
#include <vector>

namespace loader {

struct JavaProcess {
    DWORD pid;
    std::wstring image_name;
    std::wstring command_line;
    std::wstring window_title;
    std::wstring window_class;
};

struct WindowInfo {
    std::wstring title;
    std::wstring class_name;
};

// Enumerate processes whose image is javaw.exe / java.exe.
std::vector<JavaProcess> list_java_processes();

// Working directory the target process was started with (PEB CurrentDirectory).
// Empty when it cannot be read.
std::wstring read_working_directory(HANDLE process);

// Environment block of the target process (PEB ProcessParameters.Environment), in
// the UTF-16 "KEY=VALUE\0...\0\0" form CreateProcess wants. Empty when it cannot
// be read or does not validate.
std::vector<wchar_t> read_environment_block(HANDLE process);

// Map the embedded OpenZen.dll directly into the target process and run its
// DllMain via shellcode. The DLL bytes never touch disk. Returns an empty
// string on success or a human-readable error message.
std::wstring inject(DWORD pid);

// Return a pointer into the loader EXE's resource section that holds the
// embedded OpenZen.dll along with its byte size. The pointer remains valid
// for the lifetime of the loader process.
bool get_embedded_dll(const void*& out_data, size_t& out_size);

// Walk top-level windows and return the title + class name of the most
// informative window belonging to the given pid (longest title wins).
// Returns empty strings if none found.
WindowInfo window_info_for(DWORD pid);

} // namespace loader
