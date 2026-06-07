#include "loader.h"
#include "resource.h"

namespace loader {

bool get_embedded_dll(const void*& out_data, size_t& out_size) {
    HMODULE self = GetModuleHandleW(nullptr);
    HRSRC info = FindResourceW(self, MAKEINTRESOURCEW(IDR_OPENZEN_DLL), RT_RCDATA);
    if (!info) return false;
    DWORD size = SizeofResource(self, info);
    if (size == 0) return false;
    HGLOBAL loaded = LoadResource(self, info);
    if (!loaded) return false;
    void* data = LockResource(loaded);
    if (!data) return false;
    out_data = data;
    out_size = size;
    return true;
}

} // namespace loader
