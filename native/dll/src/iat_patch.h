#pragma once

#include <windows.h>

namespace hwid {

// Rewrite the IAT slot of `func` imported from `dll` inside `mod`.
// `log_hit` is forced off inside the DLL-load callback: that runs under the
// loader lock, and file I/O or any module load there can stall or deadlock it.
bool patch_import(HMODULE mod, const char* dll, const char* func, void* hook,
                  bool log_hit = true);

// Patch every module currently loaded in this process.
int patch_import_all(const char* dll, const char* func, void* hook);

// Register the (dll, func, hook) triple for late-loaded modules, then patch
// everything already resident. `dll` and `func` must outlive the process -
// call sites pass string literals. Returns the number of modules patched now.
int install_import(const char* dll, const char* func, void* hook);

} // namespace hwid
