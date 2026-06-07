cmake_minimum_required(VERSION 3.20)
# CMP0091 NEW lets us drive the MSVC runtime via MSVC_RUNTIME_LIBRARY
# instead of patching CMAKE_CXX_FLAGS_*. CMake 3.15+ default, set
# explicitly so the build stays predictable across CMake versions.
cmake_policy(SET CMP0091 NEW)
project(OpenZenNative LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Statically link the MSVC C/C++ runtime into both the loader EXE and the
# injected DLL. This matters most for the DLL: our manual mapper resolves
# import addresses locally and assumes the imported DLL has the same base
# in both processes - true for kernel32/user32/ntdll (KnownDLLs) but NOT
# for vcruntime140/ucrtbase, so the mapped DLL would otherwise call CRT
# functions through wild pointers and crash the target Java process. /MT
# eliminates those imports entirely.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

if(NOT DEFINED ENV{JAVA_HOME})
    message(FATAL_ERROR "JAVA_HOME must be set so we can locate jni.h / jvmti.h")
endif()

set(JDK_INCLUDE "$ENV{JAVA_HOME}/include")
include_directories(${JDK_INCLUDE} ${JDK_INCLUDE}/win32)

if(NOT WIN32)
    message(FATAL_ERROR "OpenZen native build only supports Windows")
endif()

add_compile_definitions(
    UNICODE
    _UNICODE
    WIN32_LEAN_AND_MEAN
    NOMINMAX
)

if(MSVC)
    # /MP enables cl.exe-level multi-process compilation: every translation
    # unit inside a single vcxproj compiles in parallel. Without it, MSBuild
    # only parallelises *across* projects (the -j N forwarded by
    # `cmake --build --parallel` controls that), so a single project with
    # a dozen .cpp files would still compile serially.
    add_compile_options(/W3 /permissive- /utf-8 /EHsc /MP)
endif()

add_subdirectory(dll)
add_subdirectory(loader)
