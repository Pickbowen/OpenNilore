#include "spoof_values.h"

#include <windows.h>

#include <cstdint>
#include <mutex>

namespace hwid {

namespace {

using RtlGenRandom_t = BOOLEAN(WINAPI*)(PVOID, ULONG);

RtlGenRandom_t rtl_random_fn() {
    static RtlGenRandom_t fn = reinterpret_cast<RtlGenRandom_t>(
        GetProcAddress(GetModuleHandleA("advapi32.dll"), "SystemFunction036"));
    return fn;
}

void fill_random(BYTE* out, size_t n) {
    RtlGenRandom_t fn = rtl_random_fn();
    if (fn && n <= 64) {
        if (fn(out, static_cast<ULONG>(n))) return;
    }
    // Last resort: the process is already abnormal if this path is taken.
    static uint64_t state = static_cast<uint64_t>(GetTickCount64()) | 1u;
    for (size_t i = 0; i < n; ++i) {
        state = state * 6364136223846793005ULL + 1442695040888963407ULL;
        out[i] = static_cast<BYTE>(state >> 33);
    }
}

std::wstring hex(size_t n) {
    static const wchar_t kHex[] = L"0123456789ABCDEF";
    BYTE buf[64] = {0};
    if (n > sizeof buf) n = sizeof buf;
    fill_random(buf, n);
    std::wstring s;
    s.reserve(n * 2);
    for (size_t i = 0; i < n; ++i) {
        s.push_back(kHex[buf[i] >> 4]);
        s.push_back(kHex[buf[i] & 0xF]);
    }
    return s;
}

std::wstring digits(size_t n) {
    BYTE buf[32] = {0};
    if (n > sizeof buf) n = sizeof buf;
    fill_random(buf, n);
    std::wstring s;
    s.reserve(n);
    for (size_t i = 0; i < n; ++i) s.push_back(static_cast<wchar_t>(L'0' + buf[i] % 10));
    return s;
}

// OEM part numbers and serials are upper-case alphanumerics; keeping the same
// alphabet avoids a value that no vendor would ever print on a sticker.
std::wstring alnum(size_t n, bool allow_digits_first = false) {
    static const wchar_t kSet[] = L"0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    BYTE buf[64] = {0};
    if (n > sizeof buf) n = sizeof buf;
    fill_random(buf, n);
    std::wstring s;
    s.reserve(n);
    for (size_t i = 0; i < n; ++i) {
        wchar_t c = kSet[buf[i] % (sizeof(kSet) / sizeof(wchar_t) - 1)];
        if (i == 0 && !allow_digits_first && c >= L'0' && c <= L'9') c = L'A';
        s.push_back(c);
    }
    return s;
}

std::wstring guid_string(bool braces) {
    std::wstring h = hex(16);
    std::wstring s = braces ? L"{" : L"";
    s += h.substr(0, 8);  s += L"-";
    s += h.substr(8, 4);  s += L"-";
    s += h.substr(12, 4); s += L"-";
    s += h.substr(16, 4); s += L"-";
    s += h.substr(20, 12);
    if (braces) s += L"}";
    return s;
}

SpoofValues build() {
    SpoofValues v;
    // Registry-side values.
    v.machine_guid  = guid_string(true);
    v.product_id    = digits(5) + L"-OEM-" + digits(7) + L"-" + digits(5);
    v.computer_name = L"DESKTOP-" + alnum(7, true);
    v.volume_serial = hex(4);

    // Per-device values. A real box never reports the same serial for the
    // BIOS, the board and the disk, and each class has its own shape.
    v.product_uuid    = guid_string(false);
    v.system_serial   = alnum(4) + digits(7);
    v.bios_serial     = alnum(3) + digits(7);
    v.baseboard_serial = alnum(2) + digits(8);
    v.disk_serial     = L"S3Z" + alnum(10, true) + digits(3);
    v.processor_id    = L"BFEBFBFF" + hex(4);
    return v;
}

} // namespace

const SpoofValues& values() {
    static const SpoofValues kValues = build();
    return kValues;
}

} // namespace hwid
