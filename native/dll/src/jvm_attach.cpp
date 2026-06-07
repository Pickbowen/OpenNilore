#include "openzen.h"

#include <vector>

namespace openzen::jvm {

namespace {
    // Signature of Agent_OnAttach exported by the JDK's instrument.dll.
    using Agent_OnAttach_t = jint (JNICALL*)(JavaVM* vm, char* options, void* reserved);

    bool find_instrument_dll(std::wstring& out_path) {
        // The JDK puts instrument.dll next to jvm.dll / java.dll. We locate
        // java.dll (loaded by the running JVM) and rewrite the filename.
        HMODULE javaDll = GetModuleHandleW(L"java.dll");
        if (!javaDll) {
            log::error("java.dll not loaded in current process");
            return false;
        }
        wchar_t path[MAX_PATH];
        DWORD n = GetModuleFileNameW(javaDll, path, MAX_PATH);
        if (n == 0 || n >= MAX_PATH) {
            log::error("GetModuleFileName(java.dll) failed: %lu", GetLastError());
            return false;
        }
        // Walk back to last backslash and append instrument.dll.
        wchar_t* slash = wcsrchr(path, L'\\');
        if (!slash) {
            log::error("Unexpected java.dll path: %ls", path);
            return false;
        }
        slash[1] = L'\0';
        out_path.assign(path);
        out_path.append(L"instrument.dll");
        return true;
    }
}

JavaVM* find_vm() {
    // We are injected into a process that already loaded jvm.dll. We resolve
    // JNI_GetCreatedJavaVMs at runtime so the DLL does not have to link
    // against a specific JDK's jvm.lib at build time.
    HMODULE jvm_dll = GetModuleHandleW(L"jvm.dll");
    if (!jvm_dll) {
        log::error("jvm.dll is not loaded in the current process");
        return nullptr;
    }
    using JNI_GetCreatedJavaVMs_t = jint (JNICALL*)(JavaVM**, jsize, jsize*);
    auto fn = reinterpret_cast<JNI_GetCreatedJavaVMs_t>(
        GetProcAddress(jvm_dll, "JNI_GetCreatedJavaVMs"));
    if (!fn) {
        log::error("GetProcAddress(JNI_GetCreatedJavaVMs) failed: %lu", GetLastError());
        return nullptr;
    }
    JavaVM* vm = nullptr;
    jsize count = 0;
    jint rc = fn(&vm, 1, &count);
    if (rc != JNI_OK || count < 1 || !vm) {
        log::error("JNI_GetCreatedJavaVMs rc=%d count=%d", (int)rc, (int)count);
        return nullptr;
    }
    return vm;
}

jint attach_instrument(JavaVM* vm, const std::wstring& jar_path) {
    std::wstring instrument_path;
    if (!find_instrument_dll(instrument_path)) {
        return -1;
    }
    log::info("Loading %ls", instrument_path.c_str());

    HMODULE inst = LoadLibraryW(instrument_path.c_str());
    if (!inst) {
        log::error("LoadLibrary instrument.dll failed: %lu", GetLastError());
        return -1;
    }

    auto fn = reinterpret_cast<Agent_OnAttach_t>(GetProcAddress(inst, "Agent_OnAttach"));
    if (!fn) {
        log::error("GetProcAddress(Agent_OnAttach) failed: %lu", GetLastError());
        return -1;
    }

    // OpenJDK's instrument.dll parses the options string using parseArgumentTail:
    // the tail starts with the jar path (system encoding), optionally followed
    // by '=' and additional agent args. We pass only the jar path.
    int needed = WideCharToMultiByte(CP_ACP, 0, jar_path.c_str(), -1,
                                      nullptr, 0, nullptr, nullptr);
    if (needed <= 0) {
        log::error("WideCharToMultiByte sizing failed: %lu", GetLastError());
        return -1;
    }
    std::vector<char> options(needed);
    WideCharToMultiByte(CP_ACP, 0, jar_path.c_str(), -1,
                         options.data(), needed, nullptr, nullptr);

    log::info("Calling Agent_OnAttach with options=%s", options.data());
    jint rc = fn(vm, options.data(), nullptr);
    log::info("Agent_OnAttach returned %d", (int)rc);
    return rc;
}

} // namespace openzen::jvm
