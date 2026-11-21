#include "inject_support.h"

#include <tlhelp32.h>

#include <vector>

namespace loader {

namespace {

// NtCreateThreadEx, resolved lazily: the loader still starts on systems where a
// security product has renamed or hooked it, and a missing export must only cost
// the middle mechanism, not the whole injection.
using NtCreateThreadEx_t = LONG (NTAPI*)(
    PHANDLE, ACCESS_MASK, PVOID, HANDLE, PVOID, PVOID, ULONG,
    SIZE_T, SIZE_T, SIZE_T, PVOID);

NtCreateThreadEx_t nt_create_thread_ex() {
    static NtCreateThreadEx_t fn = []() -> NtCreateThreadEx_t {
        HMODULE ntdll = GetModuleHandleW(L"ntdll.dll");
        if (!ntdll) return nullptr;
        return reinterpret_cast<NtCreateThreadEx_t>(
            GetProcAddress(ntdll, "NtCreateThreadEx"));
    }();
    return fn;
}

// Every thread id belonging to `pid`, newest snapshot. Used by the APC fallback,
// which has to pick an existing thread to ride on.
std::vector<DWORD> threads_of(DWORD pid) {
    std::vector<DWORD> out;
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0);
    if (snap == INVALID_HANDLE_VALUE) return out;

    THREADENTRY32 te{};
    te.dwSize = sizeof(te);
    if (Thread32First(snap, &te)) {
        do {
            if (te.th32OwnerProcessID == pid) out.push_back(te.th32ThreadID);
        } while (Thread32Next(snap, &te));
    }
    CloseHandle(snap);
    return out;
}

bool wait_and_report(HANDLE thread, DWORD timeout_ms, DWORD* out_exit) {
    const DWORD waited = WaitForSingleObject(thread, timeout_ms);
    if (waited == WAIT_OBJECT_0) {
        DWORD code = 0;
        if (GetExitCodeThread(thread, &code)) *out_exit = code;
        return true;
    }
    // A hung remote thread still exists; the caller treats a timeout as failure
    // but the process is left alone (never terminated from here).
    return false;
}

} // namespace

bool enable_debug_privilege() {
    HANDLE token = nullptr;
    if (!OpenProcessToken(GetCurrentProcess(),
                          TOKEN_ADJUST_PRIVILEGES | TOKEN_QUERY, &token)) {
        return false;
    }

    LUID luid{};
    bool ok = false;
    // Literal instead of SE_DEBUG_NAME: that macro follows the UNICODE setting of
    // the translation unit, and this file is also compiled into small test
    // harnesses that do not define it - there the macro degrades to an ANSI
    // string and silently fails to match the W entry point.
    if (LookupPrivilegeValueW(nullptr, L"SeDebugPrivilege", &luid)) {
        TOKEN_PRIVILEGES tp{};
        tp.PrivilegeCount = 1;
        tp.Privileges[0].Luid = luid;
        tp.Privileges[0].Attributes = SE_PRIVILEGE_ENABLED;
        // AdjustTokenPrivileges reports success even when the privilege was not
        // actually granted, so GetLastError is the real answer.
        if (AdjustTokenPrivileges(token, FALSE, &tp, sizeof(tp), nullptr, nullptr)) {
            ok = (GetLastError() == ERROR_SUCCESS);
        }
    }
    CloseHandle(token);
    return ok;
}

bool loader_is_wow64() {
    BOOL wow = FALSE;
    if (!IsWow64Process(GetCurrentProcess(), &wow)) return false;
    return wow != FALSE;
}

bool target_bitness(HANDLE process, bool& is_x86) {
    is_x86 = false;
    if (!process) return false;

    // IsWow64Process2 separates "32-bit process" from "32-bit OS", which
    // IsWow64Process alone does not.
    using IsWow64Process2_t = BOOL (WINAPI*)(HANDLE, USHORT*, USHORT*);
    static auto fn2 = reinterpret_cast<IsWow64Process2_t>([]() -> void* {
        HMODULE k32 = GetModuleHandleW(L"kernel32.dll");
        return k32 ? reinterpret_cast<void*>(GetProcAddress(k32, "IsWow64Process2"))
                   : nullptr;
    }());

    if (fn2) {
        USHORT proc_machine = 0, native_machine = 0;
        if (fn2(process, &proc_machine, &native_machine)) {
            // IMAGE_FILE_MACHINE_UNKNOWN means "32-bit process on 32-bit OS".
            is_x86 = (proc_machine == IMAGE_FILE_MACHINE_I386) ||
                     (proc_machine == IMAGE_FILE_MACHINE_UNKNOWN &&
                      native_machine == IMAGE_FILE_MACHINE_I386);
            return true;
        }
    }

    BOOL wow = FALSE;
    if (!IsWow64Process(process, &wow)) return false;
    is_x86 = wow != FALSE;
    return true;
}

bool remote_call(HANDLE process, DWORD target_pid, void* fn, void* arg,
                 DWORD timeout_ms, DWORD* out_exit, DWORD* out_error) {
    if (out_exit) *out_exit = 0;
    DWORD last = 0;

    // ---- 1. CreateRemoteThread ---------------------------------------------
    HANDLE thread = CreateRemoteThread(process, nullptr, 0,
                                       reinterpret_cast<LPTHREAD_START_ROUTINE>(fn),
                                       arg, 0, nullptr);
    if (thread) {
        const bool done = wait_and_report(thread, timeout_ms, out_exit);
        CloseHandle(thread);
        if (done) return true;
        last = ERROR_TIMEOUT;
    } else {
        last = GetLastError();
    }

    // ---- 2. NtCreateThreadEx ------------------------------------------------
    if (NtCreateThreadEx_t ntc = nt_create_thread_ex()) {
        HANDLE remote = nullptr;
        const LONG st = ntc(&remote, THREAD_ALL_ACCESS, nullptr, process,
                                fn, arg, 0, 0, 0, 0, nullptr);
        if (st >= 0 && remote) {
            const bool done = wait_and_report(remote, timeout_ms, out_exit);
            CloseHandle(remote);
            if (done) return true;
            last = ERROR_TIMEOUT;
        } else if (st < 0) {
            // NTSTATUS values are already the most specific explanation of a
            // blocked thread creation, so keep them rather than the earlier
            // kernel32 error.
            last = static_cast<DWORD>(st);
        }
    }

    // ---- 3. QueueUserAPC ----------------------------------------------------
    //
    // No thread is created, so products that watch thread creation never see
    // anything. Delivery is asynchronous: the APC runs the next time a target
    // thread enters an alertable wait, which a JVM does routinely. Queuing to
    // every thread maximises the chance one of them is alertable soon.
    for (DWORD tid : threads_of(target_pid)) {
        HANDLE t = OpenThread(THREAD_SET_CONTEXT | THREAD_SUSPEND_RESUME, FALSE, tid);
        if (!t) continue;
        if (QueueUserAPC(reinterpret_cast<PAPCFUNC>(fn), t,
                         reinterpret_cast<ULONG_PTR>(arg))) {
            CloseHandle(t);
            if (out_exit) *out_exit = 0;
            if (out_error) *out_error = 0;
            return true;
        }
        CloseHandle(t);
        last = GetLastError();
    }

    if (out_error) *out_error = last ? last : ERROR_ACCESS_DENIED;
    return false;
}

std::wstring explain_win32(const wchar_t* where, DWORD err) {
    wchar_t sys[256] = {0};
    FormatMessageW(FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
                   nullptr, err, 0, sys, 256, nullptr);

    std::wstring head = where ? where : L"inject";
    head += L" failed (";
    head += std::to_wstring(err);
    head += L"): ";
    head += sys[0] ? sys : L"unknown error";

    // Strip the trailing CRLF FormatMessage leaves behind.
    while (!head.empty() && (head.back() == L'\n' || head.back() == L'\r' ||
                             head.back() == L' ')) {
        head.pop_back();
    }

    switch (err) {
    case ERROR_ACCESS_DENIED:              // 5
        head += L"\n  -> access denied: the game is likely running elevated while "
                L"the loader is not. Restart the loader as administrator; if it "
                L"already is, a security product is blocking remote processes "
                L"for this executable.";
        break;
    case ERROR_NOT_ENOUGH_MEMORY:          // 8
    case ERROR_OUTOFMEMORY:                // 14
        head += L"\n  -> the target cannot reserve the image. Close other "
                L"programs: a low-memory machine has to hold a second full copy "
                L"of the DLL image while it is built and written.";
        break;
    case ERROR_INVALID_PARAMETER:          // 87
        head += L"\n  -> invalid parameter. If the target is 32-bit Java, the "
                L"x64 payload can never map into it: install 64-bit Java.";
        break;
    case ERROR_PARTIAL_COPY:               // 299
    case ERROR_INVALID_ADDRESS:            // 487
        head += L"\n  -> reading or writing target memory was refused. The "
                L"process is protected (anticheat driver, PPL, or a security "
                L"product); Windows will not let another process touch it.";
        break;
    case ERROR_TIMEOUT:                    // 1460
        head += L"\n  -> the remote thread did not finish in time. On a very "
                L"slow machine raise the timeout; on a blocked one this is the "
                L"thread being suspended by a security product.";
        break;
    default:
        if (err >= 0xC0000000u) {
            head += L"\n  -> NTSTATUS from thread creation. 0xC0000022 is access "
                    L"denied, 0xC0000142 is DLL init failure, 0xC0000001 is an "
                    L"unsupported request: all three mean a security product or "
                    L"process protection stopped the thread.";
        }
        break;
    }
    return head;
}

} // namespace loader
