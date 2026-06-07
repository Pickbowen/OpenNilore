#pragma once

#include <QMainWindow>
#include <QSet>
#include <QString>

class QLabel;
class QTimer;

namespace loader {

class TitleBar;
class InstanceList;

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
    void onInjectRequested(unsigned long pid, const QString& title);

protected:
    void paintEvent(QPaintEvent*) override;
    void showEvent(QShowEvent*) override;
    void closeEvent(QCloseEvent*) override;

private:
    void buildUi();
    void styleApp();
    void enableWin11RoundedCorners();

    TitleBar*     titleBar_ = nullptr;
    InstanceList* list_     = nullptr;
    QTimer*       timer_    = nullptr;
    QLabel*       status_   = nullptr;
    QLabel*       hint_     = nullptr;

    bool entrancePlayed_    = false;
    bool injectionInFlight_ = false;
    bool exiting_           = false;
};

} // namespace loader
