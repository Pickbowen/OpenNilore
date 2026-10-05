#pragma once

#include <QMainWindow>
#include <QSet>
#include <QString>

#include "InstanceList.h"   // struct Instance, used by maybeAutoInject()
#include "loader.h"         // struct JavaProcess, same

class QLabel;
class QSystemTrayIcon;
class QTimer;

namespace loader {

class TitleBar;
class InstanceList;
class Sidebar;
class SettingsPage;

class MainWindow : public QMainWindow {
    Q_OBJECT
public:
    explicit MainWindow(QWidget* parent = nullptr);

    // Called after the splash finishes; plays the entrance animation.
    void playEntrance();

    // Fade the window out, then quit the application. Idempotent: a second
    // call while a fade is already in flight is a no-op. Used by both the
    // close button and the "injection finished" path so the loader always
    // exits with a soft fade instead of vanishing.
    void playExitThenQuit();

private slots:
    void refreshNow();
    void onInjectRequested(unsigned long pid, const QString& title, const QString& commandLine);

protected:
    void paintEvent(QPaintEvent*) override;
    void showEvent(QShowEvent*) override;
    void closeEvent(QCloseEvent*) override;

private:
    void buildUi();
    void styleApp();
    void enableWin11RoundedCorners();

    // Early Mode auto-injection: fires for a Minecraft java process the loader
    // has not handled yet. Deliberately driven off the raw java process list and
    // its command line, NOT off the windowed instance list: the window only shows
    // up after Forge/mod loading, which is far too late to be "early". Pids we
    // created ourselves (and the ones already handled) are remembered so a
    // restart cannot feed itself back into another restart.
    void maybeAutoInject(const std::vector<JavaProcess>& procs);

    // Tray balloon for the injection result. The overlay only lives a couple of
    // seconds, so without this the outcome is easy to miss - which is exactly
    // how "I never saw Early Mode do anything" happens.
    void notify(const QString& title, const QString& body);

    TitleBar*        titleBar_ = nullptr;
    InstanceList*    list_     = nullptr;
    Sidebar*         sidebar_  = nullptr;
    SettingsPage*    settingsPage_ = nullptr;
    QTimer*          timer_    = nullptr;
    QLabel*          status_   = nullptr;
    QLabel*          hint_     = nullptr;

    bool entrancePlayed_    = false;
    bool injectionInFlight_ = false;
    bool exiting_           = false;
    QSet<unsigned long> earlyHandled_;
    QSystemTrayIcon* tray_ = nullptr;
};

} // namespace loader
