#pragma once

#include <jni.h>

// Read-only observer for the JNI boundary.
//
// A JNI-resident anticheat watches the calls a native module makes: one call
// arriving from a return address that belongs to no loaded image is the
// cheapest "there is an injected cheat in this process" signal there is. The
// dispatch tables are the natural place to plant that tripwire, because they
// are process-global and a single patch covers every thread:
//
//   JavaVM (JNIInvokeInterface) : AttachCurrentThread / GetEnv / ...
//   JNIEnv (JNINativeInterface) : the whole 230-odd entry table
//
// This module patches nothing. It snapshots both tables and reports where an
// entry no longer resolves into jvm.dll, so the decision - restore it, route
// around it, or move the work into the Java agent - is made on evidence
// instead of guesswork.
//
// The table *pointer* is captured once, on the first attached thread. Every
// thread's JNIEnv points at that same table, so the watchdog can inspect it
// from an unattached thread without making a single JNI call. A per-thread
// substitution (each thread handed a different table) is therefore not seen;
// that shape is not what asmjit-based trampolines produce.

namespace jnipro {

struct SlotHit {
    const char* table = nullptr;  // "JavaVM" / "JNIEnv"
    int index = 0;
    void* entry = nullptr;        // value stored in the table
    void* target = nullptr;       // resolved jump target when the entry is a stub
    wchar_t image[64] = {0};      // owning image; empty when the address has none
};

struct Scan {
    int vm_slots = 0;     // JavaVM entries inspected
    int env_slots = 0;    // JNIEnv entries inspected
    int changed = 0;      // entries that differ from the first snapshot
    int foreign = 0;      // entries resolving outside jvm.dll
    SlotHit hits[32];
    int hit_count = 0;
};

// Snapshot both tables and start the watchdog. Call once, from a thread that is
// already attached (the bootstrap thread right after AttachCurrentThread).
bool install(JavaVM* vm);

// One inspection pass. Pass nullptr to use the tables captured by install().
Scan scan(JavaVM* vm);

} // namespace jnipro
