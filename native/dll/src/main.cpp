#include "openzen.h"
#include "hwid_config.h"
#include "hwid_hook.h"
#include "hwid_wmi.h"
#include "hwid_wmi_vtbl.h"
#include "hwid_firmware.h"
#include "hwid_disk.h"
#include "hwid_net.h"
#include "jni_probe.h"
#include "nt_guard.h"
#include "spoof_values.h"

#include <atomic>
#include <cstdio>
#include <cstring>
#include <ctime>

namespace openzen {
    HMODULE g_self_module = nullptr;
}

namespace {

std::atomic<bool> g_already_attached{false};

void report_install(const char* what, int code) {
    if (code > 0) openzen::log::info("  %-32s active", what);
    else if (code == 0) openzen::log::info("  %-32s armed, no importer yet", what);
    else openzen::log::info("  %-32s unavailable", what);
}

// The vtable installer reports its own status codes, and a partial or
// self-check-vetoed install is NOT "active": the bitmap of "which slots are
// live" is the whole point of having the codes.
void report_wmi_vtbl(int status) {
    switch (status) {
        case hwid::kWmiVtblFull:
            openzen::log::info("  %-32s active (all slots + self check)",
                               "WMI vtable (UUID/disk/board)");
            break;
        case hwid::kWmiVtblPartial:
            openzen::log::info("  %-32s PARTIAL - see the WMI vtbl lines above",
                               "WMI vtable (UUID/disk/board)");
            break;
        case hwid::kWmiVtblNothing:
            openzen::log::info("  %-32s armed, provider not reachable yet",
                               "WMI vtable (UUID/disk/board)");
            break;
        default:
            openzen::log::info("  %-32s unavailable",
                               "WMI vtable (UUID/disk/board)");
            break;
    }
}

// Log the exact values this process will now report, once, at startup. This is
// the fastest way to answer "is the spoof doing anything" without a debugger.
void log_profile() {
    const hwid::SpoofValues& v = hwid::values();
    openzen::log::info("HWID profile (what this process now reports):");
    openzen::log::info("  product_uuid      = %s", hwid::narrow_ascii(v.product_uuid).c_str());
    openzen::log::info("  system_serial     = %s", hwid::narrow_ascii(v.system_serial).c_str());
    openzen::log::info("  baseboard_serial  = %s", hwid::narrow_ascii(v.baseboard_serial).c_str());
    openzen::log::info("  disk_serial       = %s", hwid::narrow_ascii(v.disk_serial).c_str());
    openzen::log::info("  machine_guid      = %s", hwid::narrow_ascii(v.machine_guid).c_str());
    openzen::log::info("  processor_id      = %s (verbatim: CPUID)",
                       hwid::narrow_ascii(v.processor_id).c_str());
}

// Which build is actually running, as one token. The loader embeds this DLL,
// so "I rebuilt it" and "the game ran the rebuilt one" are different facts and
// the log has to be able to tell them apart.
const char* self_build_stamp() {
    static char buf[40];
    static bool done = false;
    if (done) return buf;
    done = true;

    HMODULE self = openzen::g_self_module;
    if (!self) self = GetModuleHandleW(nullptr);
    auto* dos = reinterpret_cast<IMAGE_DOS_HEADER*>(self);
    if (!self || dos->e_magic != IMAGE_DOS_SIGNATURE) {
        std::snprintf(buf, sizeof buf, "unknown");
        return buf;
    }
    auto* nt = reinterpret_cast<IMAGE_NT_HEADERS*>(
        reinterpret_cast<BYTE*>(self) + dos->e_lfanew);
    const DWORD stamp = nt->FileHeader.TimeDateStamp;
    std::tm tm{};
    const time_t tt = static_cast<time_t>(stamp);
    gmtime_s(&tm, &tt);
    std::snprintf(buf, sizeof buf, "%04d-%02d-%02d.%02d%02dZ",
                  tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday, tm.tm_hour, tm.tm_min);
    return buf;
}

void install_hwid_surfaces() {
    const hwid::Config& cfg = hwid::config();

    // One line that makes the log self-explanatory: which build, which file,
    // what it parsed to, and whether an environment variable overrode it.
    static std::atomic<bool> config_logged{false};
    if (!config_logged.exchange(true)) {
        openzen::log::info("OpenZen build %s | HWID enabled=%d surfaces=%ls seed=0x%llX",
                           self_build_stamp(), cfg.enabled ? 1 : 0, cfg.surfaces.c_str(),
                           static_cast<unsigned long long>(cfg.seed));
    }

    if (!cfg.enabled) {   // constant today; kept so the branch cannot be lost
        openzen::log::info("HWID spoof disabled by configuration");
        return;
    }

    // Read the real fingerprints BEFORE any hook exists: the fake set is
    // derived from them deterministically, and once the hooks are armed these
    // same calls would report the fake.
    hwid::real();
    openzen::log::info("HWID real fingerprint collected (smbios=%d disk=%d)",
                       hwid::real().smbios_ok ? 1 : 0, hwid::real().disk_ok ? 1 : 0);

    static std::atomic<bool> profile_logged{false};
    if (!profile_logged.exchange(true)) log_profile();

    if (hwid::surface_on("wmi")) {
        // vtable patch first: no import table is touched, so an anticheat that
        // diffs its own IAT sees nothing.
        //
        // One silent retry, 300 ms later. COM is only usable once the JVM (and
        // the toolkit inside it) has brought combase up; the first attempt can
        // land just before that. Two attempts total, capped inside the
        // installer as well - the probe is the one thing here that has crashed
        // this process before, so it does not get to loop.
        int vtbl = hwid::install_wmi_vtbl();
        if (vtbl != hwid::kWmiVtblFull) {
            Sleep(300);
            vtbl = hwid::install_wmi_vtbl(true);
        }
        report_wmi_vtbl(vtbl);
        if (!hwid::wmi_vtbl_active() && hwid::surface_on("wmipx")) {
            // Only on request: this path patches IAT slots and hands out proxy
            // objects, which is exactly what the vtable patch exists to avoid.
            report_install("WMI COM proxy (legacy)", hwid::install_wmi());
        }
    }
    if (hwid::surface_on("net")) {
        report_install("MAC (GetAdaptersAddresses)", hwid::install_net());
    }
    if (hwid::surface_on("reg")) {
        report_install("registry (legacy)", hwid::install());
    }
    if (hwid::surface_on("smbios")) {
        report_install("SMBIOS table (legacy)", hwid::install_firmware());
    }
    if (hwid::surface_on("disk")) {
        report_install("disk IOCTL (legacy)", hwid::install_disk());
    }

    // One line that answers "is the spoof up" without reading the rest of the
    // log, and says exactly which surfaces are live.
    openzen::log::info("HWID spoof armed: surfaces=%ls",
                       hwid::config().surfaces.c_str());
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

    // Read-only recon of the JNI boundary, and the first thing that runs after
    // we are attached: an anticheat that watches native -> Java traffic plants
    // its tripwire in these two dispatch tables, and the answer decides whether
    // our own JNI calls are safe to make at all. It patches nothing.
    jnipro::install(vm);

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

    // Native guard, from here on for the rest of the session:
    //   - this image is unlinked from the PEB module lists
    //   - NtQueryVirtualMemory reports its pages as MEM_FREE
    //   - ntdll-resident relays (see nt_guard.h) keep hook destinations inside
    //     a signed Microsoft image instead of this DLL
    //   - the watchdog thread re-seats any byte an integrity scan restored
    // It runs after every HWID surface is armed and before the game jar loads,
    // which is the first moment an anticheat can be looking at this process.
    // Relay mode: the one session that got through ran with the image hidden and
    // its threads behind ntdll relays, so that posture is kept. The trade is
    // known: first hops and trampolines live inside ntdll, which a byte hash
    // can see. Hiding wins over "no modification" on the evidence available.
    report_install("native guard (ntdll relay+watchdog)", ntguard::install(true) ? 1 : 0);

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

// The thread DllMain creates necessarily starts inside this DLL - that is what
// CreateThread does with the pointer it is given. It is also the most visible
// object this client owns: a thread whose start address belongs to no mapped
// image, enumerable with one NtQueryInformationThread for as long as it runs.
//
// This launcher exists for a few milliseconds. It hands the real work to a
// thread whose entry is an ntdll relay (see ntguard::spawn_hidden), then exits,
// so the long-lived bootstrap thread answers "which module started you?" with
// ntdll instead of this DLL.
DWORD WINAPI bootstrap_launcher(LPVOID) {
    openzen::log::init();

    HANDLE t = ntguard::spawn_hidden(inject_thread, nullptr);
    if (t) {
        CloseHandle(t);
        return 0;
    }
    // No usable ntdll cave: run the bootstrap on this thread rather than
    // leaving the client unloaded.
    return inject_thread(nullptr);
}

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
        HANDLE t = CreateThread(nullptr, 0, bootstrap_launcher, nullptr, 0, nullptr);
        if (t) CloseHandle(t);
    }
    return TRUE;
}
