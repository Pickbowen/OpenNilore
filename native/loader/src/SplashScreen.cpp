#include "SplashScreen.h"

#include <QApplication>
#include <QEasingCurve>
#include <QFont>
#include <QFontMetricsF>
#include <QLinearGradient>
#include <QPainter>
#include <QPainterPath>
#include <QParallelAnimationGroup>
#include <QPropertyAnimation>
#include <QRadialGradient>
#include <QScreen>
#include <QSequentialAnimationGroup>

namespace loader {

namespace {
constexpr int kWidth  = 520;
constexpr int kHeight = 280;
constexpr int kCornerRadius = 18;
} // namespace

SplashScreen::SplashScreen(QWidget* parent)
        : QWidget(parent) {
    setWindowFlags(Qt::FramelessWindowHint
                   | Qt::Tool
                   | Qt::WindowStaysOnTopHint);
    setAttribute(Qt::WA_TranslucentBackground);
    setAttribute(Qt::WA_DeleteOnClose);
    resize(kWidth, kHeight);

    if (auto* scr = QApplication::primaryScreen()) {
        QRect g = scr->availableGeometry();
        move(g.center() - QPoint(kWidth / 2, kHeight / 2));
    }

    setWindowOpacity(0.0);
}

void SplashScreen::start() {
    show();
    raise();
    activateWindow();

    // Whole sequence is ~950ms - "open, sweep, gone" rather than a long
    // intro. All visuals run in parallel inside phase1, then a short pause
    // and a quick fade-out.
    auto* winFadeIn = new QPropertyAnimation(this, "windowOpacity", this);
    winFadeIn->setDuration(180);
    winFadeIn->setStartValue(0.0);
    winFadeIn->setEndValue(1.0);
    winFadeIn->setEasingCurve(QEasingCurve::OutCubic);

    auto* logoAlpha = new QPropertyAnimation(this, "logoOpacity", this);
    logoAlpha->setDuration(320);
    logoAlpha->setStartValue(0.0);
    logoAlpha->setEndValue(1.0);
    logoAlpha->setEasingCurve(QEasingCurve::OutCubic);

    auto* logoScale = new QPropertyAnimation(this, "logoScale", this);
    logoScale->setDuration(480);
    logoScale->setStartValue(0.55);
    logoScale->setEndValue(1.0);
    logoScale->setEasingCurve(QEasingCurve::OutBack);

    auto* glow = new QPropertyAnimation(this, "glowPulse", this);
    glow->setDuration(600);
    glow->setStartValue(0.0);
    glow->setKeyValueAt(0.5, 1.0);
    glow->setEndValue(0.35);
    glow->setEasingCurve(QEasingCurve::InOutSine);

    auto* scan = new QPropertyAnimation(this, "scanProgress", this);
    scan->setDuration(580);
    scan->setStartValue(0.0);
    scan->setEndValue(1.0);
    scan->setEasingCurve(QEasingCurve::InOutQuad);

    auto* phase = new QParallelAnimationGroup(this);
    phase->addAnimation(winFadeIn);
    phase->addAnimation(logoAlpha);
    phase->addAnimation(logoScale);
    phase->addAnimation(glow);
    phase->addAnimation(scan);

    auto* winFadeOut = new QPropertyAnimation(this, "windowOpacity", this);
    winFadeOut->setDuration(220);
    winFadeOut->setStartValue(1.0);
    winFadeOut->setEndValue(0.0);
    winFadeOut->setEasingCurve(QEasingCurve::InCubic);

    auto* seq = new QSequentialAnimationGroup(this);
    seq->addAnimation(phase);
    seq->addPause(100);
    seq->addAnimation(winFadeOut);

    connect(seq, &QAbstractAnimation::finished, this, [this] {
        emit finished();
        close();
    });
    seq->start(QAbstractAnimation::DeleteWhenStopped);
}

void SplashScreen::paintEvent(QPaintEvent*) {
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);
    p.setRenderHint(QPainter::TextAntialiasing);

    QRectF rect = this->rect().adjusted(0.5, 0.5, -0.5, -0.5);
    QPainterPath panel;
    panel.addRoundedRect(rect, kCornerRadius, kCornerRadius);
    p.setClipPath(panel);

    // Layered background: dark vertical gradient + accent glow.
    QLinearGradient bg(rect.topLeft(), rect.bottomLeft());
    bg.setColorAt(0.0, QColor("#16181f"));
    bg.setColorAt(1.0, QColor("#0a0b0e"));
    p.fillRect(rect, bg);

    // Accent glow behind the wordmark; pulses with glowPulse_.
    {
        qreal r = rect.width() * (0.45 + 0.07 * glowPulse_);
        QRadialGradient glow(rect.center() + QPointF(0, -4), r);
        int peak = static_cast<int>(120 * (0.45 + 0.55 * glowPulse_));
        glow.setColorAt(0.0, QColor(85, 135, 235, peak));
        glow.setColorAt(0.7, QColor(85, 135, 235, peak / 4));
        glow.setColorAt(1.0, QColor(85, 135, 235, 0));
        p.fillRect(rect, glow);
    }

    // Wordmark: drawn via a transform so we can scale around the centre.
    {
        QFont logoFont(QStringLiteral("Segoe UI"));
        logoFont.setPointSize(40);
        logoFont.setBold(true);
        logoFont.setLetterSpacing(QFont::PercentageSpacing, 102.0);
        p.setFont(logoFont);

        QFontMetricsF fm(logoFont);
        const QString text = QStringLiteral("OpenZen");
        const qreal textW = fm.horizontalAdvance(text);
        const qreal textH = fm.ascent();
        QPointF anchor = rect.center();

        p.save();
        p.translate(anchor);
        p.scale(logoScale_, logoScale_);
        p.setOpacity(logoOpacity_);
        // Soft drop shadow for legibility against the glow.
        p.setPen(QColor(0, 0, 0, 90));
        p.drawText(QPointF(-textW / 2.0 + 1, textH / 2.0 - 6 + 1), text);
        p.setPen(QColor("#ffffff"));
        p.drawText(QPointF(-textW / 2.0,     textH / 2.0 - 6),     text);
        p.restore();
    }

    // Scan rail near the bottom: faint baseline with a bright animated head
    // and a trailing gradient.
    {
        const qreal trackY  = rect.bottom() - 38;
        const qreal trackL  = rect.left()   + 70;
        const qreal trackR  = rect.right()  - 70;
        const qreal trackW  = trackR - trackL;

        p.setPen(QPen(QColor(255, 255, 255, 30), 1));
        p.drawLine(QPointF(trackL, trackY), QPointF(trackR, trackY));

        const qreal headX = trackL + trackW * scanProgress_;
        const qreal tailStart = std::max(headX - 140.0, trackL);
        QLinearGradient trail(QPointF(tailStart, 0), QPointF(headX, 0));
        trail.setColorAt(0.0, QColor(80, 130, 230, 0));
        trail.setColorAt(1.0, QColor(130, 180, 255, 230));
        p.setPen(QPen(QBrush(trail), 2));
        p.drawLine(QPointF(tailStart, trackY), QPointF(headX, trackY));

        // Bright "head" dot.
        p.setPen(Qt::NoPen);
        p.setBrush(QColor(180, 210, 255, 230));
        p.drawEllipse(QPointF(headX, trackY), 3.5, 3.5);
    }

    // Hairline border on top to crisp up the panel.
    p.setClipping(false);
    p.setPen(QPen(QColor(255, 255, 255, 28), 1));
    p.setBrush(Qt::NoBrush);
    p.drawPath(panel);
}

} // namespace loader
