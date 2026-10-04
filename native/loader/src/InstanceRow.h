#pragma once

#include <QFrame>
#include <QString>

class QLabel;
class QPushButton;
class QPropertyAnimation;

namespace loader {

// A single instance row inside InstanceList. Self-paints a rounded panel,
// hosts PID / title labels and an Inject button on the right. Hover
// transitions are driven by a QPropertyAnimation on hoverIntensity_; the
// row's own entrance is driven by entrance_ (opacity * vertical offset).
class InstanceRow : public QFrame {
    Q_OBJECT
    Q_PROPERTY(qreal hoverIntensity READ hoverIntensity WRITE setHoverIntensity)
    Q_PROPERTY(qreal entrance       READ entrance       WRITE setEntrance)
    Q_PROPERTY(qreal highlight       READ highlight       WRITE setHighlight)

public:
    InstanceRow(unsigned long pid, const QString& title, const QString& commandLine,
                QWidget* parent = nullptr);

    unsigned long pid() const { return pid_; }
    const QString& commandLine() const { return commandLine_; }
    const QString& title() const { return title_; }
    void updateTitle(const QString& title);

    qreal hoverIntensity() const { return hoverIntensity_; }
    void  setHoverIntensity(qreal v) { hoverIntensity_ = v; update(); }

    qreal entrance() const { return entrance_; }
    void  setEntrance(qreal v);

    qreal highlight() const { return highlight_; }
    void  setHighlight(qreal v) { highlight_ = v; update(); }

    // Flash a yellow outline ring around the row (drawn in paintEvent while
    // highlight_ fades in/out).
    void flashHighlight();

    void playEntrance();

signals:
    void injectClicked(unsigned long pid, const QString& title, const QString& commandLine);

protected:
    void enterEvent(QEnterEvent*) override;
    void leaveEvent(QEvent*) override;
    void paintEvent(QPaintEvent*) override;

private:
    void animateHover(qreal target);

    unsigned long pid_;
    QString       title_;
    QString       commandLine_;
    QLabel*       pidLabel_   = nullptr;
    QLabel*       titleLabel_ = nullptr;
    QPushButton*  injectBtn_  = nullptr;

    qreal hoverIntensity_ = 0.0;  // 0..1, drives background tint
    qreal entrance_       = 1.0;  // 0..1, drives opacity + slide-in offset
    qreal highlight_      = 0.0;  // 0..1, drives yellow indicator strength

    QPropertyAnimation* hoverAnim_     = nullptr;
    QPropertyAnimation* highlightAnim_ = nullptr;
};

} // namespace loader
