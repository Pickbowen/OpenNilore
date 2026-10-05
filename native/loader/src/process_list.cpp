#include "loader.h"
#include "loader_settings.h"

#include <tlhelp32.h>
#include <psapi.h>
#include <winternl.h>

#include <algorithm>
#include <cwctype>
#include <cstring>
#include <sstream>
#include <string>

namespace loader {

namespace {
    bool ci_equals(const std::wstring& a, const wchar_t* b) {
        std::wstring lower(a);
        std::transform(lower.begin(), lower.end(), lower.begin(),
                       [](wchar_t c) { return (wchar_t)std::towlower(c); });
        return lower == b;
    }

    std::wstring read_command_line(HANDLE process) {
        // Read the FULL command line from the remote PEB via
        // NtQueryInformationProcess (ProcessBasicInformation) + ReadProcessMemory.
        // This is what Early Mode needs to relaunch the same Minecraft with all
        // its JVM args / main class intact.
        auto pNtQuery = reinterpret_cast<NTSTATUS(NTAPI*)(HANDLE, ULONG, PVOID,
                                                         ULONG, PULONG)>(
            GetProcAddress(GetModuleHandleA("ntdll.dll"),
                           "NtQueryInformationProcess"));
        struct ProcBasicInfo {
            PVOID reserved1;
            PVOID PebBaseAddress;
            PVOID reserved2[2];
            ULONG_PTR uniqueProcessId;
            PVOID reserved3;
        };
        ProcBasicInfo info{};
        if (pNtQuery && NT_SUCCESS(pNtQuery(process, 0, &info,
                                            sizeof info, nullptr))
            && info.PebBaseAddress) {
            // PEB -> ProcessParameters -> CommandLine (UNICODE_STRING).
            // Offsets (x64): PEB+0x20 = ProcessParameters;
            //   RTL_USER_PROCESS_PARAMETERS +0x70 = CommandLine (UNICODE_STRING).
            constexpr ULONG_PTR kParamsOff = 0x20;
            constexpr ULONG_PTR kCmdLineOff = 0x70;
            SIZE_T read = 0;
            BYTE remoteBuf[0x400] = {0};
            if (ReadProcessMemory(process, info.PebBaseAddress,
                                  remoteBuf, 0x400, &read)) {
                PVOID params = nullptr;
                std::memcpy(&params, remoteBuf + kParamsOff, sizeof params);
                BYTE paramsBuf[0x300] = {0};
                if (params && ReadProcessMemory(process, params,
                                                paramsBuf, 0x300, &read)) {
                    UNICODE_STRING cmd{};
                    std::memcpy(&cmd, paramsBuf + kCmdLineOff, sizeof cmd);
                    if (cmd.Length > 0 && cmd.Buffer && cmd.Length < 0x10000) {
                        std::wstring out(cmd.Length / 2, L'\0');
                        if (ReadProcessMemory(process, cmd.Buffer, out.data(),
                                              cmd.Length, &read)) {
                            return out;
                        }
                    }
                }
            }
        }
        // Fallback: executable path only.
        wchar_t buf[MAX_PATH * 2];
        DWORD size = (DWORD)(sizeof buf / sizeof buf[0]);
        if (QueryFullProcessImageNameW(process, 0, buf, &size)) {
            return std::wstring(buf, size);
        }
        return L"";
    }
}

std::wstring read_working_directory(HANDLE process) {
    // Same PEB walk as read_command_line, different field: Early Mode relaunches
    // the instance and Minecraft resolves its own files relative to the working
    // directory the launcher gave it, so it has to be reproduced, not guessed.
    auto pNtQuery = reinterpret_cast<NTSTATUS(NTAPI*)(HANDLE, ULONG, PVOID,
                                                     ULONG, PULONG)>(
        GetProcAddress(GetModuleHandleA("ntdll.dll"),
                       "NtQueryInformationProcess"));
    struct ProcBasicInfo {
        PVOID reserved1;
        PVOID PebBaseAddress;
        PVOID reserved2[2];
        ULONG_PTR uniqueProcessId;
        PVOID reserved3;
    };
    ProcBasicInfo info{};
    if (!pNtQuery || !NT_SUCCESS(pNtQuery(process, 0, &info, sizeof info, nullptr))
        || !info.PebBaseAddress) {
        return std::wstring();
    }
    // PEB+0x20 = ProcessParameters;
    //   RTL_USER_PROCESS_PARAMETERS +0x38 = CurrentDirectory (CURDIR.DosPath).
    constexpr ULONG_PTR kParamsOff = 0x20;
    constexpr ULONG_PTR kCurDirOff = 0x38;
    SIZE_T read = 0;
    BYTE remoteBuf[0x400] = {0};
    if (!ReadProcessMemory(process, info.PebBaseAddress, remoteBuf, 0x400, &read)) {
        return std::wstring();
    }
    PVOID params = nullptr;
    std::memcpy(&params, remoteBuf + kParamsOff, sizeof params);
    BYTE paramsBuf[0x300] = {0};
    if (!params || !ReadProcessMemory(process, params, paramsBuf, 0x300, &read)) {
        return std::wstring();
    }
    UNICODE_STRING dir{};
    std::memcpy(&dir, paramsBuf + kCurDirOff, sizeof dir);
    if (dir.Length == 0 || !dir.Buffer || dir.Length >= 0x10000) {
        return std::wstring();
    }
    std::wstring out(dir.Length / 2, L'\0');
    if (!ReadProcessMemory(process, dir.Buffer, out.data(), dir.Length, &read)) {
        return std::wstring();
    }
    return out;
}

std::vector<wchar_t> read_environment_block(HANDLE process) {
    // Early Mode relaunches the instance, so it has to look like the launcher
    // started it - same working directory, same environment. Offsets are the x64
    // RTL_USER_PROCESS_PARAMETERS layout, same walk as read_command_line.
    auto pNtQuery = reinterpret_cast<NTSTATUS(NTAPI*)(HANDLE, ULONG, PVOID,
                                                     ULONG, PULONG)>(
        GetProcAddress(GetModuleHandleA("ntdll.dll"),
                       "NtQueryInformationProcess"));
    struct ProcBasicInfo {
        PVOID reserved1;
        PVOID PebBaseAddress;
        PVOID reserved2[2];
        ULONG_PTR uniqueProcessId;
        PVOID reserved3;
    };
    ProcBasicInfo info{};
    if (!pNtQuery || !NT_SUCCESS(pNtQuery(process, 0, &info, sizeof info, nullptr))
        || !info.PebBaseAddress) {
        return {};
    }
    constexpr ULONG_PTR kParamsOff  = 0x20;
    constexpr ULONG_PTR kEnvOff     = 0x80;   // .Environment
    constexpr ULONG_PTR kEnvSizeOff = 0x3F0;  // .EnvironmentSize
    SIZE_T read = 0;
    BYTE remoteBuf[0x40] = {0};
    if (!ReadProcessMemory(process, info.PebBaseAddress, remoteBuf, sizeof remoteBuf, &read)) {
        return {};
    }
    PVOID params = nullptr;
    std::memcpy(&params, remoteBuf + kParamsOff, sizeof params);
    if (!params) return {};
    BYTE paramsBuf[0x420] = {0};
    if (!ReadProcessMemory(process, params, paramsBuf, sizeof paramsBuf, &read)) {
        return {};
    }
    PVOID env = nullptr;
    ULONG_PTR envSize = 0;
    std::memcpy(&env, paramsBuf + kEnvOff, sizeof env);
    std::memcpy(&envSize, paramsBuf + kEnvSizeOff, sizeof envSize);
    // Refuse anything that does not look like the double-null-terminated block
    // CreateProcess expects - a bad block handed to CreateProcess would fail the
    // relaunch, and inheriting ours is the safer degradation.
    if (!env || envSize < 4 || envSize > 0x10000 || (envSize % sizeof(wchar_t)) != 0) {
        return {};
    }
    std::vector<wchar_t> out(envSize / sizeof(wchar_t), L'\0');
    if (!ReadProcessMemory(process, env, out.data(), envSize, &read) || read != envSize) {
        return {};
    }
    if (out.size() < 2 || out[out.size() - 1] != L'\0' || out[out.size() - 2] != L'\0') {
        return {};
    }
    return out;
}

std::vector<JavaProcess> list_java_processes() {
    std::vector<JavaProcess> result;

    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return result;

    PROCESSENTRY32W pe{};
    pe.dwSize = sizeof pe;
    if (!Process32FirstW(snap, &pe)) {
        CloseHandle(snap);
        return result;
    }

    do {
        std::wstring name = pe.szExeFile;
        if (!ci_equals(name, L"javaw.exe") && !ci_equals(name, L"java.exe")) continue;

        JavaProcess jp;
        jp.pid = pe.th32ProcessID;
        jp.image_name = name;

        // PROCESS_VM_READ is required, not optional: read_command_line() walks
        // the target's PEB with ReadProcessMemory, which QUERY_LIMITED_INFORMATION
        // alone does not permit. Without it every command line silently degraded
        // to the bare exe path, which made Early Mode's "does this look like
        // Minecraft" test match nothing at all.
        HANDLE process = OpenProcess(
            PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_VM_READ,
            FALSE, jp.pid);
        if (process) {
            jp.command_line = read_command_line(process);
            CloseHandle(process);
        }
        WindowInfo wi = window_info_for(jp.pid);
        jp.window_title = std::move(wi.title);
        jp.window_class = std::move(wi.class_name);
        result.push_back(std::move(jp));
    } while (Process32NextW(snap, &pe));

    CloseHandle(snap);
    return result;
}

} // namespace loader
