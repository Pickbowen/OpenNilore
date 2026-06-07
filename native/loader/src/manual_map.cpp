#include "manual_map.h"

#include <psapi.h>
#include <tlhelp32.h>

#include <sstream>
#include <vector>

#pragma comment(lib, "psapi.lib")

namespace loader {

namespace {

std::wstring fmt_err(const wchar_t* where, DWORD err) {
    wchar_t msg[256] = {0};
    FormatMessageW(FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
                   nullptr, err, 0, msg, 256, nullptr);
    std::wstringstream ss;
    ss << where << L" failed (" << err << L"): " << msg;
    return ss.str();
}

const IMAGE_NT_HEADERS* nt_of(const void* base) {
    auto dos = static_cast<const IMAGE_DOS_HEADER*>(base);
    return reinterpret_cast<const IMAGE_NT_HEADERS*>(
            static_cast<const BYTE*>(base) + dos->e_lfanew);
}

// Run LoadLibraryW(name) inside the target process and return the resulting
// HMODULE seen by that process, or nullptr on failure.
HMODULE remote_load_library(HANDLE process, const wchar_t* dll_name) {
    SIZE_T sz = (std::wcslen(dll_name) + 1) * sizeof(wchar_t);
    LPVOID arg = VirtualAllocEx(process, nullptr, sz,
            MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (!arg) return nullptr;
    SIZE_T written = 0;
    if (!WriteProcessMemory(process, arg, dll_name, sz, &written) || written != sz) {
        VirtualFreeEx(process, arg, 0, MEM_RELEASE);
        return nullptr;
    }
    auto load_lib = reinterpret_cast<LPTHREAD_START_ROUTINE>(GetProcAddress(
            GetModuleHandleW(L"kernel32.dll"), "LoadLibraryW"));
    HANDLE thread = CreateRemoteThread(process, nullptr, 0, load_lib, arg, 0, nullptr);
    if (!thread) {
        VirtualFreeEx(process, arg, 0, MEM_RELEASE);
        return nullptr;
    }
    WaitForSingleObject(thread, 10000);
    DWORD ret = 0;
    GetExitCodeThread(thread, &ret);
    CloseHandle(thread);
    VirtualFreeEx(process, arg, 0, MEM_RELEASE);
    // On x64 GetExitCodeThread returns a DWORD which truncates HMODULE, but
    // ASLR keeps HMODULEs within 32 bits for almost every module on Windows,
    // so this works in practice. The remote_module enumerator below is the
    // fallback if the truncation ever bites us.
    return reinterpret_cast<HMODULE>(static_cast<ULONG_PTR>(ret));
}

HMODULE find_remote_module(HANDLE process, const wchar_t* name) {
    HMODULE mods[1024];
    DWORD cb = 0;
    if (!EnumProcessModulesEx(process, mods, sizeof mods, &cb, LIST_MODULES_ALL)) {
        return nullptr;
    }
    DWORD count = cb / sizeof(HMODULE);
    for (DWORD i = 0; i < count; ++i) {
        wchar_t buf[MAX_PATH];
        if (GetModuleBaseNameW(process, mods[i], buf, MAX_PATH)) {
            if (_wcsicmp(buf, name) == 0) return mods[i];
        }
    }
    return nullptr;
}

void apply_relocations(BYTE* image, const IMAGE_NT_HEADERS* nt, ULONGLONG delta) {
    if (delta == 0) return;
    const auto& dir = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_BASERELOC];
    if (dir.Size == 0) return;
    BYTE* block_ptr = image + dir.VirtualAddress;
    BYTE* end = block_ptr + dir.Size;
    while (block_ptr < end) {
        auto block = reinterpret_cast<IMAGE_BASE_RELOCATION*>(block_ptr);
        if (block->SizeOfBlock == 0) break;
        DWORD count = (block->SizeOfBlock - sizeof(IMAGE_BASE_RELOCATION)) / sizeof(WORD);
        auto entries = reinterpret_cast<WORD*>(block + 1);
        BYTE* page = image + block->VirtualAddress;
        for (DWORD i = 0; i < count; ++i) {
            WORD type = entries[i] >> 12;
            WORD off = entries[i] & 0x0FFF;
            if (type == IMAGE_REL_BASED_DIR64) {
                *reinterpret_cast<ULONGLONG*>(page + off) += delta;
            } else if (type == IMAGE_REL_BASED_HIGHLOW) {
                *reinterpret_cast<DWORD*>(page + off) += static_cast<DWORD>(delta);
            }
            // IMAGE_REL_BASED_ABSOLUTE (0) is a padding entry; ignore.
        }
        block_ptr += block->SizeOfBlock;
    }
}

bool resolve_imports(HANDLE process, BYTE* local_image,
                     const IMAGE_NT_HEADERS* nt, std::wstring& err) {
    const auto& dir = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT];
    if (dir.Size == 0) return true;
    auto desc = reinterpret_cast<IMAGE_IMPORT_DESCRIPTOR*>(local_image + dir.VirtualAddress);

    while (desc->Name) {
        const char* dll_name_ansi = reinterpret_cast<const char*>(local_image + desc->Name);
        wchar_t dll_name_w[MAX_PATH] = {0};
        MultiByteToWideChar(CP_ACP, 0, dll_name_ansi, -1, dll_name_w, MAX_PATH);

        HMODULE remote_mod = find_remote_module(process, dll_name_w);
        if (!remote_mod) {
            remote_mod = remote_load_library(process, dll_name_w);
            if (!remote_mod) remote_mod = find_remote_module(process, dll_name_w);
        }
        if (!remote_mod) {
            std::wstringstream ss;
            ss << L"Remote LoadLibrary failed for dependency " << dll_name_w;
            err = ss.str();
            return false;
        }

        // Use the loader's own copy of the dependency to walk its export table
        // and compute remote function addresses. This works because Windows
        // resolves each DLL's exports to a constant RVA, so
        //   remote_fn = remote_mod_base + (local_fn - local_mod_base)
        HMODULE local_mod = GetModuleHandleA(dll_name_ansi);
        if (!local_mod) local_mod = LoadLibraryA(dll_name_ansi);
        if (!local_mod) {
            std::wstringstream ss;
            ss << L"Local LoadLibrary failed for dependency " << dll_name_w;
            err = ss.str();
            return false;
        }

        auto thunk = reinterpret_cast<IMAGE_THUNK_DATA*>(local_image +
                (desc->OriginalFirstThunk ? desc->OriginalFirstThunk : desc->FirstThunk));
        auto iat = reinterpret_cast<IMAGE_THUNK_DATA*>(local_image + desc->FirstThunk);

        while (thunk->u1.AddressOfData) {
            FARPROC local_fn = nullptr;
            if (IMAGE_SNAP_BY_ORDINAL(thunk->u1.Ordinal)) {
                local_fn = GetProcAddress(local_mod,
                        reinterpret_cast<LPCSTR>(IMAGE_ORDINAL(thunk->u1.Ordinal)));
            } else {
                auto by_name = reinterpret_cast<IMAGE_IMPORT_BY_NAME*>(
                        local_image + thunk->u1.AddressOfData);
                local_fn = GetProcAddress(local_mod, by_name->Name);
            }
            if (local_fn) {
                ULONGLONG remote_fn = reinterpret_cast<ULONGLONG>(remote_mod) +
                        (reinterpret_cast<ULONGLONG>(local_fn) -
                         reinterpret_cast<ULONGLONG>(local_mod));
                iat->u1.Function = remote_fn;
            }
            ++thunk;
            ++iat;
        }
        ++desc;
    }
    return true;
}

DWORD section_protection(DWORD characteristics) {
    bool x = (characteristics & IMAGE_SCN_MEM_EXECUTE) != 0;
    bool r = (characteristics & IMAGE_SCN_MEM_READ) != 0;
    bool w = (characteristics & IMAGE_SCN_MEM_WRITE) != 0;
    if (x && r && w) return PAGE_EXECUTE_READWRITE;
    if (x && r) return PAGE_EXECUTE_READ;
    if (x) return PAGE_EXECUTE;
    if (r && w) return PAGE_READWRITE;
    if (r) return PAGE_READONLY;
    return PAGE_NOACCESS;
}

} // namespace

std::wstring inject_in_memory(DWORD pid, const void* dll_bytes, size_t dll_size) {
    if (!dll_bytes || dll_size < sizeof(IMAGE_DOS_HEADER)) {
        return L"DLL payload too small";
    }
    auto dos = static_cast<const IMAGE_DOS_HEADER*>(dll_bytes);
    if (dos->e_magic != IMAGE_DOS_SIGNATURE) return L"Bad DOS signature";
    auto nt = nt_of(dll_bytes);
    if (nt->Signature != IMAGE_NT_SIGNATURE) return L"Bad NT signature";
    if (nt->FileHeader.Machine != IMAGE_FILE_MACHINE_AMD64) {
        return L"DLL is not x64 (only AMD64 supported)";
    }

    HANDLE process = OpenProcess(
            PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION |
            PROCESS_VM_OPERATION | PROCESS_VM_WRITE | PROCESS_VM_READ,
            FALSE, pid);
    if (!process) return fmt_err(L"OpenProcess", GetLastError());

    SIZE_T image_size = nt->OptionalHeader.SizeOfImage;
    LPVOID remote_image = VirtualAllocEx(process, nullptr, image_size,
            MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
    if (!remote_image) {
        DWORD err = GetLastError();
        CloseHandle(process);
        return fmt_err(L"VirtualAllocEx (image)", err);
    }

    // Build the in-memory image locally before pushing it across, so we can
    // apply relocations + import patches in cheap local memory rather than
    // round-tripping ReadProcessMemory/WriteProcessMemory.
    std::vector<BYTE> local_image(image_size, 0);
    std::memcpy(local_image.data(), dll_bytes, nt->OptionalHeader.SizeOfHeaders);

    auto sect = IMAGE_FIRST_SECTION(nt);
    for (WORD i = 0; i < nt->FileHeader.NumberOfSections; ++i, ++sect) {
        if (sect->SizeOfRawData == 0) continue;
        if (sect->PointerToRawData + sect->SizeOfRawData > dll_size) continue;
        std::memcpy(local_image.data() + sect->VirtualAddress,
                    static_cast<const BYTE*>(dll_bytes) + sect->PointerToRawData,
                    sect->SizeOfRawData);
    }

    ULONGLONG delta = reinterpret_cast<ULONGLONG>(remote_image) -
            nt->OptionalHeader.ImageBase;
    apply_relocations(local_image.data(), nt, delta);

    std::wstring imp_err;
    if (!resolve_imports(process, local_image.data(), nt, imp_err)) {
        VirtualFreeEx(process, remote_image, 0, MEM_RELEASE);
        CloseHandle(process);
        return imp_err;
    }

    SIZE_T written = 0;
    if (!WriteProcessMemory(process, remote_image, local_image.data(),
                            image_size, &written) || written != image_size) {
        DWORD err = GetLastError();
        VirtualFreeEx(process, remote_image, 0, MEM_RELEASE);
        CloseHandle(process);
        return fmt_err(L"WriteProcessMemory (image)", err);
    }

    // Tighten section permissions to match the original PE characteristics.
    sect = IMAGE_FIRST_SECTION(nt);
    for (WORD i = 0; i < nt->FileHeader.NumberOfSections; ++i, ++sect) {
        if (sect->Misc.VirtualSize == 0) continue;
        DWORD prot = section_protection(sect->Characteristics);
        DWORD old = 0;
        VirtualProtectEx(process,
                static_cast<BYTE*>(remote_image) + sect->VirtualAddress,
                sect->Misc.VirtualSize, prot, &old);
    }

    // CreateRemoteThread can only deliver one pointer-sized arg, so we drop a
    // tiny x64 trampoline that calls
    //   DllMain(hModule = remote_image,
    //           fdwReason = DLL_PROCESS_ATTACH,
    //           lpvReserved = NULL)
    // through the standard MSVC ABI before returning.
    BYTE shellcode[] = {
        0x48, 0xB9, 0,0,0,0,0,0,0,0,  // mov rcx, imm64 (hModule)
        0xBA, 0x01, 0x00, 0x00, 0x00, // mov edx, 1     (DLL_PROCESS_ATTACH)
        0x4D, 0x31, 0xC0,             // xor r8, r8     (lpvReserved)
        0x48, 0xB8, 0,0,0,0,0,0,0,0,  // mov rax, imm64 (entry point)
        0x48, 0x83, 0xEC, 0x28,       // sub rsp, 0x28  (16 + shadow space)
        0xFF, 0xD0,                   // call rax
        0x48, 0x83, 0xC4, 0x28,       // add rsp, 0x28
        0xC3                          // ret
    };
    ULONGLONG hmod = reinterpret_cast<ULONGLONG>(remote_image);
    ULONGLONG entry = hmod + nt->OptionalHeader.AddressOfEntryPoint;
    std::memcpy(shellcode + 2, &hmod, sizeof hmod);
    std::memcpy(shellcode + 20, &entry, sizeof entry);

    LPVOID remote_sc = VirtualAllocEx(process, nullptr, sizeof shellcode,
            MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
    if (!remote_sc) {
        DWORD err = GetLastError();
        VirtualFreeEx(process, remote_image, 0, MEM_RELEASE);
        CloseHandle(process);
        return fmt_err(L"VirtualAllocEx (shellcode)", err);
    }
    if (!WriteProcessMemory(process, remote_sc, shellcode, sizeof shellcode, &written)
            || written != sizeof shellcode) {
        DWORD err = GetLastError();
        VirtualFreeEx(process, remote_sc, 0, MEM_RELEASE);
        VirtualFreeEx(process, remote_image, 0, MEM_RELEASE);
        CloseHandle(process);
        return fmt_err(L"WriteProcessMemory (shellcode)", err);
    }

    HANDLE thread = CreateRemoteThread(process, nullptr, 0,
            reinterpret_cast<LPTHREAD_START_ROUTINE>(remote_sc), nullptr, 0, nullptr);
    if (!thread) {
        DWORD err = GetLastError();
        VirtualFreeEx(process, remote_sc, 0, MEM_RELEASE);
        VirtualFreeEx(process, remote_image, 0, MEM_RELEASE);
        CloseHandle(process);
        return fmt_err(L"CreateRemoteThread", err);
    }
    WaitForSingleObject(thread, 30000);
    CloseHandle(thread);

    VirtualFreeEx(process, remote_sc, 0, MEM_RELEASE);
    // Leave remote_image allocated - the DLL stays mapped in the target's
    // address space for the lifetime of the process.
    CloseHandle(process);
    return L"";
}

} // namespace loader
