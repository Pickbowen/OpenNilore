#include "hwid_config.h"

#include <cstring>
#include <string>

namespace hwid {

namespace {

// The whole configuration surface used to be a file plus four environment
// variables. It is now a constant.
//
// Why: every "the spoof did not do anything" report in this client's history
// traced back to the configuration layer rather than to the hooks - a file
// written with LF that the Windows profile APIs could not split, a stale copy
// in a second directory, an environment variable left over from an experiment.
// None of that is worth keeping for a switch that only has one useful setting.
//
// Change these constants and rebuild if the behaviour ever needs to differ.
constexpr bool kEnabled = true;              // always on
constexpr unsigned long long kIdentitySeed = 0;  // 0 = derive from the hardware

Config make_config() {
    Config c;
    c.enabled = kEnabled;
    // wmi only.
    //
    // It is the one surface that changes no executable byte anywhere. The MAC
    // surface needs a code-level hook (its caller resolves the import itself,
    // so an IAT patch never reaches it), and every session that had a signed
    // module modified was flagged, while the session that ran with nothing
    // modified was not. Given that evidence, 3 of the 6 collected fields -
    // UUID, disk serial, board serial - are worth more than a 4th field bought
    // with a modified image.
    //
    // The two CPU fields are left real regardless: ProcessorId is a CPUID dump
    // and ProcessorNameString is the brand string from the same instruction.
    // Faking either contradicts a `cpuid` the scanner can run in one line.
    c.surfaces = L"wmi";
    c.force_get_hook = false;
    c.verbose = false;
    c.seed = kIdentitySeed;
    return c;
}

} // namespace

const Config& config() {
    static const Config c = make_config();
    return c;
}

// Kept for the log line's format string. There is no file any more.
const std::wstring& config_path() {
    static const std::wstring kNone;
    return kNone;
}

bool surface_on(const char* name) {
    const Config& c = config();
    if (!c.enabled) return false;
    if (!name) return false;

    const std::wstring& list = c.surfaces;
    if (list == L"all") return true;
    if (list == L"none") return false;

    const size_t n = std::strlen(name);
    size_t i = 0;
    while (i < list.size()) {
        while (i < list.size() && (list[i] == L' ' || list[i] == L',')) ++i;
        const size_t start = i;
        while (i < list.size() && list[i] != L',' && list[i] != L' ') ++i;
        const size_t len = i - start;
        if (len == n) {
            bool same = true;
            for (size_t k = 0; k < n; ++k) {
                const wchar_t a = static_cast<wchar_t>(
                    static_cast<unsigned char>(name[k]));
                wchar_t b = list[start + k];
                if (b >= L'A' && b <= L'Z') b = static_cast<wchar_t>(b - L'A' + L'a');
                wchar_t al = a;
                if (al >= L'A' && al <= L'Z') al = static_cast<wchar_t>(al - L'A' + L'a');
                if (al != b) { same = false; break; }
            }
            if (same) return true;
        }
    }
    return false;
}

} // namespace hwid
