#include "openzen.h"
#include "hwid_hook.h"
#include "hwid_wmi.h"

#include <atomic>

namespace openzen {
    HMODULE g_self_module = nullptr;
}

namespace {

std::atomic<bool> g_already_attached{false};

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
constexpr int      kPollMs          = 500;
constexpr DWORD    kPollCapMs       = 5000;  // back-off cap between attempts

DWORD WINAPI inject_thread(LPVOID) {
    using namespace openzen;

    log::init();
    log::info("OpenZen.dll bootstrap thread started, pid=%lu", GetCurrentProcessId());

    // HWID spoof (IAT-level) is installed as early as possible, before the
    // JVM attaches - so any later registry reads from Minecraft/Forge see the
    // fake values. If no module imports RegQueryValueExW, install() returns
    // false and the spoof stays OFF (per the user's "default OFF" rule).
    // NEVER logs on failure; silent = disabled.
    if (hwid::install()) {
        log::info("HWID spoof active (%ld IAT slots patched)", (long)hwid::g_patch_count);
    } else {
        log::info("HWID spoof not installed (no importers) - disabled");
    }
    if (hwid::install_wmi()) {
        log::info("HWID WMI spoof active (CoCreateInstance patched)");
    } else {
        log::info("HWID WMI spoof not installed - disabled");
    }

    JavaVM* vm = jvm::find_vm();
    if (!vm) return 1;

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
