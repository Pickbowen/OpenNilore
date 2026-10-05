#include "MainWindow.h"

#include "InjectionOverlay.h"
#include "InstanceList.h"
#include "SettingsPage.h"
#include "Sidebar.h"
#include "TitleBar.h"
#include "loader.h"
#include "loader_settings.h"

#include <algorithm>
#include <cwctype>

#include <QApplication>
#include <QCloseEvent>
#include <QSystemTrayIcon>
#include <QStyle>
#include <QEasingCurve>
#include <QHBoxLayout>
#include <QLabel>
#include <QLinearGradient>
#include <QPainter>
#include <QPainterPath>
#include <QParallelAnimationGroup>
#include <QPropertyAnimation>
#include <QRandomGenerator>
#include <QString>
#include <QTimer>
#include <QVBoxLayout>
#include <QVector>
#include <QWidget>

#ifdef Q_OS_WIN
#  include <windows.h>
#  include <dwmapi.h>
#endif

#include <string>

namespace loader {

namespace {
constexpr int kCornerRadius = 12;
constexpr int kBaseWidth    = 760;
constexpr int kBaseHeight   = 500;
constexpr int kSizeJitter   = 10;   // ± px of random size jitter applied per launch

// Random alphanumeric identifier (first char always a letter). Used to give the
// loader window a non-constant Win32 title each launch so a scanner can't match
// it against a fixed window-text string.
QString randomIdent(int minLen, int maxLen) {
    static const char kAlphabet[] =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    constexpr int kLetters = 52;                       // leading a-zA-Z only
    constexpr int kAll     = int(sizeof(kAlphabet)) - 1;
    QRandomGenerator* rng = QRandomGenerator::global();
    const int len = minLen + int(rng->bounded(quint32(maxLen - minLen + 1)));
    QString s;
    s.reserve(len);
    s.append(QLatin1Char(kAlphabet[rng->bounded(kLetters)]));
    for (int i = 1; i < len; ++i) {
        s.append(QLatin1Char(kAlphabet[rng->bounded(kAll)]));
    }
    return s;
}

QString fromW(const std::wstring& w) {
    return QString::fromWCharArray(w.c_str(), static_cast<int>(w.size()));
}

bool startsWithMinecraft(const std::wstring& title) {
    static const std::wstring prefix = L"Minecraft";
    if (title.size() < prefix.size()) return false;
    return title.compare(0, prefix.size(), prefix) == 0;
}

bool isMinecraft(const std::wstring& title, const std::wstring& cls) {
    // Either the title starts with "Minecraft" (in-game window such as
    // "Minecraft 1.20.1") or the LWJGL GLFW window class is in use (still
    // true before the title gets set during early startup).
    if (startsWithMinecraft(title)) return true;
    return _wcsicmp(cls.c_str(), L"GLFW30") == 0;
}
} // namespace

MainWindow::MainWindow(QWidget* parent)
        : QMainWindow(parent) {
    // Frameless + translucent so paintEvent can draw a rounded panel and
    // shape the window however we like. The custom TitleBar covers move/
    // minimize/close.
    setWindowFlags(Qt::FramelessWindowHint | Qt::Window);
    setAttribute(Qt::WA_TranslucentBackground);
    setAttribute(Qt::WA_NoSystemBackground);
    // Base size with a small random jitter (±kSizeJitter px) on each axis so the
    // window dimensions aren't a constant fingerprint. Minimum is lowered by the
    // jitter so a negative jitter isn't clamped back to the base size.
    setMinimumSize(kBaseWidth - kSizeJitter, kBaseHeight - kSizeJitter);
    QRandomGenerator* rng = QRandomGenerator::global();
    const int jitterW = int(rng->bounded(quint32(2 * kSizeJitter + 1))) - kSizeJitter;
    const int jitterH = int(rng->bounded(quint32(2 * kSizeJitter + 1))) - kSizeJitter;
    resize(kBaseWidth + jitterW, kBaseHeight + jitterH);

    styleApp();
    buildUi();

    timer_ = new QTimer(this);
    timer_->setInterval(1000);
    connect(timer_, &QTimer::timeout, this, &MainWindow::refreshNow);
    timer_->start();
    refreshNow();
}

void MainWindow::buildUi() {
#ifdef OPENZEN_BUILD_REVISION
    const QString displayTitle = QStringLiteral("OpenNilore Loader  ·  build %1")
            .arg(QString::fromLatin1(OPENZEN_BUILD_REVISION).left(7));
#else
    const QString displayTitle = QStringLiteral("OpenNilore Loader");
#endif
    // The OS-level window title (what GetWindowTextW and window scanners read) is
    // randomised on every launch so it can't be matched against a fixed string.
    // The visible custom title bar below still shows the branded name.
    setWindowTitle(randomIdent(8, 16));

    auto* central = new QWidget(this);
    central->setAttribute(Qt::WA_TranslucentBackground);

    auto* root = new QVBoxLayout(central);
    root->setContentsMargins(0, 0, 0, 0);
    root->setSpacing(0);

    titleBar_ = new TitleBar(central);
    titleBar_->setTitleText(displayTitle);
    root->addWidget(titleBar_);

    // Flat DeepSeek-style: the sidebar is a fixed-width column; the Inject
    // view (InstanceList) is a DIRECT child of this central widget - exactly
    // like the proven-stable original, never wrapped in any intermediate
    // container (wrapping it in a shell/dock/stack caused a vtable UAF:
    // exe+0x245cd5 / 0x246655 / 0x246635). The Settings page is a sibling
    // overlay child that is shown/hidden via setVisible.
    auto* body = new QWidget(central);
    body->setObjectName("body");
    auto* bodyLayout = new QHBoxLayout(body);
    bodyLayout->setContentsMargins(0, 0, 0, 0);
    bodyLayout->setSpacing(0);

    sidebar_ = new Sidebar(body);
    bodyLayout->addWidget(sidebar_);

    list_ = new InstanceList(body);
    connect(list_, &InstanceList::injectRequested,
            this, &MainWindow::onInjectRequested);
    bodyLayout->addWidget(list_, 1);

    settingsPage_ = new SettingsPage(body);
    settingsPage_->setVisible(false);
    bodyLayout->addWidget(settingsPage_, 1);

    connect(sidebar_, &Sidebar::injectClicked, this, [this] {
        list_->setVisible(true);
        settingsPage_->setVisible(false);
    });
    connect(sidebar_, &Sidebar::settingsClicked, this, [this] {
        list_->setVisible(false);
        settingsPage_->setVisible(true);
    });

    root->addWidget(body, 1);

    auto* statusRow = new QWidget(central);
    auto* statusRowLayout = new QHBoxLayout(statusRow);
    statusRowLayout->setContentsMargins(10, 0, 10, 10);
    status_ = new QLabel(statusRow);
    status_->setObjectName("status");
    statusRowLayout->addWidget(status_);
    statusRowLayout->addStretch(1);
    root->addWidget(statusRow);

    setCentralWidget(central);
}

void MainWindow::styleApp() {
    // Modern dark palette via QSS. The MainWindow background is painted in
    // paintEvent (rounded panel) so we don't set a background colour on
    // QMainWindow/QWidget directly here.
    static const char* kQss = R"qss(
        QWidget#body {
            background: transparent;
            color: #e3e5ea;
            font-family: "Segoe UI", "Microsoft YaHei UI", sans-serif;
            font-size: 13px;
        }
        QLabel#title {
            font-size: 18px;
            font-weight: 600;
            color: #ffffff;
            padding: 2px 0 0 2px;
        }
        QLabel#hint {
            color: #8a8e98;
            font-size: 12px;
            padding-left: 2px;
        }
        QLabel#status {
            color: #c2c6cf;
            padding: 7px 11px;
            border: 1px solid #2c2e35;
            border-radius: 7px;
            background: rgba(35, 37, 43, 200);
        }
    )qss";
    qApp->setStyleSheet(QString::fromUtf8(kQss));
}

void MainWindow::enableWin11RoundedCorners() {
#ifdef Q_OS_WIN
    // DWMWA_WINDOW_CORNER_PREFERENCE is Windows 11+. The call is harmless
    // (returns an HRESULT we ignore) on Windows 10 — DwmSetWindowAttribute
    // just rejects the unknown attribute, no crash.
    enum { DWMWA_WINDOW_CORNER_PREFERENCE_LOCAL = 33 };
    enum { DWMWCP_ROUND_LOCAL = 2 };
    HWND hwnd = reinterpret_cast<HWND>(winId());
    if (!hwnd) return;
    int pref = DWMWCP_ROUND_LOCAL;
    DwmSetWindowAttribute(hwnd,
                          DWMWA_WINDOW_CORNER_PREFERENCE_LOCAL,
                          &pref,
                          sizeof(pref));
#endif
}

void MainWindow::paintEvent(QPaintEvent*) {
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);

    QRectF r = QRectF(rect()).adjusted(0.5, 0.5, -0.5, -0.5);
    QPainterPath panel;
    panel.addRoundedRect(r, kCornerRadius, kCornerRadius);

    QLinearGradient bg(r.topLeft(), r.bottomLeft());
    bg.setColorAt(0.0, QColor("#1f2127"));
    bg.setColorAt(1.0, QColor("#15171c"));
    p.fillPath(panel, bg);

    p.setPen(QPen(QColor(255, 255, 255, 26), 1));
    p.setBrush(Qt::NoBrush);
    p.drawPath(panel);
}

void MainWindow::showEvent(QShowEvent* e) {
    QMainWindow::showEvent(e);
    enableWin11RoundedCorners();
    if (!entrancePlayed_) {
        setWindowOpacity(0.0);
    }
}

void MainWindow::playEntrance() {
    entrancePlayed_ = true;
    show();
    raise();
    activateWindow();

    auto* fade = new QPropertyAnimation(this, "windowOpacity", this);
    fade->setDuration(520);
    fade->setStartValue(windowOpacity());
    fade->setEndValue(1.0);
    fade->setEasingCurve(QEasingCurve::OutCubic);

    QRect endGeo   = geometry();
    QRect startGeo = endGeo;
    startGeo.translate(0, 18);
    setGeometry(startGeo);
    auto* slide = new QPropertyAnimation(this, "geometry", this);
    slide->setDuration(560);
    slide->setStartValue(startGeo);
    slide->setEndValue(endGeo);
    slide->setEasingCurve(QEasingCurve::OutCubic);

    auto* grp = new QParallelAnimationGroup(this);
    grp->addAnimation(fade);
    grp->addAnimation(slide);
    grp->start(QAbstractAnimation::DeleteWhenStopped);
}

namespace {

// The window is not up yet when we want to act, so the command line is the only
// signal available. It is set at process creation, which is exactly why the
// trigger can fire seconds after java.exe starts instead of after the game has
// finished loading its mods.
bool looks_like_minecraft(const std::wstring& command_line) {
    if (command_line.empty()) return false;
    std::wstring lower(command_line);
    std::transform(lower.begin(), lower.end(), lower.begin(),
                   [](wchar_t c) { return (wchar_t)std::towlower(c); });
    // --gameDir / --assetIndex are the vanilla launcher's own arguments and are
    // what NetEase-style launchers pass through too; the main class checks cover
    // launches that were built by hand.
    return lower.find(L"--gamedir") != std::wstring::npos
        || lower.find(L"--assetindex") != std::wstring::npos
        || lower.find(L"net.minecraft.client.main.main") != std::wstring::npos
        || lower.find(L"minecraft") != std::wstring::npos;
}

// A Minecraft java process. The command line is the early signal; the window is
// the fallback for when the PEB read is refused (a protected game process), which
// is late but still better than never injecting.
bool is_minecraft_process(const JavaProcess& jp) {
    return looks_like_minecraft(jp.command_line)
        || isMinecraft(jp.window_title, jp.window_class);
}

} // namespace

void MainWindow::refreshNow() {
    auto procs = list_java_processes();
    QVector<Instance> filtered;
    filtered.reserve(procs.size());
    for (const auto& jp : procs) {
        if (!isMinecraft(jp.window_title, jp.window_class)) continue;
        Instance item;
        item.pid = jp.pid;
        item.title = fromW(jp.window_title);
        item.commandLine = fromW(jp.command_line);
        if (item.title.isEmpty()) {
            item.title = QStringLiteral("(starting up — %1)")
                    .arg(fromW(jp.window_class));
        }
        filtered.push_back(std::move(item));
    }

    list_->setInstances(filtered);

    // Early Mode triggers off the raw java list, not the windowed one, so say
    // what it currently sees: "no java process at all" and "a java process that
    // does not look like Minecraft" are different failures and the loader hid
    // both of them behind the same one-line status.
    if (loader_settings::is_early_mode_enabled()) {
        int matching = 0;
        for (const auto& jp : procs) {
            if (is_minecraft_process(jp)) ++matching;
        }
        status_->setText(QStringLiteral(
                "Early Mode — %1 java process(es) seen, %2 recognised.")
                .arg((int)procs.size()).arg(matching));
    } else {
        status_->setText(QStringLiteral("Watching %1 Minecraft instance(s).")
                         .arg(list_->count()));
    }

    maybeAutoInject(procs);
}

void MainWindow::maybeAutoInject(const std::vector<JavaProcess>& procs) {
    // Early Mode is an auto path: the user launches the game normally and the
    // loader takes the instance over itself, because being "early" means we have
    // to be the one that starts the process (CREATE_SUSPENDED). Nothing happens
    // while the toggle is off.
    if (!loader_settings::is_early_mode_enabled()) return;
    if (injectionInFlight_) return;

    for (const JavaProcess& jp : procs) {
        // Skip what we already dealt with, and skip the pid our own relaunch
        // produced - otherwise the restart would queue another restart forever.
        if (earlyHandled_.contains(jp.pid)) continue;
        if (!is_minecraft_process(jp)) continue;
        earlyHandled_.insert(jp.pid);
        QString title = fromW(jp.window_title);
        if (title.isEmpty()) {
            title = QStringLiteral("Minecraft (starting up)");
        }
        onInjectRequested(jp.pid, title, fromW(jp.command_line));
        return;                                     // one instance at a time
    }
}

void MainWindow::notify(const QString& title, const QString& body) {
    if (!tray_) {
        tray_ = new QSystemTrayIcon(this);
        QIcon icon = qApp->windowIcon();
        if (icon.isNull()) {
            icon = style()->standardIcon(QStyle::SP_ComputerIcon);
        }
        tray_->setIcon(icon);
        tray_->show();
    }
    tray_->showMessage(title, body, QSystemTrayIcon::Information, 5000);
}

void MainWindow::onInjectRequested(unsigned long pid, const QString& title,
                                   const QString& commandLine) {
    // Signal re-entrancy guard: the Early Mode auto-detection fires on every
    // newly-seen Minecraft pid. When auto-inject is already in progress
    // (injectionInFlight_), drop the new candidate instead of starting a
    // second overlay / worker thread.
    if (injectionInFlight_) return;
    injectionInFlight_ = true;

    // Freeze the list so the user can't queue another injection while we're
    // waiting for the worker thread + animation to wrap up.
    list_->setInteractive(false);
    if (timer_) timer_->stop();

    const bool early = loader_settings::is_early_mode_enabled();
    auto* overlay = new InjectionOverlay(pid, title, commandLine, this);
    connect(overlay, &InjectionOverlay::completed, this,
            [this, early](bool ok, unsigned long injectedPid) {
        // Ping the injected row so the user sees which pid got the DLL.
        // The 1.4s yellow ring needs a few frames; hold the window briefly
        // (3000ms) before fading out so it is actually visible, then leave.
        if (ok && injectedPid) list_->markInjected(injectedPid);
        // Remember the pid we just stood up (early mode hands back the new one)
        // so the auto path does not pick it up as a fresh candidate.
        if (injectedPid) earlyHandled_.insert(injectedPid);
        if (ok && injectedPid && early) {
            // Green tag on the row + a balloon, so Early Mode leaving a trace is
            // not something you have to catch in a 2s animation.
            list_->markEarlyInjected(injectedPid);
            notify(QStringLiteral("OpenNilore"),
                   QStringLiteral("Early Mode injected (pid %1, mapped in before the game window)")
                       .arg(injectedPid));
        } else if (ok && injectedPid) {
            notify(QStringLiteral("OpenNilore"),
                   QStringLiteral("Injected into pid %1").arg(injectedPid));
        } else {
            notify(QStringLiteral("OpenNilore"),
                   QStringLiteral("Injection failed"));
        }
        if (early) {
            // Early Mode keeps watching instead of quitting: the instance we just
            // restarted only reappears in the list a few seconds later, and that
            // row (with its green tag) is the whole point of the feedback. It
            // also means a later launch gets taken over without restarting the
            // loader by hand.
            injectionInFlight_ = false;
            list_->setInteractive(true);
            if (timer_) timer_->start();
            return;
        }
        QTimer::singleShot(1200, this, [this]() {
            playExitThenQuit();
        });
    });
    overlay->start();
}

void MainWindow::closeEvent(QCloseEvent* e) {
    // Intercept the first close so we can play the fade-out; the fade's
    // finished handler calls qApp->quit which fires another close after
    // exiting_ is set, and we let that one through normally.
    if (exiting_) {
        QMainWindow::closeEvent(e);
        return;
    }
    e->ignore();
    playExitThenQuit();
}

void MainWindow::playExitThenQuit() {
    if (exiting_) return;
    exiting_ = true;
    if (timer_) timer_->stop();

    auto* fade = new QPropertyAnimation(this, "windowOpacity", this);
    fade->setDuration(280);
    fade->setStartValue(windowOpacity());
    fade->setEndValue(0.0);
    fade->setEasingCurve(QEasingCurve::InCubic);
    connect(fade, &QAbstractAnimation::finished,
            qApp, &QApplication::quit);
    fade->start(QAbstractAnimation::DeleteWhenStopped);
}

} // namespace loader
