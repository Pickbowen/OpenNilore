#pragma once

#include <QMainWindow>

class QTimer;

// Debug: after the QApplication event loop starts, a QTimer fires
// QTimer::singleShot(1200) and quits the app. This avoids a long-running
// window and lets us launch the loader from the console to observe whether
// the crash (0xC0000005, fault at exe+0x245ba5) reproduces. WITHOUT the
// window a crash on startup (inside Qt uic / style init) would also manifest
// as an immediate exit, so the timeout both proves the window came up and
// gives the crash a chance to fire first.
namespace loader::dbg {

// Install a QTimer::singleShot(this, 1200, &QCoreApplication::quit).
void installAutoQuit(QObject* parent);

} // namespace loader::dbg