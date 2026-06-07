#include "InjectionOverlay.h"

#include "loader.h"

#include <QApplication>
#include <QEasingCurve>
#include <QFont>
#include <QFontMetricsF>
#include <QLinearGradient>
#include <QPainter>
#include <QPainterPath>
#include <QPropertyAnimation>
#include <QRadialGradient>
#include <QScreen>
#include <QSequentialAnimationGroup>

#include <algorithm>
#include <thread>

namespace loader {

namespace {
constexpr int kW = 460;
constexpr int kH = 240;
constexpr int kCornerRadius = 16;
} // namespace

InjectionOverlay::InjectionOverlay(unsigned long pid, const QString& target,
                                   QWidget* parent)
        : QWidget(parent), pid_(pid), target_(target) {
    setWindowFlags(Qt::FramelessWindowHint
                   | Qt::Tool
                   | Qt::WindowStaysOnTopHint);
    setAttribute(Qt::WA_TranslucentBackground);
    setAttribute(Qt::WA_DeleteOnClose);
    resize(kW, kH);

    // Centre over the parent window if we have one, else the primary screen.
    if (parent) {
        QPoint pcentre = parent->geometry().center();
        move(pcentre - QPoint(kW / 2, kH / 2));
    } else if (auto* scr = QApplication::primaryScreen()) {
        QRect g = scr->availableGeometry();
        move(g.center() - QPoint(kW / 2, kH / 2));
    }
}

void InjectionOverlay::start() {
    show();
    raise();
    activateWindow();

    // Panel fade in.
    auto* panelFade = new QPropertyAnimation(this, "panelOpacity", this);
    panelFade->setDuration(220);
    panelFade->setStartValue(0.0);
    panelFade->setEndValue(1.0);
    panelFade->setEasingCurve(QEasingCurve::OutCubic);
    panelFade->start(QAbstractAnimation::DeleteWhenStopped);

    // Continuous spinner rotation.
    spinnerAnim_ = new QPropertyAnimation(this, "spinnerAngle", this);
    spinnerAnim_->setDuration(1100);
    spinnerAnim_->setStartValue(0.0);
    spinnerAnim_->setEndValue(360.0);
    spinnerAnim_->setLoopCount(-1);
    spinnerAnim_->start();

    // Progress crawls towards 70% while the inject worker runs; when the
    // worker returns we jump to 100% on a faster animation.
    progressAnim_ = new QPropertyAnimation(this, "progress", this);
    progressAnim_->setDuration(900);
    progressAnim_->setStartValue(0.0);
    progressAnim_->setEndValue(0.7);
    progressAnim_->setEasingCurve(QEasingCurve::OutCubic);
    progressAnim_->start();

    // Run the (synchronous, can-block) inject() on a worker thread so the
    // overlay stays animated. Use invokeMethod with a queued connection to
    // bounce the result back to the GUI thread.
    std::thread([this]() {
        std::wstring err = inject(pid_);
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

    if (spinnerAnim_)  spinnerAnim_->stop();
    if (progressAnim_) progressAnim_->stop();

    statusText_ = injectOk_
        ? QStringLiteral("Injection complete")
        : QStringLiteral("Injection failed: ") + injectErr_;
    update();

    // Finish progress bar quickly, then animate the success/failure mark
    // drawing in, hold a beat, fade out.
    auto* finish = new QPropertyAnimation(this, "progress", this);
    finish->setDuration(260);
    finish->setStartValue(progress_);
    finish->setEndValue(1.0);
    finish->setEasingCurve(QEasingCurve::OutCubic);

    auto* check = new QPropertyAnimation(this, "checkmarkProgress", this);
    check->setDuration(380);
    check->setStartValue(0.0);
    check->setEndValue(1.0);
    check->setEasingCurve(QEasingCurve::OutBack);

    auto* fadeOut = new QPropertyAnimation(this, "panelOpacity", this);
    fadeOut->setDuration(260);
    fadeOut->setStartValue(1.0);
    fadeOut->setEndValue(0.0);
    fadeOut->setEasingCurve(QEasingCurve::InCubic);

    auto* seq = new QSequentialAnimationGroup(this);
    seq->addAnimation(finish);
    seq->addAnimation(check);
    seq->addPause(700);
    seq->addAnimation(fadeOut);

    connect(seq, &QAbstractAnimation::finished, this, [this]() {
        emit completed(injectOk_);
        close();
    });
    seq->start(QAbstractAnimation::DeleteWhenStopped);
}

void InjectionOverlay::paintEvent(QPaintEvent*) {
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);
    p.setRenderHint(QPainter::TextAntialiasing);
    p.setOpacity(panelOpacity_);

    QRectF rect = QRectF(this->rect()).adjusted(0.5, 0.5, -0.5, -0.5);
    QPainterPath panel;
    panel.addRoundedRect(rect, kCornerRadius, kCornerRadius);
    p.setClipPath(panel);

    // Background.
    QLinearGradient bg(rect.topLeft(), rect.bottomLeft());
    bg.setColorAt(0.0, QColor("#1a1c22"));
    bg.setColorAt(1.0, QColor("#0e1014"));
    p.fillRect(rect, bg);

    // Accent glow behind the spinner.
    {
        QRadialGradient glow(rect.center() - QPointF(0, 30),
                             rect.width() * 0.5);
        glow.setColorAt(0.0, QColor(70, 130, 230, 90));
        glow.setColorAt(1.0, QColor(70, 130, 230, 0));
        p.fillRect(rect, glow);
    }

    // Spinner / status mark.
    const QPointF c(rect.center().x(), rect.top() + 72);
    const qreal r = 26.0;

    if (!injectDone_) {
        QPen ringPen(QColor(255, 255, 255, 30), 3);
        ringPen.setCapStyle(Qt::FlatCap);
        p.setPen(ringPen);
        p.setBrush(Qt::NoBrush);
        p.drawEllipse(c, r, r);

        QPen arcPen(QColor("#7fb0ff"), 3);
        arcPen.setCapStyle(Qt::RoundCap);
        p.setPen(arcPen);
        QRectF arcRect(c.x() - r, c.y() - r, r * 2, r * 2);
        int startAngle = static_cast<int>(-spinnerAngle_ * 16);
        int spanAngle  = -120 * 16;
        p.drawArc(arcRect, startAngle, spanAngle);
    } else {
        QColor accent = injectOk_ ? QColor("#3ecf6b") : QColor("#e25656");
        p.setPen(Qt::NoPen);
        p.setBrush(accent);
        p.drawEllipse(c, r, r);

        QPen mark(QColor("#ffffff"), 3);
        mark.setCapStyle(Qt::RoundCap);
        mark.setJoinStyle(Qt::RoundJoin);
        p.setPen(mark);
        p.setBrush(Qt::NoBrush);

        const qreal t = std::clamp(checkmarkProgress_, 0.0, 1.0);
        if (injectOk_) {
            // Two-segment checkmark, animated by t.
            QPointF a(c.x() - 11, c.y() + 1);
            QPointF b(c.x() - 2,  c.y() + 9);
            QPointF d(c.x() + 12, c.y() - 7);
            if (t <= 0.5) {
                qreal tt = t / 0.5;
                p.drawLine(a, a + (b - a) * tt);
            } else {
                p.drawLine(a, b);
                qreal tt = (t - 0.5) / 0.5;
                p.drawLine(b, b + (d - b) * tt);
            }
        } else {
            qreal off = 11.0 * t;
            p.drawLine(QPointF(c.x() - off, c.y() - off),
                       QPointF(c.x() + off, c.y() + off));
            p.drawLine(QPointF(c.x() + off, c.y() - off),
                       QPointF(c.x() - off, c.y() + off));
        }
    }

    // Status text.
    {
        QFont f(QStringLiteral("Segoe UI"));
        f.setPointSize(12);
        f.setBold(true);
        p.setFont(f);
        p.setPen(QColor("#e3e7ef"));
        QFontMetricsF fm(f);
        qreal tw = fm.horizontalAdvance(statusText_);
        p.drawText(QPointF(rect.center().x() - tw / 2, rect.top() + 140),
                   statusText_);
    }

    // Target subtitle.
    {
        QFont f(QStringLiteral("Segoe UI"));
        f.setPointSize(9);
        p.setFont(f);
        p.setPen(QColor("#8a90a0"));
        QFontMetricsF fm(f);
        QString sub = QStringLiteral("PID %1 · %2").arg(pid_).arg(target_);
        // Truncate overly long titles so the subtitle still fits inside
        // the panel.
        const qreal maxW = rect.width() - 60;
        if (fm.horizontalAdvance(sub) > maxW) {
            sub = fm.elidedText(sub, Qt::ElideMiddle, static_cast<int>(maxW));
        }
        qreal sw = fm.horizontalAdvance(sub);
        p.drawText(QPointF(rect.center().x() - sw / 2, rect.top() + 162),
                   sub);
    }

    // Progress bar.
    {
        const qreal pbY = rect.bottom() - 38;
        const qreal pbL = rect.left()   + 64;
        const qreal pbR = rect.right()  - 64;
        const qreal pbH = 4.0;

        QPainterPath track;
        track.addRoundedRect(QRectF(pbL, pbY - pbH / 2, pbR - pbL, pbH),
                             pbH / 2, pbH / 2);
        p.setPen(Qt::NoPen);
        p.setBrush(QColor(255, 255, 255, 22));
        p.drawPath(track);

        const qreal fillW = (pbR - pbL) * std::clamp(progress_, 0.0, 1.0);
        if (fillW > 0.5) {
            QPainterPath fill;
            fill.addRoundedRect(QRectF(pbL, pbY - pbH / 2, fillW, pbH),
                                pbH / 2, pbH / 2);
            QLinearGradient g(QPointF(pbL, 0), QPointF(pbR, 0));
            if (injectDone_ && !injectOk_) {
                g.setColorAt(0.0, QColor("#e25656"));
                g.setColorAt(1.0, QColor("#f08585"));
            } else {
                g.setColorAt(0.0, QColor("#3d6fd1"));
                g.setColorAt(1.0, QColor("#7fb0ff"));
            }
            p.setBrush(g);
            p.drawPath(fill);
        }
    }

    // Border.
    p.setClipping(false);
    p.setPen(QPen(QColor(255, 255, 255, 28), 1));
    p.setBrush(Qt::NoBrush);
    p.drawPath(panel);
}

} // namespace loader
