#include "syscalls.h"

#include <cstring>

namespace sys {

namespace {

// Each stub is 12 bytes, aligned to 16:
//   4C 8B D1             mov r10, rcx
//   B8 XX XX 00 00       mov eax, <syscall_number>
//   0F 05                syscall
//   C3                   ret
constexpr size_t STUB_SIZE = 16;

// Extract the syscall number for a given ntdll export by reading the
// original (unhooked) ntdll.dll from disk.
// Returns 0 on failure.
WORD syscallFromDisk(const char* funcName) {
    // Build path to system ntdll.dll
    char sysDir[MAX_PATH];
    GetSystemDirectoryA(sysDir, MAX_PATH);
    char ntdllPath[MAX_PATH];
    lstrcpyA(ntdllPath, sysDir);
    lstrcatA(ntdllPath, "\\ntdll.dll");

    // Open with low-level I/O to avoid triggering LoadLibrary hooks
    HANDLE hFile = CreateFileA(ntdllPath, GENERIC_READ, FILE_SHARE_READ,
                               nullptr, OPEN_EXISTING, 0, nullptr);
    if (hFile == INVALID_HANDLE_VALUE) return 0;

    DWORD fileSize = GetFileSize(hFile, nullptr);
    if (fileSize == 0 || fileSize > 10 * 1024 * 1024) { // sanity: ntdll < 10MB
        CloseHandle(hFile);
        return 0;
    }

    auto fileBuf = static_cast<BYTE*>(HeapAlloc(GetProcessHeap(), 0, fileSize));
    if (!fileBuf) {
        CloseHandle(hFile);
        return 0;
    }

    DWORD bytesRead = 0;
    ReadFile(hFile, fileBuf, fileSize, &bytesRead, nullptr);
    CloseHandle(hFile);

    if (bytesRead != fileSize) {
        HeapFree(GetProcessHeap(), 0, fileBuf);
        return 0;
    }

    // Parse PE headers
    auto dos = reinterpret_cast<IMAGE_DOS_HEADER*>(fileBuf);
    if (dos->e_magic != IMAGE_DOS_SIGNATURE) {
        HeapFree(GetProcessHeap(), 0, fileBuf);
        return 0;
    }

    auto nt = reinterpret_cast<IMAGE_NT_HEADERS*>(fileBuf + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE) {
        HeapFree(GetProcessHeap(), 0, fileBuf);
        return 0;
    }

    auto& exportDirEntry = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_EXPORT];
    if (exportDirEntry.Size == 0) {
        HeapFree(GetProcessHeap(), 0, fileBuf);
        return 0;
    }

    auto exports = reinterpret_cast<IMAGE_EXPORT_DIRECTORY*>(fileBuf + exportDirEntry.VirtualAddress);
    auto names     = reinterpret_cast<DWORD*>(fileBuf + exports->AddressOfNames);
    auto ordinals  = reinterpret_cast<WORD*>(fileBuf + exports->AddressOfNameOrdinals);
    auto functions = reinterpret_cast<DWORD*>(fileBuf + exports->AddressOfFunctions);

    WORD result = 0;

    for (DWORD i = 0; i < exports->NumberOfNames; i++) {
        auto name = reinterpret_cast<const char*>(fileBuf + names[i]);
        if (lstrcmpA(name, funcName) != 0) continue;

        // Found the function. Read its bytes from the file image.
        DWORD funcRva = functions[ordinals[i]];
        auto funcBytes = fileBuf + funcRva;

        // Nt* functions on x64 have a well-known prologue:
        //   4C 8B D1    mov r10, rcx
        //   B8 XX XX    mov eax, imm32   <-- low 16 bits = syscall number
        //
        // Some short stubs (few params) skip the mov r10, rcx and start
        // directly with B8.

        // Try the standard 3-byte prefix first
        if (funcBytes[0] == 0x4C && funcBytes[1] == 0x8B && funcBytes[2] == 0xD1) {
            if (funcBytes[3] == 0xB8) {
                result = *reinterpret_cast<WORD*>(funcBytes + 4);
                break;
            }
        }

        // Fallback: scan first 8 bytes for B8 xx xx
        for (int j = 0; j < 8; j++) {
            if (funcBytes[j] == 0xB8) {
                result = *reinterpret_cast<WORD*>(funcBytes + j + 1);
                break;
            }
        }
        break;
    }

    HeapFree(GetProcessHeap(), 0, fileBuf);
    return result;
}

// Generate an executable syscall stub in `out` for the given syscall number.
void emitStub(BYTE* out, WORD syscallNum) {
    // mov r10, rcx
    out[0] = 0x4C;
    out[1] = 0x8B;
    out[2] = 0xD1;
    // mov eax, <syscall_number>  (32-bit immediate, upper 16 = 0)
    out[3] = 0xB8;
    out[4] = static_cast<BYTE>(syscallNum & 0xFF);
    out[5] = static_cast<BYTE>((syscallNum >> 8) & 0xFF);
    out[6] = 0x00;
    out[7] = 0x00;
    // syscall
    out[8]  = 0x0F;
    out[9]  = 0x05;
    // ret
    out[10] = 0xC3;
    // padding to 16 bytes (not strictly needed, but keeps alignment clean)
    out[11] = 0xCC; // int 3
    out[12] = 0xCC;
    out[13] = 0xCC;
    out[14] = 0xCC;
    out[15] = 0xCC;
}

} // anonymous namespace

bool init(NtFuncs& out) {
    struct Mapping {
        const char* name;
        WORD* target;
    };

    WORD nums[5] = {};
    Mapping mappings[] = {
        { "NtAllocateVirtualMemory", &nums[0] },
        { "NtFreeVirtualMemory",     &nums[1] },
        { "NtWriteVirtualMemory",    &nums[2] },
        { "NtProtectVirtualMemory",  &nums[3] },
        { "NtCreateThreadEx",        &nums[4] },
    };

    // Resolve all syscall numbers from disk
    for (auto& m : mappings) {
        *m.target = syscallFromDisk(m.name);
        if (*m.target == 0) return false;
    }

    // Allocate executable memory for 5 stubs
    constexpr size_t COUNT = sizeof(mappings) / sizeof(mappings[0]);
    auto stubs = static_cast<BYTE*>(VirtualAlloc(
        nullptr, COUNT * STUB_SIZE,
        MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE));
    if (!stubs) return false;

    // Generate stubs
    for (size_t i = 0; i < COUNT; i++) {
        emitStub(stubs + i * STUB_SIZE, nums[i]);
    }

    out.NtAllocateVirtualMemory = reinterpret_cast<NtFuncs::pfn_NtAllocateVirtualMemory>(stubs + 0 * STUB_SIZE);
    out.NtFreeVirtualMemory     = reinterpret_cast<NtFuncs::pfn_NtFreeVirtualMemory>(stubs + 1 * STUB_SIZE);
    out.NtWriteVirtualMemory    = reinterpret_cast<NtFuncs::pfn_NtWriteVirtualMemory>(stubs + 2 * STUB_SIZE);
    out.NtProtectVirtualMemory  = reinterpret_cast<NtFuncs::pfn_NtProtectVirtualMemory>(stubs + 3 * STUB_SIZE);
    out.NtCreateThreadEx        = reinterpret_cast<NtFuncs::pfn_NtCreateThreadEx>(stubs + 4 * STUB_SIZE);

    return true;
}

void cleanup(NtFuncs& funcs) {
    if (funcs.NtAllocateVirtualMemory) {
        // All stubs are in one contiguous allocation; the first pointer
        // points at offset 0 of that allocation.
        VirtualFree(reinterpret_cast<PVOID>(funcs.NtAllocateVirtualMemory), 0, MEM_RELEASE);
        funcs.NtAllocateVirtualMemory = nullptr;
        funcs.NtFreeVirtualMemory = nullptr;
        funcs.NtWriteVirtualMemory = nullptr;
        funcs.NtProtectVirtualMemory = nullptr;
        funcs.NtCreateThreadEx = nullptr;
    }
}

} // namespace sys
