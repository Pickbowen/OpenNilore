#pragma once

#include <string>

// Runtime configuration for the HWID spoof.
//
// Read once, from %TEMP%\openzen_hwid.ini, with environment variables taking
// precedence (so a one-off test does not require editing the file).
//
// Default is: enabled, surfaces = wmi,net - the two surfaces that cover what
// the anticheat actually collects. Everything else stays off because each
// extra hook is another place a self-check can look.

namespace hwid {

struct Config {
    bool enabled = true;
    std::wstring surfaces = L"wmi,net";
    bool force_get_hook = false;   // skip in-place Put, intercept Get directly
    bool verbose = false;          // log every hook decision
};

const Config& config();

// True when `name` (wmi / net / reg / smbios / disk / wmipx) is in the active
// surface list.
bool surface_on(const char* name);

// Absolute path of the ini, or empty when none was found. Logged at startup so
// it is obvious which configuration was in effect.
const std::wstring& config_path();

} // namespace hwid
