#pragma once

#include <QColor>
#include <QFontDatabase>
#include <QString>

namespace loader {
namespace ui {

constexpr int kRadiusWindow  = 14;
constexpr int kWindowRadius  = 8;    // matches the DWM rounding so the two edges coincide
constexpr int kRadiusCard    = 8;
constexpr int kRadiusControl = 6;
constexpr int kRadiusBadge   = 5;

constexpr int kSidebarWidth   = 168;
constexpr int kTitleBarHeight = 44;
constexpr int kStatusHeight   = 52;
constexpr int kRowHeight      = 52;
constexpr int kRowSpacing     = 6;
constexpr int kNavHeight      = 34;

constexpr int kGutter      = 20;
constexpr int kGutterRight = 18;
constexpr int kCardPadX    = 12;

inline QColor bgWindow()  { return QColor(0x14, 0x1A, 0x24); }
inline QColor bgSidebar() { return QColor(0x0D, 0x12, 0x19); }
inline QColor bgCard()    { return QColor(0x19, 0x1F, 0x29); }
inline QColor bgRaised()  { return QColor(0x21, 0x28, 0x33); }
inline QColor bgControl() { return QColor(0x27, 0x2E, 0x3A); }

inline QColor line()       { return QColor(255, 255, 255, 14); }
inline QColor lineStrong() { return QColor(255, 255, 255, 26); }

inline QColor textPrimary()   { return QColor(0xFF, 0xFF, 0xFF); }
inline QColor textBody()      { return QColor(0xE6, 0xEA, 0xF0); }
inline QColor textSecondary() { return QColor(0x93, 0x9A, 0xA5); }
inline QColor textMuted()     { return QColor(0x7C, 0x83, 0x8E); }
inline QColor textTertiary()  { return QColor(0x5E, 0x65, 0x6F); }

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
