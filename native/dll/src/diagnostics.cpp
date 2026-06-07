#include "openzen.h"

#include <cstdarg>
#include <cstdio>
#include <mutex>

namespace openzen::log {

namespace {
    std::mutex g_mutex;
    HANDLE g_file = INVALID_HANDLE_VALUE;

    void write_line(const char* level, const char* fmt, va_list ap) {
        std::lock_guard<std::mutex> lock(g_mutex);

        SYSTEMTIME st;
        GetLocalTime(&st);

        char buf[2048];
        int prefix = std::snprintf(buf, sizeof buf,
                                   "[%02d:%02d:%02d.%03d %s] ",
                                   st.wHour, st.wMinute, st.wSecond,
                                   st.wMilliseconds, level);
        if (prefix < 0) prefix = 0;
        int body = std::vsnprintf(buf + prefix, sizeof buf - prefix - 2, fmt, ap);
        if (body < 0) body = 0;
        int total = prefix + body;
        if (total > (int)sizeof buf - 2) total = (int)sizeof buf - 2;
        buf[total++] = '\r';
        buf[total++] = '\n';

        OutputDebugStringA(buf);

        if (g_file != INVALID_HANDLE_VALUE) {
            DWORD written = 0;
            WriteFile(g_file, buf, (DWORD)total, &written, nullptr);
            FlushFileBuffers(g_file);
        }
    }
}

void init() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_file != INVALID_HANDLE_VALUE) return;

    wchar_t tmp[MAX_PATH];
    DWORD n = GetTempPathW(MAX_PATH, tmp);
    if (n == 0 || n > MAX_PATH) return;

    wchar_t path[MAX_PATH];
    std::swprintf(path, MAX_PATH, L"%sopenzen.log", tmp);

    g_file = CreateFileW(path, GENERIC_WRITE, FILE_SHARE_READ, nullptr,
                          CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
}

void info(const char* fmt, ...) {
    va_list ap; va_start(ap, fmt);
    write_line("INFO", fmt, ap);
    va_end(ap);
}

void error(const char* fmt, ...) {
    va_list ap; va_start(ap, fmt);
    write_line("ERROR", fmt, ap);
    va_end(ap);
}

} // namespace openzen::log
