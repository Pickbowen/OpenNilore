#pragma once

#include <string>

// Configuration for the HWID spoof.
//
// This used to be a file plus environment variables. It is now a constant
// defined in hwid_config.cpp: every failure attributed to "the spoof is not
// running" turned out to live in this layer, and a switch with one useful
// setting does not justify a file, a parser and four environment variables.
//
// The shipped value is: enabled, surface = wmi (the only one that changes no
// executable byte in the process).

namespace hwid {

struct Config {
    bool enabled = true;
    // Only the vtable surface is on by default.
    //
    // It is the one surface that touches no executable byte anywhere: the
    // spoof lives in a COM dispatch slot, so a code-integrity or prologue check
    // has nothing to find. Every other surface is a patch on a live function,
    // and `net` in particular patches GetAdaptersAddresses - an import the
    // anticheat lists itself - which is the one artifact worth not leaving
    // behind unless MAC spoofing is actually needed.
    std::wstring surfaces = L"wmi";
    bool force_get_hook = false;   // skip in-place Put, intercept Get directly
    bool verbose = false;          // log every hook decision

    // Extra seed mixed into the fake-identity derivation.
    //
    // Without it the fake set is a pure function of the real hardware: the same
    // machine derives the same fake forever, which is good for "does not look
    // like a VM" and useless for "the account got banned, I need a new
    // machine". Changing this value rotates every derived identifier while
    // keeping them internally consistent and stable between runs.
    unsigned long long seed = 0;   // 0 = derive from the hardware only (legacy)
};

const Config& config();

// True when `name` (wmi / net / reg / smbios / disk / wmipx) is in the active
// surface list.
bool surface_on(const char* name);

// Absolute path of the ini, or empty when none was found. Logged at startup so
// it is obvious which configuration was in effect.
const std::wstring& config_path();

} // namespace hwid
