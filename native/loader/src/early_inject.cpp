#include "early_inject.h"

#include "loader.h"
#include "manual_map.h"

#include <stdio.h>

#include <string>
#include <vector>

namespace loader {

namespace {

std::wstring format_error(const wchar_t* what, unsigned long code) {
    wchar_t buf[256];
    _snwprintf_s(buf, _TRUNCATE, L"%ls (error %lu)", what, code);
    return std::wstring(buf);
}

// Full image path of the instance we are about to relaunch. Kept separate from
// the command line because CreateProcessW wants them as two arguments.
std::wstring image_path_of(DWORD pid) {
    HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_VM_READ,
                                 FALSE, pid);
    if (!process) {
        return std::wstring();
    }
    wchar_t buf[MAX_PATH * 2] = {0};
    DWORD size = (DWORD)(sizeof buf / sizeof buf[0]);
    std::wstring out;
    if (QueryFullProcessImageNameW(process, 0, buf, &size)) {
        out.assign(buf, size);
    }
    CloseHandle(process);
    return out;
}

} // namespace

std::wstring inject_early(DWORD existing_pid, const std::wstring& command_line,
                          DWORD& out_new_pid) {
    if (existing_pid == 0 || command_line.empty()) {
        return L"Early Mode: no instance command line to relaunch from";
    }

    std::wstring image = image_path_of(existing_pid);
    if (image.empty()) {
        return format_error(L"Early Mode: QueryFullProcessImageName", GetLastError());
    }

    // The game's working directory is part of how it finds its own files; read
    // it off the running instance rather than guessing next to the exe. PROCESS_VM_READ
    // is required for the PEB read - with only QUERY_LIMITED_INFORMATION this
    // silently came back empty and the child inherited the LOADER's directory,
    // which is fatal for launchers that pass a relative --gameDir.
    std::wstring cwd;
    std::vector<wchar_t> env;
    {
        HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_VM_READ,
                                     FALSE, existing_pid);
        if (process) {
            cwd = read_working_directory(process);
            env = read_environment_block(process);
            CloseHandle(process);
        }
    }
    if (cwd.empty()) {
        // Relaunching without it gives a game sitting in the wrong directory: it
        // starts and then dies. Keep the instance the user has instead.
        return L"Early Mode: could not read the instance working directory "
               L"(process may be protected); not relaunching into a wrong one";
    }

    const void* dll_data = nullptr;
    size_t dll_size = 0;
    if (!get_embedded_dll(dll_data, dll_size)) {
        return L"Embedded OpenNilore.dll resource not found in loader EXE";
    }

    // CreateProcessW may write into the command line buffer, so hand it a copy.
    std::vector<wchar_t> cmd(command_line.begin(), command_line.end());
    cmd.push_back(L'\0');

    STARTUPINFOW si{};
    si.cb = sizeof si;
    PROCESS_INFORMATION pi{};

    // Hand over the instance's own environment block so the relaunch is a clone
    // of what the launcher started, not "whatever the loader happens to have".
    // Falls back to inheriting ours when the block was not readable.
    if (!CreateProcessW(image.c_str(), cmd.data(), nullptr, nullptr, FALSE,
                        CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT,
                        env.empty() ? nullptr : env.data(),
                        cwd.c_str(), &si, &pi)) {
        return format_error(L"Early Mode: CreateProcess(CREATE_SUSPENDED)",
                            GetLastError());
    }

    // The child is frozen: no user code of its own has run, so the DLL gets a
    // clean process to set up in. Manual map keeps us away from the loader lock.
    std::wstring err = inject_in_memory(pi.dwProcessId, dll_data, dll_size);
    if (!err.empty()) {
        // Never resume a half-initialised process, and leave the instance the
        // user already had running untouched.
        TerminateProcess(pi.hProcess, 1);
        CloseHandle(pi.hThread);
        CloseHandle(pi.hProcess);
        out_new_pid = 0;
        return L"Early Mode: inject into suspended process failed: " + err;
    }

    // Injection is in. Now it is safe to retire the old instance (overlapping
    // logins are worse than a moment of downtime) and let the new one go.
    HANDLE old_process = OpenProcess(PROCESS_TERMINATE, FALSE, existing_pid);
    if (old_process) {
        TerminateProcess(old_process, 0);
        CloseHandle(old_process);
    }

    if (ResumeThread(pi.hThread) == (DWORD)-1) {
        // The DLL is mapped but the process is stuck suspended: still better to
        // hand back the pid so the caller can report what happened.
        DWORD code = GetLastError();
        CloseHandle(pi.hThread);
        CloseHandle(pi.hProcess);
        out_new_pid = pi.dwProcessId;
        return format_error(L"Early Mode: ResumeThread", code);
    }

    out_new_pid = pi.dwProcessId;
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return std::wstring();
}

} // namespace loader
