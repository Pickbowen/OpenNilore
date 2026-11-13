#pragma once

#include <QColor>
#include <QFontDatabase>
#include <QString>

namespace loader {
namespace ui {

constexpr int kRadiusWindow  = 14;
constexpr int kRadiusCard    = 8;
constexpr int kRadiusControl = 6;
constexpr int kRadiusBadge   = 5;

constexpr int kSidebarWidth   = 168;
constexpr int kTitleBarHeight = 44;
constexpr int kStatusHeight   = 36;
constexpr int kRowHeight      = 52;
constexpr int kRowSpacing     = 6;
constexpr int kNavHeight      = 34;

constexpr int kGutter      = 20;
constexpr int kGutterRight = 18;
constexpr int kCardPadX    = 12;

inline QColor bgWindow()  { return QColor(0x17, 0x19, 0x1C); }
inline QColor bgSidebar() { return QColor(0x12, 0x14, 0x17); }
inline QColor bgCard()    { return QColor(0x1D, 0x20, 0x24); }
inline QColor bgRaised()  { return QColor(0x24, 0x28, 0x2D); }
inline QColor bgControl() { return QColor(0x2A, 0x2F, 0x35); }

inline QColor line()       { return QColor(255, 255, 255, 12); }
inline QColor lineStrong() { return QColor(255, 255, 255, 22); }

inline QColor textPrimary()   { return QColor(0xFF, 0xFF, 0xFF); }
inline QColor textBody()      { return QColor(0xD2, 0xD6, 0xDC); }
inline QColor textSecondary() { return QColor(0x94, 0x9A, 0xA4); }
inline QColor textMuted()     { return QColor(0x85, 0x8B, 0x95); }
inline QColor textTertiary()  { return QColor(0x66, 0x6C, 0x76); }

inline QColor accent()   { return QColor(0x5C, 0x9D, 0xFF); }
inline QColor onAccent() { return QColor(0xFF, 0xFF, 0xFF); }
inline QColor success()  { return QColor(0x4A, 0xDE, 0x80); }
inline QColor danger()   { return QColor(0xFF, 0x6B, 0x6B); }

inline QString uiFontFamily() {
    static const QString cached = [] {
        const QStringList preferred{
            QStringLiteral("Poppins"),
            QStringLiteral("Inter"),
            QStringLiteral("Segoe UI Variable Text"),
            QStringLiteral("Segoe UI"),
        };
        const QStringList available = QFontDatabase::families();
        for (const QString& family : preferred) {
            if (available.contains(family)) return family;
        }
        return QStringLiteral("Segoe UI");
    }();
    return cached;
}

} // namespace ui
} // namespace loader
