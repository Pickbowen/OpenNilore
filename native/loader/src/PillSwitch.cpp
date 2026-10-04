#include "PillSwitch.h"

#include <QMouseEvent>
#include <QPainter>
#include <QPropertyAnimation>

namespace loader {

namespace {
constexpr int kTrackW = 44;
constexpr int kTrackH = 24;
constexpr int kKnob   = 20;      // knob diameter, 2px inset on each side
constexpr int kPadX   = 2;       // track padding to the knob
} // namespace

PillSwitch::PillSwitch(QWidget* parent)
        : QWidget(parent) {
    setFixedSize(kTrackW, kTrackH);
    setCursor(Qt::PointingHandCursor);
}

void PillSwitch::setChecked(bool on) {
    if (on == checked_) return;
    checked_ = on;
    animateTo(on ? 1.0 : 0.0);
    emit toggled(checked_);
}

void PillSwitch::setKnobPos(qreal v) {
    knobPos_ = v;
    update();
}

void PillSwitch::animateTo(qreal target) {
    auto* anim = new QPropertyAnimation(this, "knobPos", this);
    anim->setDuration(180);
    anim->setStartValue(knobPos_);
    anim->setEndValue(target);
    anim->start(QAbstractAnimation::DeleteWhenStopped);
}

void PillSwitch::mousePressEvent(QMouseEvent* e) {
    if (e->button() != Qt::LeftButton) {
        QWidget::mousePressEvent(e);
        return;
    }
    setChecked(!checked_);
    e->accept();
}

void PillSwitch::paintEvent(QPaintEvent*) {
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);

    const QRectF track(0.5, 0.5, kTrackW - 1, kTrackH - 1);
    p.setPen(Qt::NoPen);
    p.setBrush(checked_ ? QColor("#3d6fd1") : QColor("#272a31"));
    p.drawRoundedRect(track, kTrackH / 2.0, kTrackH / 2.0);

    // Sliding knob with a soft highlight.
    const qreal x = kPadX + knobPos_ * (kTrackW - 2 * kPadX - kKnob);
    const QRectF knob(x, (kTrackH - kKnob) / 2.0, kKnob, kKnob);
    p.setBrush(QColor("#ffffff"));
    p.drawEllipse(knob);

    // Tiny inner shadow for depth.
    QLinearGradient g(knob.topLeft(), knob.bottomLeft());
    g.setColorAt(0.0, QColor(255, 255, 255, 255));
    g.setColorAt(1.0, QColor(220, 224, 232, 255));
    p.setBrush(g);
    p.drawEllipse(knob);
}

} // namespace loader