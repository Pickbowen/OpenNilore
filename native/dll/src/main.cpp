#include "openzen.h"

#include <atomic>
#include <cstdint>
#include <cstring>
#include <vector>

namespace openzen {
    HMODULE g_self_module = nullptr;
}

namespace {

std::atomic<bool> g_already_attached{false};

// ============================================================
// MaxHook Bypass
// ============================================================

struct MaxHookBypass {
    HMODULE module = nullptr;
    uint8_t* base = nullptr;
    uint32_t size = 0;
    uint8_t* dataBase = nullptr;
    uint32_t dataSize = 0;
    uint8_t* textBase = nullptr;
    uint32_t textSize = 0;
};

// Parse PE sections to find .data and .text
static bool parseSections(MaxHookBypass& ctx) {
    auto* dos = reinterpret_cast<IMAGE_DOS_HEADER*>(ctx.base);
    if (dos->e_magic != IMAGE_DOS_SIGNATURE) return false;

    auto* nt = reinterpret_cast<IMAGE_NT_HEADERS64*>(ctx.base + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE) return false;

    auto* section = IMAGE_FIRST_SECTION(nt);
    for (WORD i = 0; i < nt->FileHeader.NumberOfSections; i++, section++) {
        char name[9] = {};
        memcpy(name, section->Name, 8);

        if (strcmp(name, ".data") == 0) {
            ctx.dataBase = ctx.base + section->VirtualAddress;
            ctx.dataSize = section->Misc.VirtualSize;
        } else if (strcmp(name, ".text") == 0) {
            ctx.textBase = ctx.base + section->VirtualAddress;
            ctx.textSize = section->Misc.VirtualSize;
        }
    }
    return ctx.dataBase && ctx.textBase;
}

// Scan .data for g_initialized (a BOOL/int that starts as TRUE)
// Heuristic: look for a writable dword that is 1, surrounded by nulls
static uint8_t* findGInitialized(MaxHookBypass& ctx) {
    if (!ctx.dataBase || ctx.dataSize == 0) return nullptr;

    for (uint32_t off = 0; off + 4 <= ctx.dataSize; off += 4) {
        auto* ptr = ctx.dataBase + off;
        uint32_t val = *reinterpret_cast<uint32_t*>(ptr);

        // g_initialized is typically set to 1 during DllMain
        if (val != 1) continue;

        // Check surrounding bytes for null padding (heuristic)
        bool nullBefore = (off >= 4) ? (*reinterpret_cast<uint32_t*>(ptr - 4) == 0) : true;
        bool nullAfter = (off + 8 <= ctx.dataSize) ? (*reinterpret_cast<uint32_t*>(ptr + 4) == 0) : true;

        if (nullBefore && nullAfter) {
            return ptr;
        }
    }
    return nullptr;
}

// Kill the collector thread by setting g_initialized = 0
static bool killCollector(MaxHookBypass& ctx) {
    uint8_t* gInit = findGInitialized(ctx);
    if (!gInit) {
        log::info("g_initialized not found in MaxHook .data");
        return false;
    }

    log::info("g_initialized found at %p", gInit);

    DWORD oldProtect;
    if (!VirtualProtect(gInit, 4, PAGE_READWRITE, &oldProtect)) {
        log::error("VirtualProtect failed: %lu", GetLastError());
        return false;
    }

    *reinterpret_cast<uint32_t*>(gInit) = 0;
    VirtualProtect(gInit, 4, oldProtect, &oldProtect);

    log::info("g_initialized set to 0, collector thread should exit");
    return true;
}

// Scan .text for: mov [rip+disp], rax (48 89 05 xx xx xx xx)
// This pattern is used to store the original JNI function table pointer
static std::vector<uint8_t*> findMovRipRax(MaxHookBypass& ctx) {
    std::vector<uint8_t*> results;
    if (!ctx.textBase || ctx.textSize < 7) return results;

    for (uint32_t i = 0; i + 7 <= ctx.textSize; i++) {
        uint8_t* p = ctx.textBase + i;
        // 48 89 05 disp32 = mov [rip+disp32], rax
        if (p[0] == 0x48 && p[1] == 0x89 && p[2] == 0x05) {
            results.push_back(p);
        }
    }
    return results;
}

// Restore original JNI function table
static bool restoreJNITable(JNIEnv* env, MaxHookBypass& ctx) {
    // Find the JNIEnv* stored in MaxHook's .data
    // It's a pointer that points to the JNI function table
    JNIEnv** envPtrInData = nullptr;

    if (!ctx.dataBase || ctx.dataSize == 0) {
        log::error("MaxHook .data section not found");
        return false;
    }

    // Scan .data for a pointer that looks like JNIEnv*
    for (uint32_t off = 0; off + sizeof(void*) <= ctx.dataSize; off += sizeof(void*)) {
        auto** ptr = reinterpret_cast<JNIEnv**>(ctx.dataBase + off);
        if (*ptr == env) {
            envPtrInData = ptr;
            log::info("JNIEnv* found in .data at %p", envPtrInData);
            break;
        }
    }

    if (!envPtrInData) {
        log::info("JNIEnv* not found in .data, trying direct approach");
    }

    // Scan .text for mov [rip+disp], rax to find g_origJNI
    auto movInstructions = findMovRipRax(ctx);
    log::info("Found %zu mov [rip+disp], rax instructions", movInstructions.size());

    JNINativeInterface_* origJNI = nullptr;

    for (uint8_t* instr : movInstructions) {
        // Calculate RIP-relative address: addr = instr + 7 + disp32
        int32_t disp = *reinterpret_cast<int32_t*>(instr + 3);
        uint8_t* target = instr + 7 + disp;

        // Check if target is in .data section
        if (target >= ctx.dataBase && target < ctx.dataBase + ctx.dataSize) {
            // Read the pointer stored at target
            auto* stored = *reinterpret_cast<JNINativeInterface_***>(target);

            // Verify it looks like a valid JNI function table
            if (stored && IsBadReadPtr(stored, sizeof(void*)) == 0) {
                // Check if it has reasonable function pointers
                void* firstFn = reinterpret_cast<void**>(stored)[0];
                if (firstFn && !IsBadCodePtr((FARPROC)firstFn)) {
                    origJNI = reinterpret_cast<JNINativeInterface_*>(stored);
                    log::info("Found g_origJNI at %p, original table at %p", target, origJNI);
                    break;
                }
            }
        }
    }

    if (!origJNI) {
        log::error("Could not find original JNI function table");
        return false;
    }

    // Restore the original JNI function table
    DWORD oldProtect;
    auto* functionsPtr = reinterpret_cast<uint8_t*>(&env->functions);
    if (!VirtualProtect(functionsPtr, sizeof(void*), PAGE_READWRITE, &oldProtect)) {
        log::error("VirtualProtect failed for JNI restore: %lu", GetLastError());
        return false;
    }

    env->functions = origJNI;
    VirtualProtect(functionsPtr, sizeof(void*), oldProtect, &oldProtect);

    log::info("JNI function table restored to %p", origJNI);
    return true;
}

// Main bypass routine
static void bypassMaxHook(JavaVM* vm, JNIEnv* env) {
    MaxHookBypass ctx;

    // Find maxhook.dll
    ctx.module = GetModuleHandleW(L"maxhook.dll");
    if (!ctx.module) {
        ctx.module = GetModuleHandleW(L"MaxHook.dll");
    }
    if (!ctx.module) {
        log::info("maxhook.dll not loaded, skipping bypass");
        return;
    }

    log::info("maxhook.dll detected at %p", ctx.module);

    // Get module info
    MODULEINFO modInfo;
    if (!GetModuleInformation(GetCurrentProcess(), ctx.module, &modInfo, sizeof(modInfo))) {
        log::error("GetModuleInformation failed: %lu", GetLastError());
        return;
    }

    ctx.base = static_cast<uint8_t*>(modInfo.lpBaseOfDll);
    ctx.size = modInfo.SizeOfImage;

    // Parse PE sections
    if (!parseSections(ctx)) {
        log::error("Failed to parse MaxHook PE sections");
        return;
    }

    log::info("MaxHook .text: %p (size: 0x%X)", ctx.textBase, ctx.textSize);
    log::info("MaxHook .data: %p (size: 0x%X)", ctx.dataBase, ctx.dataSize);

    // Step 1: Kill collector thread
    killCollector(ctx);

    // Step 2: Restore JNI function table
    restoreJNITable(env, ctx);
}

// ============================================================
// Bootstrap
// ============================================================

DWORD WINAPI inject_thread(LPVOID) {
    using namespace openzen;

    log::init();
    log::info("OpenZen.dll bootstrap thread started, pid=%lu", GetCurrentProcessId());

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

    // Bypass MaxHook before doing anything else
    bypassMaxHook(vm, env);

    std::wstring jar_path;
    if (!jar::extract_embedded(jar_path)) {
        vm->DetachCurrentThread();
        return 3;
    }

    jint rc = jvm::attach_instrument(vm, jar_path);
    if (rc != 0) {
        log::error("Agent_OnAttach reported error %d", (int)rc);
    }

    jobject game_loader = classes::find_game_class_loader(vm, env);
    if (!game_loader) {
        vm->DetachCurrentThread();
        return 4;
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
        bool expected = false;
        if (!g_already_attached.compare_exchange_strong(expected, true)) {
            return TRUE;
        }
        openzen::g_self_module = module;
        DisableThreadLibraryCalls(module);
        HANDLE t = CreateThread(nullptr, 0, inject_thread, nullptr, 0, nullptr);
        if (t) CloseHandle(t);
    }
    return TRUE;
}
