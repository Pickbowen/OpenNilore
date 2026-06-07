#include "MainWindow.h"

#include "InjectionOverlay.h"
#include "InstanceList.h"
#include "TitleBar.h"
#include "loader.h"

#include <QApplication>
#include <QCloseEvent>
#include <QEasingCurve>
#include <QHBoxLayout>
#include <QLabel>
#include <QLinearGradient>
#include <QPainter>
#include <QPainterPath>
#include <QParallelAnimationGroup>
#include <QPropertyAnimation>
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
    setMinimumSize(760, 500);
    resize(760, 500);

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
    const QString winTitle = QStringLiteral("OpenZen Loader  ·  build %1")
            .arg(QString::fromLatin1(OPENZEN_BUILD_REVISION).left(7));
#else
    const QString winTitle = QStringLiteral("OpenZen Loader");
#endif
    setWindowTitle(winTitle);

    auto* central = new QWidget(this);
    central->setAttribute(Qt::WA_TranslucentBackground);

    auto* root = new QVBoxLayout(central);
    root->setContentsMargins(0, 0, 0, 0);
    root->setSpacing(0);

    titleBar_ = new TitleBar(central);
    titleBar_->setTitleText(winTitle);
    root->addWidget(titleBar_);

    auto* body = new QWidget(central);
    body->setObjectName("body");
    body->setAttribute(Qt::WA_StyledBackground, false);
    auto* layout = new QVBoxLayout(body);
    layout->setContentsMargins(18, 14, 18, 14);
    layout->setSpacing(10);

    auto* title = new QLabel(QStringLiteral("Minecraft Instances"), body);
    title->setObjectName("title");

    hint_ = new QLabel(
        QStringLiteral("Click Inject on the instance you want to load OpenZen into. "
                       "List refreshes every second."),
        body);
    hint_->setObjectName("hint");
    hint_->setWordWrap(true);

    list_ = new InstanceList(body);
    connect(list_, &InstanceList::injectRequested,
            this, &MainWindow::onInjectRequested);

    status_ = new QLabel(QStringLiteral("Watching for Minecraft processes…"), body);
    status_->setObjectName("status");
    status_->setWordWrap(true);

    layout->addWidget(title);
    layout->addWidget(hint_);
    layout->addWidget(list_, 1);
    layout->addWidget(status_);
    root->addWidget(body, 1);

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

void MainWindow::refreshNow() {
    auto procs = list_java_processes();
    QVector<Instance> filtered;
    filtered.reserve(procs.size());
    for (const auto& jp : procs) {
        if (!isMinecraft(jp.window_title, jp.window_class)) continue;
        Instance item;
        item.pid = jp.pid;
        item.title = fromW(jp.window_title);
        if (item.title.isEmpty()) {
            item.title = QStringLiteral("(starting up — %1)")
                    .arg(fromW(jp.window_class));
        }
        filtered.push_back(std::move(item));
    }

    list_->setInstances(filtered);
    status_->setText(QStringLiteral("Watching %1 Minecraft instance(s).")
                     .arg(list_->count()));
}

void MainWindow::onInjectRequested(unsigned long pid, const QString& title) {
    if (injectionInFlight_) return;
    injectionInFlight_ = true;

    // Freeze the list so the user can't queue another injection while we're
    // waiting for the worker thread + animation to wrap up.
    list_->setInteractive(false);
    if (timer_) timer_->stop();

    auto* overlay = new InjectionOverlay(pid, title, this);
    connect(overlay, &InjectionOverlay::completed, this,
            [this](bool /*ok*/) { playExitThenQuit(); });
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
