#include "loader.h"
#include "manual_map.h"

namespace loader {

std::wstring inject(DWORD pid) {
    const void* dll_data = nullptr;
    size_t dll_size = 0;
    if (!get_embedded_dll(dll_data, dll_size)) {
        return L"Embedded OpenZen.dll resource not found in loader EXE";
    }
    return inject_in_memory(pid, dll_data, dll_size);
}

} // namespace loader
