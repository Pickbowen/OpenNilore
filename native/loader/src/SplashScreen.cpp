#include "SplashScreen.h"

#include "UiTheme.h"

#include <QApplication>
#include <QEasingCurve>
#include <QPainter>
#include <QPainterPath>
#include <QPropertyAnimation>
#include <QScreen>
#include <QSequentialAnimationGroup>

namespace loader {

namespace {
constexpr int kSize   = 76;
constexpr int kRadius = 18;
constexpr qreal kSpinnerRadius = 11.0;
constexpr qreal kSpinnerWidth  = 2.0;
} // namespace

SplashScreen::SplashScreen(QWidget* parent)
        : QWidget(parent) {
    setWindowFlags(Qt::FramelessWindowHint
                   | Qt::Tool
                   | Qt::WindowStaysOnTopHint);
    setAttribute(Qt::WA_TranslucentBackground);
    setAttribute(Qt::WA_DeleteOnClose);
    resize(kSize, kSize);

    if (auto* scr = QApplication::primaryScreen()) {
        QRect g = scr->availableGeometry();
        move(g.center() - QPoint(kSize / 2, kSize / 2));
    }

    setWindowOpacity(0.0);
}

void SplashScreen::start() {
    show();
    raise();
    activateWindow();

    auto* spin = new QPropertyAnimation(this, "spinnerAngle", this);
    spin->setDuration(900);
    spin->setStartValue(0.0);
    spin->setEndValue(360.0);
    spin->setLoopCount(-1);
    spin->start();

    auto* fadeIn = new QPropertyAnimation(this, "windowOpacity", this);
    fadeIn->setDuration(150);
    fadeIn->setStartValue(0.0);
    fadeIn->setEndValue(1.0);
    fadeIn->setEasingCurve(QEasingCurve::OutCubic);

    auto* fadeOut = new QPropertyAnimation(this, "windowOpacity", this);
    fadeOut->setDuration(200);
    fadeOut->setStartValue(1.0);
    fadeOut->setEndValue(0.0);
    fadeOut->setEasingCurve(QEasingCurve::InCubic);

    auto* seq = new QSequentialAnimationGroup(this);
    seq->addAnimation(fadeIn);
    seq->addPause(560);
    seq->addAnimation(fadeOut);

    connect(seq, &QAbstractAnimation::finished, this, [this] {
        emit finished();
        close();
    });
    seq->start(QAbstractAnimation::DeleteWhenStopped);
}

void SplashScreen::paintEvent(QPaintEvent*) {
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);

    const QRectF r = QRectF(this->rect()).adjusted(0.5, 0.5, -0.5, -0.5);
    QPainterPath panel;
    panel.addRoundedRect(r, kRadius, kRadius);
    p.fillPath(panel, ui::bgCard());

    const QPointF c = r.center();

    QPen track(QColor(255, 255, 255, 22), kSpinnerWidth);
    track.setCapStyle(Qt::RoundCap);
    p.setPen(track);
    p.setBrush(Qt::NoBrush);
    p.drawEllipse(c, kSpinnerRadius, kSpinnerRadius);

    QPen arc(ui::accent(), kSpinnerWidth);
    arc.setCapStyle(Qt::RoundCap);
    p.setPen(arc);
    const QRectF arcRect(c.x() - kSpinnerRadius, c.y() - kSpinnerRadius,
                         kSpinnerRadius * 2, kSpinnerRadius * 2);
    p.drawArc(arcRect, static_cast<int>(-spinnerAngle_ * 16), -100 * 16);
}

} // namespace loader
