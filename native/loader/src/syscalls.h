#pragma once

#include <windows.h>
#include <winternl.h>

// Direct syscall provider that bypasses ntdll.dll hooks.
// Reads the original ntdll.dll from disk, extracts syscall numbers,
// and generates executable stubs that trigger the syscall instruction directly.

namespace sys {

struct NtFuncs {
    // NtAllocateVirtualMemory(ProcessHandle, *BaseAddress, ZeroBits,
    //                         *RegionSize, AllocationType, Protect)
    using pfn_NtAllocateVirtualMemory = NTSTATUS(NTAPI*)(
        HANDLE, PVOID*, ULONG_PTR, PSIZE_T, ULONG, ULONG);

    // NtFreeVirtualMemory(ProcessHandle, *BaseAddress, *RegionSize, FreeType)
    using pfn_NtFreeVirtualMemory = NTSTATUS(NTAPI*)(
        HANDLE, PVOID*, PSIZE_T, ULONG);

    // NtWriteVirtualMemory(ProcessHandle, BaseAddress, Buffer, Size, *Written)
    using pfn_NtWriteVirtualMemory = NTSTATUS(NTAPI*)(
        HANDLE, PVOID, PVOID, SIZE_T, PSIZE_T);

    // NtProtectVirtualMemory(ProcessHandle, *BaseAddress, *Size, NewProtect, *OldProtect)
    using pfn_NtProtectVirtualMemory = NTSTATUS(NTAPI*)(
        HANDLE, PVOID*, PSIZE_T, ULONG, PULONG);

    // NtCreateThreadEx(*ThreadHandle, DesiredAccess, ObjectAttributes,
    //                  ProcessHandle, StartRoutine, Argument,
    //                  CreateFlags, ZeroBits, StackSize, MaxStackSize, AttributeList)
    using pfn_NtCreateThreadEx = NTSTATUS(NTAPI*)(
        PHANDLE, ACCESS_MASK, PVOID, HANDLE, PVOID, PVOID,
        ULONG, SIZE_T, SIZE_T, SIZE_T, PVOID);

    pfn_NtAllocateVirtualMemory NtAllocateVirtualMemory = nullptr;
    pfn_NtFreeVirtualMemory     NtFreeVirtualMemory = nullptr;
    pfn_NtWriteVirtualMemory    NtWriteVirtualMemory = nullptr;
    pfn_NtProtectVirtualMemory  NtProtectVirtualMemory = nullptr;
    pfn_NtCreateThreadEx        NtCreateThreadEx = nullptr;
};

// Initialize syscall stubs. Returns false if any syscall number couldn't be resolved.
bool init(NtFuncs& out);

// Release the executable stub memory.
void cleanup(NtFuncs& funcs);

} // namespace sys
