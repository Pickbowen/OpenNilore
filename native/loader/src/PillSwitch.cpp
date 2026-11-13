#include "PillSwitch.h"

#include "UiTheme.h"

#include <QEasingCurve>
#include <QMouseEvent>
#include <QPainter>
#include <QPropertyAnimation>

namespace loader {

namespace {
constexpr int kTrackW = 40;
constexpr int kTrackH = 22;
constexpr int kKnob   = 16;
constexpr int kPadX   = 2;

QColor blend(const QColor& a, const QColor& b, qreal t) {
    return QColor(int(a.red()   + (b.red()   - a.red())   * t),
                  int(a.green() + (b.green() - a.green()) * t),
                  int(a.blue()  + (b.blue()  - a.blue())  * t));
}
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
    anim->setDuration(160);
    anim->setStartValue(knobPos_);
    anim->setEndValue(target);
    anim->setEasingCurve(QEasingCurve::OutCubic);
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
    const qreal t = knobPos_;
    const QColor trackColor = blend(ui::bgControl(), ui::accent(), t);

    p.setPen(Qt::NoPen);
    p.setBrush(trackColor);
    p.drawRoundedRect(track, kTrackH / 2.0, kTrackH / 2.0);

    const int borderAlpha = int(12 * (1.0 - t));
    if (borderAlpha > 0) {
        p.setPen(QPen(QColor(255, 255, 255, borderAlpha), 1));
        p.setBrush(Qt::NoBrush);
        p.drawRoundedRect(track, kTrackH / 2.0, kTrackH / 2.0);
    }

    const qreal x = kPadX + t * (kTrackW - 2 * kPadX - kKnob);
    const QRectF knob(x, (kTrackH - kKnob) / 2.0, kKnob, kKnob);
    p.setPen(Qt::NoPen);
    p.setBrush(QColor(0xFF, 0xFF, 0xFF));
    p.drawEllipse(knob);
}

} // namespace loader
