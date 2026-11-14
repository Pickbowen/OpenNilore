#include "openzen.h"
#include "hwid_hook.h"
#include "hwid_wmi.h"
#include "hwid_wmi_vtbl.h"
#include "hwid_firmware.h"
#include "hwid_disk.h"
#include "hwid_net.h"
#include "spoof_values.h"

#include <atomic>
#include <cstring>

namespace openzen {
    HMODULE g_self_module = nullptr;
}

namespace {

std::atomic<bool> g_already_attached{false};

void report_install(const char* what, int code) {
    if (code > 0) openzen::log::info("%s active", what);
    else if (code == 0) openzen::log::info("%s armed, no importer yet", what);
    else openzen::log::info("%s unavailable", what);
}

// Which spoof surfaces to arm.
//
// The default is *only* WMI, and that is a deliberate result of reading the
// anticheat's string pool: it queries Win32_ComputerSystemProduct.UUID,
// Win32_Processor.ProcessorId, Win32_DiskDrive.SerialNumber,
// Win32_BaseBoard.{Manufacturer,Product,SerialNumber,Version} and the registry
// value HARDWARE\DESCRIPTION\System\CentralProcessor\0\ProcessorNameString.
// Everything else it could read is not read, so hooking it only adds import
// slots for a self-check to find.
//
//   wmi     - UUID / DiskDrive.SerialNumber / BaseBoard.SerialNumber, via the
//             wbemcomn vtable patch (no IAT touched)
//   wmipx   - the old IAT + COM proxy path; off, it is the visible one
//   reg     - MachineGuid / ProductId / ComputerName. The anticheat does not
//             read these, so this is off by default; enable it only if a
//             different detection layer turns out to use them.
//   smbios  - raw SMBIOS table; not read by this anticheat
//   disk    - ATA IDENTIFY / storage descriptor; not read by this anticheat
//   net     - adapter MACs; not read by this anticheat, and it touches
//             iphlpapi which the anticheat imports
//
// Note what is NOT spoofed anywhere: Win32_Processor.ProcessorId and
// ProcessorNameString. Both derive from CPUID, which the scanner can execute
// itself, so faking them manufactures a contradiction instead of hiding one.
//
// Override with OPENZEN_HWID_SURFACES="wmi" (or "all", "none", ...) to A/B
// which surface a given server reacts to.
bool surface_enabled(const char* name) {
    char buf[256] = {0};
    DWORD n = GetEnvironmentVariableA("OPENZEN_HWID_SURFACES", buf, sizeof buf - 1);
    if (n == 0 || n >= sizeof buf) {
        return _stricmp(name, "wmi") == 0;
    }
    if (_stricmp(buf, "all") == 0) return true;
    if (_stricmp(buf, "none") == 0) return false;

    const char* p = buf;
    while (*p) {
        while (*p == ' ' || *p == ',') ++p;
        const char* start = p;
        while (*p && *p != ',' && *p != ' ') ++p;
        const size_t len = static_cast<size_t>(p - start);
        if (len && len == std::strlen(name) && _strnicmp(start, name, len) == 0) return true;
    }
    return false;
}

void install_hwid_surfaces() {
    // Read the real fingerprints BEFORE any hook exists. The fake set is
    // derived from them deterministically, and once hwid_hook / hwid_firmware
    // are armed these same calls would report the fake.
    hwid::real();
    openzen::log::info("HWID real fingerprint collected (smbios=%d disk=%d)",
                       hwid::real().smbios_ok ? 1 : 0, hwid::real().disk_ok ? 1 : 0);

    if (surface_enabled("wmi")) {
        // vtable patch first: no import table is touched, so an anticheat that
        // diffs its own IAT sees nothing.
        report_install("HWID WMI spoof (vtable)", hwid::install_wmi_vtbl());
        if (!hwid::wmi_vtbl_active()) {
            // Fallback only. This path patches IAT slots and hands out proxy
            // objects, which is exactly what the vtable patch exists to avoid.
            report_install("HWID WMI spoof (com proxy fallback)", hwid::install_wmi());
        }
    }
    if (surface_enabled("reg")) {
        report_install("HWID registry spoof", hwid::install());
    }
    if (surface_enabled("smbios")) {
        report_install("HWID SMBIOS spoof", hwid::install_firmware());
    }
    if (surface_enabled("disk")) {
        report_install("HWID disk spoof", hwid::install_disk());
    }
    if (surface_enabled("net")) {
        report_install("HWID MAC spoof", hwid::install_net());
    }
}

// Early-mode risk: the loader injects the moment a Minecraft window appears,
// but at that point Forge's game class loader may not have defined
// net.minecraft.client.Minecraft yet (6148 classes loaded vs ~38500 at full
// MC launch). find_game_class_loader() then fails and the client stays dead -
// even though the DLL made it in fine (g_already_attached blocks a retry).
//
// Instead of hard-waiting N seconds (which the user rejected), the bootstrap
// thread POLLS: it retries find_game_class_loader() every kPollMs until the
// class shows up or the deadline passes. The game keeps loading normally, the
// DLL bootstrap just sits on a background thread. On persistent failure we
// clear the idempotence flag so a later inject attempt can retry.
constexpr int      kMaxAttempts     = 60;    // 60 * 500ms = 30s budget
// Early Mode maps the DLL in before the process resumes, so the JVM can be a
// long way off (launcher stub -> JVM start -> Forge). 120s is a generous cap;
// hitting it only means the bootstrap gives up quietly.
constexpr unsigned kJvmWaitMs       = 120000;
constexpr int      kPollMs          = 500;
constexpr DWORD    kPollCapMs       = 5000;  // back-off cap between attempts

DWORD WINAPI inject_thread(LPVOID) {
    using namespace openzen;

    log::init();
    log::info("OpenZen.dll bootstrap thread started, pid=%lu", GetCurrentProcessId());

    // HWID spoofing is deliberately NOT armed at this point.
    //
    // Early Mode maps this DLL into the process before it has finished
    // starting. The old installs (IAT patches on advapi32/kernel32/iphlpapi)
    // were pure memory writes and were safe there. install_wmi_vtbl() is not:
    // it calls CoInitializeEx and CoCreateInstance(CLSID_WbemLocator), which
    // pulls up COM and its dependency chain in a process whose loader state is
    // not settled yet. That faulted with 0xc0000005 at an address inside no
    // mapped module ("faulting module: unknown"), immediately after the
    // "real fingerprint collected" line.
    //
    // The spoof is armed below instead, as soon as the JVM is up: by then the
    // process is fully initialised, and the WBEM vtable patch is process-wide
    // anyway, so it does not matter that the anticheat may not have loaded yet.

    // Early Mode puts us inside the process before the JVM exists, so wait for
    // it instead of bailing out. nullptr after the deadline = the game never
    // came up; leaving the process untouched is the whole point.
    JavaVM* vm = jvm::wait_for_vm(kJvmWaitMs);
    if (!vm) {
        log::error("No JavaVM after %u ms - bootstrap gives up", kJvmWaitMs);
        return 1;
    }

    // Safe point: the JVM is live, the loader is idle, COM can be initialised.
    // Every install is idempotent, so a later retry pass is harmless.
    install_hwid_surfaces();

    JNIEnv* env = nullptr;
    JavaVMAttachArgs args{};
    args.version = JNI_VERSION_1_8;
    args.name = const_cast<char*>("OpenZen-Bootstrap");
    args.group = nullptr;
    if (vm->AttachCurrentThreadAsDaemon((void**)&env, &args) != JNI_OK || !env) {
        log::error("AttachCurrentThreadAsDaemon failed");
        return 2;
    }
    log::info("Attached bootstrap thread to JavaVM");

    std::wstring jar_path;
    if (!jar::extract_embedded(jar_path)) {
        vm->DetachCurrentThread();
        return 3;
    }

    // Poll until the Minecraft class loader is ready (or deadline).
    jobject game_loader = nullptr;
    for (int attempt = 0; attempt < kMaxAttempts; ++attempt) {
        game_loader = classes::find_game_class_loader(vm, env);
        if (game_loader) break;
        if (attempt + 1 < kMaxAttempts) {
            // Back off: fast at first (game usually loads fast), capped.
            DWORD wait = kPollMs * (attempt + 1);
            if (wait > kPollCapMs) wait = kPollCapMs;
            log::info("Minecraft class not ready, retrying in %lu ms (attempt %d/%d)",
                      wait, attempt + 1, kMaxAttempts);
            Sleep(wait);
        }
    }

    if (!game_loader) {
        log::error("Minecraft class never became available within deadline; "
                   "clearing idempotence so a later inject can retry");
        g_already_attached.store(false);
        vm->DetachCurrentThread();
        return 4;
    }

    jint rc = jvm::attach_instrument(vm, jar_path);
    if (rc != 0) {
        log::error("Agent_OnAttach reported error %d", (int)rc);
        // Continue anyway - some JDK builds report non-zero even on success
        // because of secondary cleanup; PatchAgent.agentmain may still have run.
    }

    // Second HWID pass. The first one ran as soon as the JVM existed, which is
    // still early for COM: if ole32 was not up, the WMI install deferred itself
    // (see raw_co_create). By now the game has a window and the toolkit has
    // initialised COM, so this pass usually succeeds where the first deferred.
    // Every install is idempotent.
    install_hwid_surfaces();

    jclass bridge_cls = classes::load_dll_bootstrap(env, game_loader, jar_path);
    if (!bridge_cls) {
        env->DeleteLocalRef(game_loader);
        vm->DetachCurrentThread();
        return 5;
    }

    jmethodID load_mid = env->GetStaticMethodID(bridge_cls, "load",
            "(Ljava/lang/String;Ljava/lang/ClassLoader;)V");
    if (!load_mid) {
        log::error("GameLoaderBridge.load(String, ClassLoader) method not found");
        env->ExceptionClear();
        vm->DetachCurrentThread();
        return 6;
    }

    jstring jar_jstr = env->NewString(
        reinterpret_cast<const jchar*>(jar_path.c_str()),
        static_cast<jsize>(jar_path.size()));

    env->CallStaticVoidMethod(bridge_cls, load_mid, jar_jstr, game_loader);
    if (env->ExceptionCheck()) {
        log::error("GameLoaderBridge.load threw an exception");
        env->ExceptionDescribe();
        env->ExceptionClear();
    } else {
        log::info("GameLoaderBridge.load returned without exception");
    }

    env->DeleteLocalRef(jar_jstr);
    env->DeleteLocalRef(bridge_cls);
    env->DeleteLocalRef(game_loader);
    vm->DetachCurrentThread();
    return 0;
}

} // namespace

BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        // Idempotence: if the loader injects twice (or the host calls
        // LoadLibrary twice from different threads) we still only kick off the
        // bootstrap once.
        bool expected = false;
        if (!g_already_attached.compare_exchange_strong(expected, true)) {
            return TRUE;
        }
        openzen::g_self_module = module;
        DisableThreadLibraryCalls(module);
        // Never call JNI from inside DllMain - the loader lock is held. Kick
        // a separate worker thread that will do all the heavy lifting.
        HANDLE t = CreateThread(nullptr, 0, inject_thread, nullptr, 0, nullptr);
        if (t) CloseHandle(t);
    }
    return TRUE;
}
