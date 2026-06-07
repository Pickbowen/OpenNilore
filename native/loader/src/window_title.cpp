#include "loader.h"

namespace loader {

namespace {
    struct Search {
        DWORD pid;
        WindowInfo best;
    };

    BOOL CALLBACK enum_proc(HWND hwnd, LPARAM lp) {
        Search* s = reinterpret_cast<Search*>(lp);

        if (!IsWindowVisible(hwnd)) return TRUE;
        // Skip child windows / tool windows: we want main app windows.
        if (GetWindow(hwnd, GW_OWNER) != nullptr) return TRUE;

        DWORD pid = 0;
        GetWindowThreadProcessId(hwnd, &pid);
        if (pid != s->pid) return TRUE;

        int len = GetWindowTextLengthW(hwnd);
        if (len <= 0) return TRUE;
        std::wstring title(len, L'\0');
        GetWindowTextW(hwnd, title.data(), len + 1);
        title.resize(len);

        // Prefer the longest title - usually the main Minecraft window which
        // includes version/world name vs a tiny "Java" tooltip window.
        if (title.size() > s->best.title.size()) {
            wchar_t cls[256] = {0};
            GetClassNameW(hwnd, cls, 256);
            s->best.title = std::move(title);
            s->best.class_name = cls;
        }
        return TRUE;
    }
}

WindowInfo window_info_for(DWORD pid) {
    Search s{pid, {}};
    EnumWindows(enum_proc, reinterpret_cast<LPARAM>(&s));
    return s.best;
}

} // namespace loader
