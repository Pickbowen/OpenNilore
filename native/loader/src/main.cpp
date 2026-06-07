#include "MainWindow.h"
#include "SplashScreen.h"

#include <QApplication>
#include <QStyleFactory>

int main(int argc, char** argv) {
    QApplication::setHighDpiScaleFactorRoundingPolicy(
        Qt::HighDpiScaleFactorRoundingPolicy::PassThrough);

    QApplication app(argc, argv);
    QApplication::setStyle(QStyleFactory::create(QStringLiteral("Fusion")));
    QApplication::setApplicationName(QStringLiteral("OpenZen Loader"));
    QApplication::setOrganizationName(QStringLiteral("OpenZen"));

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
