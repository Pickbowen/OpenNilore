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

        HANDLE process = OpenProcess(
            PROCESS_QUERY_LIMITED_INFORMATION,
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
