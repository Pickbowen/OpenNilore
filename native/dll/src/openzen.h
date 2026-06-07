#pragma once

#include <windows.h>
#include <jni.h>
#include <jvmti.h>
#include <string>

namespace openzen {

extern HMODULE g_self_module;

namespace log {
    void init();
    void info(const char* fmt, ...);
    void error(const char* fmt, ...);
}

namespace jar {
    // Extract the IDR_ZEN_JAR resource embedded in OpenZen.dll into a temporary
    // file under %TEMP%. Returns the absolute path on success.
    bool extract_embedded(std::wstring& out_path);
}

namespace jvm {
    // Locate the running JavaVM in the current process. Returns nullptr if no
    // JVM is available (the DLL was injected into a non-Java process).
    JavaVM* find_vm();

    // Call Agent_OnAttach in the JDK's instrument.dll, pointing it at the given
    // agent jar. After this returns 0 the jar's Agent-Class entry point
    // (PatchAgent.agentmain) will have been invoked and the JDK's
    // InstrumentationImpl will be live.
    jint attach_instrument(JavaVM* vm, const std::wstring& jar_path);
}

namespace classes {
    // Walk loaded classes via JVMTI to find the class loader that defined
    // net.minecraft.client.Minecraft - the Forge GameClassLoader. Returns a
    // local JNI reference (caller manages lifetime).
    jobject find_game_class_loader(JavaVM* vm, JNIEnv* env);

    // Build URLClassLoader(jar, parent=gameLoader) and load DllBootstrap.
    // Returns a local JNI reference to the class.
    jclass load_dll_bootstrap(JNIEnv* env, jobject game_loader,
                              const std::wstring& jar_path);
}

} // namespace openzen
