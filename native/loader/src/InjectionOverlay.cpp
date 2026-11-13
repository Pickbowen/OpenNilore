#include "InjectionOverlay.h"

#include "UiTheme.h"
#include "loader.h"
#include "loader_settings.h"

#include <QApplication>
#include <QEasingCurve>
#include <QFont>
#include <QFontMetricsF>
#include <QPainter>
#include <QPainterPath>
#include <QPropertyAnimation>
#include <QScreen>
#include <QSequentialAnimationGroup>

#include <algorithm>
#include <thread>

namespace loader {

namespace {
constexpr int   kH            = 84;
constexpr int   kMinW         = 208;
constexpr int   kMaxW         = 440;
constexpr int   kCornerRadius = ui::kRadiusWindow;
constexpr int   kPadX         = 24;
constexpr qreal kIconR        = 15.0;
constexpr qreal kTextGap      = 16.0;
} // namespace

InjectionOverlay::InjectionOverlay(unsigned long pid, const QString& target,
                                   const QString& commandLine,
                                   QWidget* parent)
        : QWidget(parent), pid_(pid), target_(target), commandLine_(commandLine) {
    setWindowFlags(Qt::FramelessWindowHint
                   | Qt::Tool
                   | Qt::WindowStaysOnTopHint);
    setAttribute(Qt::WA_TranslucentBackground);
    setAttribute(Qt::WA_DeleteOnClose);
    resize(preferredWidth(), kH);

    if (parent) {
        QPoint pcentre = parent->geometry().center();
        move(pcentre - QPoint(width() / 2, kH / 2));
    } else if (auto* scr = QApplication::primaryScreen()) {
        QRect g = scr->availableGeometry();
        move(g.center() - QPoint(width() / 2, kH / 2));
    }
}

QString InjectionOverlay::subtitle() const {
    if (injectDone_ && !injectOk_) return injectErr_;
    return QStringLiteral("PID %1 · %2").arg(pid_).arg(target_);
}

int InjectionOverlay::preferredWidth() const {
    QFont titleFont(ui::uiFontFamily());
    titleFont.setPixelSize(12);
    titleFont.setWeight(QFont::DemiBold);
    const QFontMetricsF titleMetrics(titleFont);

    QFont subFont(ui::uiFontFamily());
    subFont.setPixelSize(11);
    const QFontMetricsF subMetrics(subFont);

    const qreal textW = std::max(titleMetrics.horizontalAdvance(statusText_),
                                 subMetrics.horizontalAdvance(subtitle()));
    const int total = int(kPadX + kIconR * 2 + kTextGap + textW + kPadX + 2.5);
    return std::clamp(total, kMinW, kMaxW);
}

void InjectionOverlay::resizeCentered() {
    const int target = preferredWidth();
    if (target == width()) return;

    QPoint centre;
    if (QWidget* p = parentWidget()) {
        centre = p->geometry().center();
    } else if (auto* scr = QApplication::primaryScreen()) {
        centre = scr->availableGeometry().center();
    } else {
        centre = geometry().center();
    }
    resize(target, kH);
    move(centre - QPoint(target / 2, kH / 2));
}

void InjectionOverlay::start() {
    show();
    raise();
    activateWindow();

    auto* panelFade = new QPropertyAnimation(this, "panelOpacity", this);
    panelFade->setDuration(200);
    panelFade->setStartValue(0.0);
    panelFade->setEndValue(1.0);
    panelFade->setEasingCurve(QEasingCurve::OutCubic);
    panelFade->start(QAbstractAnimation::DeleteWhenStopped);

    spinnerAnim_ = new QPropertyAnimation(this, "spinnerAngle", this);
    spinnerAnim_->setDuration(900);
    spinnerAnim_->setStartValue(0.0);
    spinnerAnim_->setEndValue(360.0);
    spinnerAnim_->setLoopCount(-1);
    spinnerAnim_->start();

    std::thread([this]() {
        std::wstring err = inject(static_cast<DWORD>(pid_));
        QString qerr = QString::fromWCharArray(err.c_str(),
                                               static_cast<int>(err.size()));
        QMetaObject::invokeMethod(this, [this, qerr]() {
            onInjectResult(qerr);
        }, Qt::QueuedConnection);
    }).detach();
}

void InjectionOverlay::onInjectResult(QString err) {
    injectDone_ = true;
    injectOk_   = err.isEmpty();
    injectErr_  = err;

    if (spinnerAnim_) spinnerAnim_->stop();

    statusText_ = injectOk_
        ? QStringLiteral("Injection complete")
        : QStringLiteral("Injection failed");

    resizeCentered();
    update();

    auto* check = new QPropertyAnimation(this, "checkmarkProgress", this);
    check->setDuration(340);
    check->setStartValue(0.0);
    check->setEndValue(1.0);
    check->setEasingCurve(QEasingCurve::OutCubic);

    auto* fadeOut = new QPropertyAnimation(this, "panelOpacity", this);
    fadeOut->setDuration(240);
    fadeOut->setStartValue(1.0);
    fadeOut->setEndValue(0.0);
    fadeOut->setEasingCurve(QEasingCurve::InCubic);

    auto* seq = new QSequentialAnimationGroup(this);
    seq->addAnimation(check);
    seq->addPause(820);
    seq->addAnimation(fadeOut);

    connect(seq, &QAbstractAnimation::finished, this, [this]() {
        emit completed(injectOk_, pid_);
        close();
    });
    seq->start(QAbstractAnimation::DeleteWhenStopped);
}

void InjectionOverlay::paintEvent(QPaintEvent*) {
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);
    p.setRenderHint(QPainter::TextAntialiasing);
    p.setOpacity(panelOpacity_);

    const QRectF rect = QRectF(this->rect()).adjusted(0.5, 0.5, -0.5, -0.5);
    QPainterPath panel;
    panel.addRoundedRect(rect, kCornerRadius, kCornerRadius);
    p.fillPath(panel, ui::bgCard());

    const QPointF icon(kPadX + kIconR, kH / 2.0);

    if (!injectDone_) {
        QPen track(QColor(255, 255, 255, 30), 2.0);
        track.setCapStyle(Qt::FlatCap);
        p.setPen(track);
        p.setBrush(Qt::NoBrush);
        p.drawEllipse(icon, kIconR, kIconR);

        QPen arcPen(ui::accent(), 2.0);
        arcPen.setCapStyle(Qt::RoundCap);
        p.setPen(arcPen);
        const QRectF arcRect(icon.x() - kIconR, icon.y() - kIconR,
                             kIconR * 2, kIconR * 2);
        p.drawArc(arcRect, static_cast<int>(-spinnerAngle_ * 16), -110 * 16);
    } else {
        p.setPen(Qt::NoPen);
        p.setBrush(injectOk_ ? ui::success() : ui::danger());
        p.drawEllipse(icon, kIconR, kIconR);

        QPen stroke(QColor(0xFF, 0xFF, 0xFF), 2.2);
        stroke.setCapStyle(Qt::RoundCap);
        stroke.setJoinStyle(Qt::RoundJoin);
        p.setPen(stroke);
        p.setBrush(Qt::NoBrush);

        const qreal t = std::clamp(checkmarkProgress_, 0.0, 1.0);
        if (injectOk_) {
            const QPointF a(icon.x() - 6.0, icon.y() + 0.5);
            const QPointF b(icon.x() - 1.5, icon.y() + 5.0);
            const QPointF d(icon.x() + 6.5, icon.y() - 4.5);
            if (t <= 0.45) {
                p.drawLine(a, a + (b - a) * (t / 0.45));
            } else {
                p.drawLine(a, b);
                p.drawLine(b, b + (d - b) * ((t - 0.45) / 0.55));
            }
        } else {
            const qreal off = 5.5 * t;
            p.drawLine(QPointF(icon.x() - off, icon.y() - off),
                       QPointF(icon.x() + off, icon.y() + off));
            p.drawLine(QPointF(icon.x() + off, icon.y() - off),
                       QPointF(icon.x() - off, icon.y() + off));
        }
    }

    const qreal textX = kPadX + kIconR * 2 + kTextGap;

    QFont titleFont(ui::uiFontFamily());
    titleFont.setPixelSize(12);
    titleFont.setWeight(QFont::DemiBold);
    const QFontMetricsF titleMetrics(titleFont);

    QFont subFont(ui::uiFontFamily());
    subFont.setPixelSize(11);
    const QFontMetricsF subMetrics(subFont);

    const qreal blockH   = titleMetrics.height() + 5 + subMetrics.height();
    const qreal blockTop = (kH - blockH) / 2.0;

    p.setFont(titleFont);
    p.setPen(ui::textPrimary());
    p.drawText(QPointF(textX, blockTop + titleMetrics.ascent()), statusText_);

    QString sub = subtitle();
    const qreal maxW = rect.right() - kPadX - textX;
    if (subMetrics.horizontalAdvance(sub) > maxW) {
        sub = subMetrics.elidedText(sub, Qt::ElideMiddle, static_cast<int>(maxW));
    }

    p.setFont(subFont);
    p.setPen(ui::textMuted());
    p.drawText(QPointF(textX,
                       blockTop + titleMetrics.height() + 5 + subMetrics.ascent()),
               sub);
}

} // namespace loader
