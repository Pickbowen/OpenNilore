#include "MainWindow.h"
#include "SplashScreen.h"
#include "autoquit.h"

#include <QApplication>
#include <QStyleFactory>

int main(int argc, char** argv) {
    // Debug-only: if the env var is set, auto-quit 1.2 s after the event loop
    // starts so the process can be launched non-interactively and observed
    // for the startup crash (0xC0000005). Keep the variable name non-secret;
    // it is window-dressing for local testing only.
    const bool dbgAutoQuit = qEnvironmentVariableIsSet("OPENZEN_AUTO_QUIT");
    QApplication::setHighDpiScaleFactorRoundingPolicy(
        Qt::HighDpiScaleFactorRoundingPolicy::PassThrough);

    QApplication app(argc, argv);
    if (dbgAutoQuit) {
        loader::dbg::installAutoQuit(&app);
    }
    QApplication::setStyle(QStyleFactory::create(QStringLiteral("Fusion")));
    QApplication::setApplicationName(QStringLiteral("OpenNilore Loader"));
    QApplication::setOrganizationName(QStringLiteral("OpenNilore"));

    // Main window is constructed up front but kept hidden until the splash
    // emits finished(), so its windowOpacity starts at 0 (set in showEvent
    // on its first show inside playEntrance()).
    auto* main = new loader::MainWindow();

    auto* splash = new loader::SplashScreen();
    QObject::connect(splash, &loader::SplashScreen::finished, main, [main] {
        main->playEntrance();
    });
    splash->start();

    int rc = app.exec();
    delete main;
    return rc;
}
