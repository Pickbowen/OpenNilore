#include "loader.h"

#include <tlhelp32.h>
#include <psapi.h>

#include <algorithm>
#include <cwctype>

namespace loader {

namespace {
    bool ci_equals(const std::wstring& a, const wchar_t* b) {
        std::wstring lower(a);
        std::transform(lower.begin(), lower.end(), lower.begin(),
                       [](wchar_t c) { return (wchar_t)std::towlower(c); });
        return lower == b;
    }

    std::wstring read_command_line(HANDLE process) {
        // Reading the full command line via NtQueryInformationProcess / PEB is
        // architecture-sensitive and not worth the complexity here. We surface
        // the executable's full path as a stand-in - users can identify the
        // Minecraft instance from PID + working directory if needed.
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
