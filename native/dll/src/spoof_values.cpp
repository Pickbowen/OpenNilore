#include "spoof_values.h"

#include <windows.h>
#include <winioctl.h>

#include <cstdint>
#include <cstring>
#include <cwchar>
#include <mutex>
#include <string>
#include <vector>

#if defined(_MSC_VER) || defined(__MINGW32__)
#include <intrin.h>
#endif

namespace hwid {

namespace {

constexpr DWORD kRawSmbios = 0x52534D42;   // 'RSMB'
constexpr size_t kMaxTableBytes = 4u << 20;

// ---------------------------------------------------------------------------
// Deterministic reseeding.
//
// The seed is a hash over the REAL fingerprint, so:
//   * the same physical machine always derives the same fake, across process
//     restarts, reboots and client updates - an anticheat that stores
//     account <-> machine pairs never sees the machine "change";
//   * a genuinely different machine derives a different fake, because the
//     input changes;
//   * there is nothing on disk to find. No profile file, no registry key.
// ---------------------------------------------------------------------------

struct Rng {
    uint64_t s;
    explicit Rng(uint64_t seed) : s(seed ? seed : 0x9E3779B97F4A7C15ull) {}
    uint32_t next() {
        s ^= s >> 33;
        s *= 0xFF51AFD7ED558CCDull;
        s ^= s >> 33;
        s *= 0xC4CEB9FE1A85EC53ull;
        s ^= s >> 33;
        return static_cast<uint32_t>(s >> 32);
    }
    uint32_t below(uint32_t n) { return n ? next() % n : 0; }
};

uint64_t fnv1a(const wchar_t* s) {
    uint64_t h = 0xCBF29CE484222325ull;
    if (!s) return h;
    for (; *s; ++s) {
        h ^= static_cast<uint64_t>(static_cast<uint32_t>(*s));
        h *= 0x100000001B3ull;
    }
    return h;
}

void hash_mix(uint64_t& h, const std::wstring& s) {
    h ^= fnv1a(s.c_str());
    h *= 0x100000001B3ull;
    h ^= h >> 29;
}

std::wstring hex8(uint32_t v) {
    static const wchar_t kHex[] = L"0123456789ABCDEF";
    wchar_t out[9];
    for (int i = 7; i >= 0; --i) {
        out[i] = kHex[v & 0xF];
        v >>= 4;
    }
    out[8] = 0;
    return out;
}

std::wstring narrow_to_wide(const std::string& s) {
    std::wstring w;
    w.reserve(s.size());
    for (unsigned char c : s) w.push_back(c < 0x80 ? static_cast<wchar_t>(c) : L'?');
    return w;
}

// ---------------------------------------------------------------------------
// Real value collection. MUST run before any hook is installed: once
// hwid_hook / hwid_firmware are armed these same APIs return the fake set and
// the derivation would feed on itself.
// ---------------------------------------------------------------------------

std::wstring read_reg_string(HKEY root, const wchar_t* subkey, const wchar_t* name) {
    HKEY key = nullptr;
    if (RegOpenKeyExW(root, subkey, 0, KEY_READ | KEY_WOW64_64KEY, &key) != ERROR_SUCCESS)
        return std::wstring();
    wchar_t buf[512] = {0};
    DWORD size = sizeof buf;
    DWORD type = 0;
    std::wstring out;
    if (RegQueryValueExW(key, name, nullptr, &type,
                         reinterpret_cast<LPBYTE>(buf), &size) == ERROR_SUCCESS &&
        (type == REG_SZ || type == REG_EXPAND_SZ)) {
        out.assign(buf);
    }
    RegCloseKey(key);
    return out;
}

std::wstring cpuid_processor_id() {
    int regs[4] = {0, 0, 0, 0};
    __cpuid(regs, 1);
    // Win32_Processor.ProcessorId = EDX:EAX of CPUID leaf 1, as two 32-bit
    // hex groups. Reported verbatim: `cpuid` is an instruction, not an API,
    // so no user-mode spoof of this value survives a direct read.
    return hex8(static_cast<uint32_t>(regs[3])) + hex8(static_cast<uint32_t>(regs[0]));
}

struct SmbiosWalker {
    const BYTE* data = nullptr;
    size_t size = 0;

    bool load() {
        HMODULE k32 = GetModuleHandleA("kernel32.dll");
        if (!k32) return false;
        using Fn = UINT(WINAPI*)(DWORD, DWORD, PVOID, DWORD);
        auto fn = reinterpret_cast<Fn>(GetProcAddress(k32, "GetSystemFirmwareTable"));
        if (!fn) return false;
        DWORD need = fn(kRawSmbios, 0, nullptr, 0);
        if (need == 0 || need > kMaxTableBytes) return false;
        raw_.assign(need, 0);
        if (fn(kRawSmbios, 0, raw_.data(), need) != need) return false;
        data = raw_.data();
        size = raw_.size();
        return true;
    }

    // Walk the structure table, calling back with (type, formatted, length,
    // strings). Stops at the end-of-table marker.
    template <typename Fn>
    bool walk(Fn cb) const {
        if (!data) return false;
        // GetSystemFirmwareTable('RSMB') hands back a RawSMBIOSData structure,
        // not a bare structure table:
        //   BYTE Used20CallingMethod, MajorVersion, MinorVersion, DmiRevision;
        //   DWORD Length;
        //   BYTE SMBIOSTableData[];
        // The Length field is what distinguishes the two layouts, so trust it
        // instead of guessing from the first byte.
        if (size >= 8) {
            const DWORD table_len = static_cast<DWORD>(data[4])
                                  | (static_cast<DWORD>(data[5]) << 8)
                                  | (static_cast<DWORD>(data[6]) << 16)
                                  | (static_cast<DWORD>(data[7]) << 24);
            if (table_len != 0 && table_len <= size - 8) return walk_at(8, cb);
        }
        return walk_at(0, cb);
    }

    template <typename Fn>
    bool walk_at(size_t off, Fn cb) const {
        while (off + 4 <= size) {
            const BYTE type = data[off];
            const BYTE len = data[off + 1];
            // Firmware is allowed to trail padding after the end-of-table
            // marker; stop rather than declaring the whole walk a failure,
            // because everything before this point is still good data.
            if (len < 4 || off + len > size) break;

            const BYTE* formatted = data + off;
            size_t p = off + len;
            std::vector<std::string> strings;
            if (p < size) {
                while (p < size) {
                    size_t n = 0;
                    while (p + n < size && data[p + n] != 0) ++n;
                    if (n == 0) { ++p; break; }
                    strings.emplace_back(reinterpret_cast<const char*>(data + p), n);
                    p += n + 1;
                }
            }
            cb(type, formatted, static_cast<size_t>(len), strings);
            off = p;
            if (type == 127) break;
        }
        return true;
    }

private:
    std::vector<BYTE> raw_;
};

std::wstring smbios_string(const std::vector<std::string>& strings, BYTE index) {
    if (index == 0 || index > strings.size()) return std::wstring();
    return narrow_to_wide(strings[index - 1]);
}

std::wstring uuid_text(const BYTE* b) {
    // SMBIOS type 1 stores the first three fields little-endian on the wire;
    // Windows renders them back into the familiar 8-4-4-4-12 form.
    static const wchar_t kHex[] = L"0123456789ABCDEF";
    std::wstring out;
    out.reserve(36);
    auto byte = [&](int i) {
        out.push_back(kHex[b[i] >> 4]);
        out.push_back(kHex[b[i] & 0xF]);
    };
    auto le32 = [&](int i) {
        for (int k = 3; k >= 0; --k) byte(i + k);
    };
    auto le16 = [&](int i) {
        for (int k = 1; k >= 0; --k) byte(i + k);
    };
    le32(0);
    out.push_back(L'-');
    le16(4);
    out.push_back(L'-');
    le16(6);
    out.push_back(L'-');
    byte(8); byte(9);
    out.push_back(L'-');
    for (int i = 10; i < 16; ++i) byte(i);
    return out;
}

bool all_zero_uuid(const std::wstring& s) {
    for (wchar_t c : s) {
        if (c != L'0' && c != L'-') return false;
    }
    return true;
}

struct DiskIdentity {
    std::wstring serial;
    std::wstring model;
};

DiskIdentity read_disk_identity() {
    DiskIdentity out;
    HANDLE h = CreateFileW(L"\\\\.\\PhysicalDrive0", 0,
                           FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr,
                           OPEN_EXISTING, 0, nullptr);
    if (h == INVALID_HANDLE_VALUE) return out;

    STORAGE_PROPERTY_QUERY query{};
    query.PropertyId = StorageDeviceProperty;
    query.QueryType = PropertyStandardQuery;
    std::vector<BYTE> buf(4096, 0);
    DWORD returned = 0;
    if (DeviceIoControl(h, IOCTL_STORAGE_QUERY_PROPERTY, &query, sizeof query,
                        buf.data(), static_cast<DWORD>(buf.size()), &returned, nullptr) &&
        returned > sizeof(STORAGE_DEVICE_DESCRIPTOR)) {
        auto* desc = reinterpret_cast<const STORAGE_DEVICE_DESCRIPTOR*>(buf.data());
        if (desc->SerialNumberOffset && desc->SerialNumberOffset < returned) {
            const char* s = reinterpret_cast<const char*>(buf.data() + desc->SerialNumberOffset);
            out.serial = narrow_to_wide(std::string(s));
        }
        if (desc->ProductIdOffset && desc->ProductIdOffset < returned) {
            const char* s = reinterpret_cast<const char*>(buf.data() + desc->ProductIdOffset);
            out.model = narrow_to_wide(std::string(s));
        }
    }
    CloseHandle(h);

    // ATA strings arrive space-padded; NVMe serials in this field are EUI64 /
    // NGUID text and can legitimately start with a digit, so only spaces are
    // trimmed. Stripping leading zeroes here silently shortened real serials.
    auto trim = [](std::wstring& s) {
        while (!s.empty() && s.front() == L' ') s.erase(s.begin());
        while (!s.empty() && s.back() == L' ') s.pop_back();
    };
    trim(out.serial);
    trim(out.model);
    return out;
}

RealValues collect_real() {
    RealValues r;

    SmbiosWalker smbios;
    if (smbios.load()) {
        r.smbios_ok = smbios.walk([&](BYTE type, const BYTE* f, size_t len,
                                      const std::vector<std::string>& strings) {
            if (type == 1) {
                if (len > 0x04) r.product_vendor = smbios_string(strings, f[0x04]);
                if (len > 0x05) r.product_name = smbios_string(strings, f[0x05]);
                if (len > 0x06) r.product_version = smbios_string(strings, f[0x06]);
                if (len > 0x07) r.system_serial = smbios_string(strings, f[0x07]);
                if (len >= 0x18) r.product_uuid = uuid_text(f + 0x08);
            } else if (type == 2) {
                if (len > 0x04) r.baseboard_manufacturer = smbios_string(strings, f[0x04]);
                if (len > 0x05) r.baseboard_product = smbios_string(strings, f[0x05]);
                if (len > 0x06) r.baseboard_version = smbios_string(strings, f[0x06]);
                if (len > 0x07) r.baseboard_serial = smbios_string(strings, f[0x07]);
            }
        });
    }

    DiskIdentity disk = read_disk_identity();
    r.disk_serial = disk.serial;
    r.disk_model = disk.model;
    r.disk_ok = !disk.serial.empty();

    r.processor_id = cpuid_processor_id();
    r.machine_guid = read_reg_string(HKEY_LOCAL_MACHINE, L"SOFTWARE\\Microsoft\\Cryptography",
                                     L"MachineGuid");
    return r;
}

// ---------------------------------------------------------------------------
// Shape-preserving derivation.
//
// A real serial is not a random string: it has a length, a character class per
// position, and a vendor prefix. Faking it means reseeding the free part and
// leaving everything else alone, so the value remains parseable by the same
// code that parses the real one.
// ---------------------------------------------------------------------------

wchar_t reseed_char(wchar_t c, Rng& rng) {
    // Exclude the glyphs vendors avoid printing on a label (I, O, 0, 1) but
    // keep the position's character class, which is what parsers key on.
    static const wchar_t kUpper[] = L"ABCDEFGHJKLMNPQRSTUVWXYZ";
    static const wchar_t kLower[] = L"abcdefghijkmnopqrstuvwxyz";
    static const wchar_t kDigit[] = L"23456789";
    if (c >= L'A' && c <= L'Z') return kUpper[rng.below(24)];
    if (c >= L'a' && c <= L'z') return kLower[rng.below(25)];
    if (c >= L'0' && c <= L'9') return kDigit[rng.below(8)];
    return c;   // separators, spaces and punctuation are structural
}

// Keep the first `keep` characters (vendor prefix) and the total length;
// reseed every alphanumeric after that.
std::wstring reshape(const std::wstring& real, size_t keep, Rng& rng, const wchar_t* fallback) {
    std::wstring base = real;
    if (base.empty()) base = fallback ? fallback : L"";
    if (base.empty()) return base;
    if (keep > base.size()) keep = base.size();
    std::wstring out = base;
    for (size_t i = keep; i < out.size(); ++i) out[i] = reseed_char(out[i], rng);
    return out;
}

// Reseed only the hex digits, preserving the 8-4-4-4-12 layout, the leading
// OEM group (which encodes the vendor that burned the UUID) and the case
// style: Windows writes MachineGuid in lower case, SMBIOS tools usually in
// upper, and a value that flips case mid-string is not something any real
// firmware or installer produces.
std::wstring reshape_uuid(const std::wstring& real, Rng& rng) {
    std::wstring s = real;
    static const wchar_t kTemplate[] = L"03000200-0400-0500-0006-000700080009";

    bool lower = false;
    for (wchar_t c : s) {
        if (c >= L'a' && c <= L'z') { lower = true; break; }
    }
    if (s.size() != 36 || all_zero_uuid(s)) {
        // All-zero UUIDs exist on some boards, but a board that reports one
        // has no fingerprint to begin with; fall back to the layout Intel
        // boards use so the value stays plausible.
        s = kTemplate;
        lower = false;
    }

    const wchar_t* hex = lower ? L"0123456789abcdef" : L"0123456789ABCDEF";
    for (size_t i = 8; i < s.size(); ++i) {
        if (s[i] == L'-') continue;
        s[i] = hex[rng.below(16)];
    }
    return s;
}

std::wstring derive_product_id(const std::wstring& real, Rng& rng) {
    // Retail/OEM Windows ProductId look like 00330-80000-00000-AA123.
    // Keep the group layout and reseed digits only.
    std::wstring s = real;
    if (s.size() < 20) s = L"00330-80000-00000-AA123";
    for (size_t i = 0; i < s.size(); ++i) {
        if (s[i] >= L'0' && s[i] <= L'9') s[i] = L'0' + rng.below(10);
    }
    return s;
}

std::wstring derive_computer_name(const std::wstring& real, Rng& rng) {
    std::wstring s = real;
    if (s.empty() || s.size() > 15) s = L"DESKTOP-ABCDEFG";
    // Windows default is DESKTOP- followed by seven upper-case alphanumerics.
    size_t keep = 0;
    if (s.size() > 8 && _wcsnicmp(s.c_str(), L"DESKTOP-", 8) == 0) keep = 8;
    else if (s.size() > 3) keep = 3;
    return reshape(s, keep, rng, L"DESKTOP-ABCDEFG");
}

SpoofValues build(const RealValues& r) {
    uint64_t seed = 0x5A17C3E9D4B21107ull;
    hash_mix(seed, r.product_uuid);
    hash_mix(seed, r.system_serial);
    hash_mix(seed, r.baseboard_serial);
    hash_mix(seed, r.disk_serial);
    hash_mix(seed, r.machine_guid);
    hash_mix(seed, r.processor_id);
    Rng rng(seed);

    SpoofValues v;

    // Registry side.
    {
        std::wstring real = r.machine_guid;
        if (real.size() != 36) real = L"3f2b1a90-4c7d-4e21-9b8a-0d6e5f4c3b2a";
        // Registry MachineGuid has no braces; the old code emitted "{...}".
        std::wstring s = reshape_uuid(real, rng);
        if (!s.empty() && s.front() == L'{') s.erase(s.begin());
        if (!s.empty() && s.back() == L'}') s.pop_back();
        v.machine_guid = s;
    }
    v.product_id = derive_product_id(L"", rng);
    v.computer_name = derive_computer_name(L"", rng);
    v.volume_serial = reshape(L"00000000", 0, rng, L"00000000");

    // SMBIOS type 1.
    v.product_uuid = reshape_uuid(r.product_uuid, rng);
    v.product_vendor = r.product_vendor;
    v.product_name = r.product_name;
    v.product_version = r.product_version;
    // OEM serials encode model and build year in the leading characters
    // (Samsung "S41...", Dell service tags, ASUS "MB-..."), so keep four of
    // them: enough that the serial still looks like it belongs to this model,
    // leaving 11+ derived characters of entropy.
    v.system_serial = reshape(r.system_serial, r.system_serial.size() >= 10 ? 4 : 2, rng,
                              L"ABC1234567");

    // SMBIOS type 2. The manufacturer/model/version stay verbatim: the serial
    // is derived from the real one, so it already matches that vendor's shape.
    v.baseboard_serial = reshape(r.baseboard_serial, r.baseboard_serial.size() >= 10 ? 4 : 2,
                                 rng, L"AB12345678");
    v.baseboard_manufacturer = r.baseboard_manufacturer;
    v.baseboard_product = r.baseboard_product;
    v.baseboard_version = r.baseboard_version;

    // Storage. Keep the vendor prefix of the real serial, keep its length.
    // NVMe reports an EUI64/NGUID here ("E823_8FA6_..."), where the leading
    // group is the vendor, so four characters is the right cut for both the
    // ATA ("WD-WCC...") and the NVMe form.
    v.disk_serial = reshape(r.disk_serial, r.disk_serial.size() >= 10 ? 4 : 3, rng,
                            L"WD-WCC1234567");
    v.disk_model = r.disk_model;

    // CPUID cannot be spoofed from user mode; report the real value.
    v.processor_id = r.processor_id;
    return v;
}

} // namespace

const RealValues& real() {
    static const RealValues kReal = collect_real();
    return kReal;
}

const SpoofValues& values() {
    static const SpoofValues kValues = build(real());
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

void mix_mac(const unsigned char real[6], unsigned char out[6]) {
    // Keep the OUI (first three bytes) so the adapter still belongs to the
    // vendor's block, and keep both flag bits: the locally-administered bit is
    // exactly how a scanner spots a virtual or forged adapter. Only the
    // device part is reseeded, deterministically per machine, so the same card
    // always reports the same fake and a different card reports a different
    // one - a real second NIC, not a rotating MAC.
    for (int i = 0; i < 6; ++i) out[i] = real[i];

    uint32_t h = static_cast<uint32_t>(fnv1a(values().machine_guid.c_str()));
    for (int i = 0; i < 6; ++i) {
        h = (h ^ static_cast<uint32_t>(real[i])) * 16777619u;
        h ^= h >> 13;
    }
    for (int i = 3; i < 6; ++i) {
        out[i] = static_cast<unsigned char>((real[i] ^ (h >> ((i - 3) * 8))) & 0xFF);
    }
    out[0] = real[0];   // unicast + globally-administered bits untouched
}

} // namespace hwid
