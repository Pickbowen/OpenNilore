#pragma once

#include <QWidget>

class QLabel;
class QToolButton;

namespace loader {

// Custom title bar used by the frameless MainWindow. Owns the move-by-drag
// behaviour, the minimize/close buttons, and a small pulsing scan indicator
// that signals the auto-refresh loop is live.
class TitleBar : public QWidget {
    Q_OBJECT
    Q_PROPERTY(qreal pulse READ pulse WRITE setPulse)

public:
    explicit TitleBar(QWidget* parent = nullptr);

    void setTitleText(const QString& text);

    qreal pulse() const { return pulse_; }
    void  setPulse(qreal v);

protected:
    void mousePressEvent(QMouseEvent*) override;
    void paintEvent(QPaintEvent*) override;

private:
    QLabel*      titleLabel_ = nullptr;
    QToolButton* minBtn_     = nullptr;
    QToolButton* closeBtn_   = nullptr;
    qreal        pulse_      = 0.0;
};

} // namespace loader
