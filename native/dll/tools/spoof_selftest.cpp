// Offline check for the derivation in spoof_values.cpp: prints the real values
// side by side with the derived fake, then the same again from a second call.
// The second block must be byte-identical to the first - that equality is the
// property the anticheat's account<->machine binding depends on.
#include <cstdio>
#include <string>
#include <vector>

#include <windows.h>

#include "../src/spoof_values.h"

static const char* w(const std::wstring& s) {
    static char buf[4][512];
    static int n = 0;
    char* out = buf[n++ & 3];
    int j = 0;
    for (size_t i = 0; i < s.size() && j < 500; ++i) out[j++] = (s[i] < 0x80) ? (char)s[i] : '?';
    out[j] = 0;
    return out;
}

static void row(const char* k, const std::wstring& real, const std::wstring& fake) {
    printf("%-26s real=[%s] (%zu)\n%-26s fake=[%s] (%zu)\n\n",
           k, w(real), real.size(), "", w(fake), fake.size());
}

int main() {
    // Raw SMBIOS probe: this is the exact call the real collector makes, so a
    // failure here explains an empty product_uuid without guessing.
    {
        using Fn = UINT(WINAPI*)(DWORD, DWORD, PVOID, DWORD);
        HMODULE k32 = GetModuleHandleA("kernel32.dll");
        auto fn = k32 ? reinterpret_cast<Fn>(GetProcAddress(k32, "GetSystemFirmwareTable"))
                      : nullptr;
        if (!fn) {
            printf("smbios: GetSystemFirmwareTable not resolvable\n");
        } else {
            SetLastError(0);
            DWORD need = fn(0x52534D42 /* 'RSMB' */, 0, nullptr, 0);
            printf("smbios: need=%lu err=%lu\n", need, GetLastError());
            if (need) {
                std::vector<BYTE> buf(need, 0);
                UINT got = fn(0x52534D42, 0, buf.data(), need);
                printf("smbios: got=%u head=%02X %02X %02X %02X\n",
                       got, buf[0], buf[1], buf[2], buf[3]);
                // Walk a couple of structures to show where parsing would stop.
                size_t off = 0;
                for (int i = 0; i < 4 && off + 4 < buf.size(); ++i) {
                    printf("  struct@%zu: type=%u len=%u\n", off, buf[off], buf[off + 1]);
                    BYTE len = buf[off + 1];
                    if (len < 4) { printf("  (bad length, walk stops)\n"); break; }
                    size_t p = off + len;
                    while (p < buf.size() && buf[p] != 0) { while (p < buf.size() && buf[p]) ++p; ++p; }
                    if (p < buf.size()) ++p;
                    off = p;
                }
            }
        }
    }
    printf("\n");

    const hwid::RealValues& r = hwid::real();
    const hwid::SpoofValues& a = hwid::values();
    const hwid::SpoofValues& b = hwid::values();

    printf("smbios_ok=%d disk_ok=%d\n\n", (int)r.smbios_ok, (int)r.disk_ok);

    row("product_uuid", r.product_uuid, a.product_uuid);
    row("system_serial", r.system_serial, a.system_serial);
    row("baseboard_serial", r.baseboard_serial, a.baseboard_serial);
    row("disk_serial", r.disk_serial, a.disk_serial);
    row("processor_id", r.processor_id, a.processor_id);
    row("machine_guid", r.machine_guid, a.machine_guid);

    // Determinism: values() is a cached singleton, so this really re-reads the
    // same object. The interesting check is a fresh process run - run this
    // binary twice and diff the output.
    printf("--- derived set, second read ---\n");
    printf("uuid=%s\n", w(b.product_uuid));
    printf("disk=%s\n", w(b.disk_serial));
    printf("board=%s\n", w(b.baseboard_serial));
    printf("guid=%s\n", w(b.machine_guid));
    printf("pid=%s\n", w(b.product_id));
    printf("name=%s\n", w(b.computer_name));

    // Shape checks that a scanner would run.
    auto hexish = [](const std::wstring& s) {
        for (wchar_t c : s) {
            if (!((c >= L'0' && c <= L'9') ||
                  (c >= L'A' && c <= L'F') ||
                  (c >= L'a' && c <= L'f') || c == L'-'))
                return false;
        }
        return !s.empty();
    };
    printf("\nshape: uuid_hex36=%d guid_hex36=%d processor_len16=%d\n",
           (int)(hexish(a.product_uuid) && a.product_uuid.size() == 36),
           (int)(hexish(a.machine_guid) && a.machine_guid.size() == 36),
           (int)(a.processor_id.size() == 16));
    return 0;
}
