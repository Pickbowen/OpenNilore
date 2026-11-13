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
    // RFC 4122 v4 shape: a UUID whose version nibble is random reads as fake
    // the moment anyone parses it, and SMBIOS type 1 exposes it raw.
    h[12] = L'4';
    h[16] = L'8';
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

namespace {

int hex_nibble(wchar_t c) {
    if (c >= L'0' && c <= L'9') return c - L'0';
    if (c >= L'A' && c <= L'F') return c - L'A' + 10;
    if (c >= L'a' && c <= L'f') return c - L'a' + 10;
    return -1;
}

} // namespace

void product_uuid_bytes(unsigned char out[16]) {
    const std::wstring& s = values().product_uuid;
    unsigned char raw[16] = {0};
    int nibbles = 0;
    for (wchar_t c : s) {
        int v = hex_nibble(c);
        if (v < 0) continue;
        unsigned char& byte = raw[nibbles >> 1];
        if ((nibbles & 1) == 0) byte = static_cast<unsigned char>(v);
        else byte = static_cast<unsigned char>((byte << 4) | v);
        if (++nibbles == 32) break;
    }
    out[0] = raw[3]; out[1] = raw[2]; out[2] = raw[1]; out[3] = raw[0];
    out[4] = raw[5]; out[5] = raw[4];
    out[6] = raw[7]; out[7] = raw[6];
    for (int i = 8; i < 16; ++i) out[i] = raw[i];
}

void disk_serial_identify(unsigned char out[20]) {
    const std::wstring& s = values().disk_serial;
    char plain[20];
    for (int i = 0; i < 20; ++i) {
        plain[i] = (i < static_cast<int>(s.size()) && s[i] < 0x80)
                       ? static_cast<char>(s[i])
                       : ' ';
    }
    for (int i = 0; i < 20; i += 2) {
        out[i] = static_cast<unsigned char>(plain[i + 1]);
        out[i + 1] = static_cast<unsigned char>(plain[i]);
    }
}

std::string narrow_ascii(const std::wstring& w) {
    std::string s;
    s.reserve(w.size());
    for (wchar_t c : w) s.push_back(c < 0x80 ? static_cast<char>(c) : '?');
    return s;
}

uint32_t mix_volume_serial(uint32_t real_serial) {
    uint32_t salt = 0;
    for (wchar_t c : values().volume_serial) {
        int nibble = hex_nibble(c);
        if (nibble >= 0) salt = (salt << 4) | static_cast<uint32_t>(nibble);
    }
    uint32_t h = real_serial ^ salt;
    h ^= h >> 16;
    h *= 0x7FEB352Du;
    h ^= h >> 15;
    h *= 0x846CA68Bu;
    h ^= h >> 16;
    return h;
}

} // namespace hwid
