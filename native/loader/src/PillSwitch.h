#pragma once

#include <QWidget>

// Animated iOS-style pill switch. Self-contained: paints the track and a
// sliding knob, animates the knob with QPropertyAnimation, toggles on mouse
// press. Owned by its parent (never reparented / deleted manually) so Qt
// manages the lifetime - avoids the widget-ownership race that crashed the
// earlier sidebar attempt.

namespace loader {

class PillSwitch : public QWidget {
    Q_OBJECT
    Q_PROPERTY(qreal knobPos READ knobPos WRITE setKnobPos)
public:
    explicit PillSwitch(QWidget* parent = nullptr);

    bool isChecked() const { return checked_; }
    void setChecked(bool on);   // stores state + animates knob to match

    qreal knobPos() const { return knobPos_; }
    void  setKnobPos(qreal v);

signals:
    void toggled(bool checked);

protected:
    void paintEvent(QPaintEvent*) override;
    void mousePressEvent(QMouseEvent*) override;

private:
    void animateTo(qreal target);

    bool  checked_ = false;
    qreal knobPos_ = 0.0;   // 0 = left (off), 1 = right (on)
};

} // namespace loader