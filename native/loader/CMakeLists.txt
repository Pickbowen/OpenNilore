# Qt-side toggles: AUTOMOC for our Q_OBJECT classes, AUTORCC for any .qrc.
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)
set(CMAKE_AUTOUIC ON)

find_package(Qt6 6.4 COMPONENTS Widgets REQUIRED)

# Stage the freshly-built OpenZen.dll into the loader's binary dir so rc.exe
# can pick it up as an RCDATA resource (embedded in the .exe).
set(EMBED_DIR "${CMAKE_CURRENT_BINARY_DIR}/embedded_dll")
add_custom_command(
    OUTPUT  "${EMBED_DIR}/OpenZen.dll"
    COMMAND ${CMAKE_COMMAND} -E make_directory "${EMBED_DIR}"
    COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:OpenZen> "${EMBED_DIR}/OpenZen.dll"
    DEPENDS OpenZen
    COMMENT "Staging OpenZen.dll into loader resources"
)
add_custom_target(stage_dll_for_loader DEPENDS "${EMBED_DIR}/OpenZen.dll")

qt_add_executable(OpenZenLoader WIN32
    src/main.cpp
    src/MainWindow.cpp
    src/MainWindow.h
    src/SplashScreen.cpp
    src/SplashScreen.h
    src/TitleBar.cpp
    src/TitleBar.h
    src/InstanceList.cpp
    src/InstanceList.h
    src/InstanceRow.cpp
    src/InstanceRow.h
    src/InjectionOverlay.cpp
    src/InjectionOverlay.h
    src/process_list.cpp
    src/injector.cpp
    src/manual_map.cpp
    src/embedded_dll.cpp
    src/window_title.cpp
    res/loader.rc
)

add_dependencies(OpenZenLoader stage_dll_for_loader)

target_include_directories(OpenZenLoader PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${CMAKE_CURRENT_SOURCE_DIR}/res
)

# Tell rc.exe to look in EMBED_DIR (where OpenZen.dll was staged) when
# resolving the RCDATA "OpenZen.dll" reference in loader.rc.
set_source_files_properties(res/loader.rc PROPERTIES
    COMPILE_FLAGS "/I\"${EMBED_DIR}\""
)

target_link_libraries(OpenZenLoader PRIVATE
    Qt6::Widgets
    psapi
    shlwapi
    user32
    dwmapi   # DwmSetWindowAttribute for Win11 rounded corners on frameless window
)

# Build-time git revision passed in by Gradle (gradle.properties /
# CI). Stamped into the window title so a built loader can be
# traced back to its source commit.
if(DEFINED OPENZEN_BUILD_REVISION AND NOT OPENZEN_BUILD_REVISION STREQUAL "")
    target_compile_definitions(OpenZenLoader PRIVATE
        OPENZEN_BUILD_REVISION="${OPENZEN_BUILD_REVISION}")
endif()

set_target_properties(OpenZenLoader PROPERTIES
    OUTPUT_NAME "OpenZenLoader"
)

if(MSVC)
    # requireAdministrator: lets the loader OpenProcess / VirtualAllocEx /
    # CreateRemoteThread against javaw.exe even when the launcher was
    # itself started elevated. UAC consent prompts once on launch.
    set_target_properties(OpenZenLoader PROPERTIES
        LINK_FLAGS "/SUBSYSTEM:WINDOWS /MANIFESTUAC:\"level='requireAdministrator' uiAccess='false'\""
    )
endif()
