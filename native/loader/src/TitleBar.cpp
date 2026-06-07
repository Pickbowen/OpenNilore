#include "TitleBar.h"

#include <QEvent>
#include <QHBoxLayout>
#include <QLabel>
#include <QLinearGradient>
#include <QMouseEvent>
#include <QPainter>
#include <QPropertyAnimation>
#include <QToolButton>
#include <QWindow>

namespace loader {

namespace {
constexpr int kBarHeight = 38;

const char* kCommonBtnQss = R"qss(
    QToolButton {
        background: transparent;
        border: none;
        color: #aab1bf;
        min-width: 46px;
        min-height: 38px;
        font-size: 14px;
        font-family: "Segoe UI", "Microsoft YaHei UI", sans-serif;
    }
    QToolButton:hover {
        background: rgba(255, 255, 255, 18);
        color: #ffffff;
    }
    QToolButton:pressed {
        background: rgba(255, 255, 255, 28);
    }
)qss";

const char* kCloseBtnQss = R"qss(
    QToolButton {
        background: transparent;
        border: none;
        color: #aab1bf;
        min-width: 46px;
        min-height: 38px;
        font-size: 14px;
        font-family: "Segoe UI", "Microsoft YaHei UI", sans-serif;
    }
    QToolButton:hover {
        background: #c0392b;
        color: #ffffff;
    }
    QToolButton:pressed {
        background: #8a2920;
    }
)qss";
} // namespace

TitleBar::TitleBar(QWidget* parent)
        : QWidget(parent) {
    setFixedHeight(kBarHeight);
    setAttribute(Qt::WA_StyledBackground, false);
    setAutoFillBackground(false);

    auto* layout = new QHBoxLayout(this);
    layout->setContentsMargins(14, 0, 0, 0);
    layout->setSpacing(10);

    // Pulsing scan indicator: a small dot painted directly in paintEvent so
    // we can drive it with a QPropertyAnimation on the pulse_ property.
    // Reserve space here so the title label lines up consistently.
    auto* dotSpacer = new QWidget(this);
    dotSpacer->setFixedSize(16, 16);
    layout->addWidget(dotSpacer);

    titleLabel_ = new QLabel(QStringLiteral("OpenZen Loader"), this);
    titleLabel_->setStyleSheet(QStringLiteral(
        "color: #e7ecf5; font-weight: 600; font-size: 12px;"
        "font-family: 'Segoe UI', 'Microsoft YaHei UI', sans-serif;"));
    layout->addWidget(titleLabel_);

    layout->addStretch(1);

    minBtn_ = new QToolButton(this);
    minBtn_->setText(QStringLiteral("–"));   // en-dash for minimize
    minBtn_->setCursor(Qt::PointingHandCursor);
    minBtn_->setStyleSheet(QString::fromUtf8(kCommonBtnQss));
    minBtn_->setToolTip(QStringLiteral("Minimize"));
    connect(minBtn_, &QToolButton::clicked, this, [this] {
        if (auto* w = window()) w->showMinimized();
    });
    layout->addWidget(minBtn_);

    closeBtn_ = new QToolButton(this);
    closeBtn_->setText(QStringLiteral("✕")); // multiplication sign
    closeBtn_->setCursor(Qt::PointingHandCursor);
    closeBtn_->setStyleSheet(QString::fromUtf8(kCloseBtnQss));
    closeBtn_->setToolTip(QStringLiteral("Close"));
    connect(closeBtn_, &QToolButton::clicked, this, [this] {
        if (auto* w = window()) w->close();
    });
    layout->addWidget(closeBtn_);

    // Looping pulse so the dot breathes; cheap (just one repaint per frame
    // step) and gives the UI a "still alive" cue.
    auto* pulseAnim = new QPropertyAnimation(this, "pulse", this);
    pulseAnim->setDuration(1800);
    pulseAnim->setStartValue(0.0);
    pulseAnim->setKeyValueAt(0.5, 1.0);
    pulseAnim->setEndValue(0.0);
    pulseAnim->setEasingCurve(QEasingCurve::InOutSine);
    pulseAnim->setLoopCount(-1);
    pulseAnim->start();
}

void TitleBar::setTitleText(const QString& text) {
    titleLabel_->setText(text);
}

void TitleBar::setPulse(qreal v) {
    pulse_ = v;
    update(QRect(0, 0, 38, height())); // only the dot area
}

void TitleBar::mousePressEvent(QMouseEvent* e) {
    if (e->button() == Qt::LeftButton) {
        if (auto* w = window()) {
            if (auto* h = w->windowHandle()) {
                h->startSystemMove();
                e->accept();
                return;
            }
        }
    }
    QWidget::mousePressEvent(e);
}

void TitleBar::paintEvent(QPaintEvent*) {
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);

    // Background: subtle top-down gradient, hairline at the bottom.
    QRect r = rect();
    QLinearGradient bg(r.topLeft(), r.bottomLeft());
    bg.setColorAt(0.0, QColor("#23252c"));
    bg.setColorAt(1.0, QColor("#1b1d22"));
    p.fillRect(r, bg);
    p.setPen(QColor(255, 255, 255, 14));
    p.drawLine(r.bottomLeft(), r.bottomRight());

    // Scan-status dot — solid core with a halo whose radius/alpha tracks
    // pulse_. The +2 nudge sits the dot a touch below the geometric centre
    // so it visually lines up with the cap-height of the title text.
    const QPointF c(20.0, r.center().y() + 1.5);
    const qreal halo = 5.0 + 5.0 * pulse_;
    const int   haloA = static_cast<int>(40 + 80 * pulse_);
    p.setPen(Qt::NoPen);
    p.setBrush(QColor(110, 200, 140, haloA));
    p.drawEllipse(c, halo, halo);
    p.setBrush(QColor(120, 230, 150));
    p.drawEllipse(c, 3.4, 3.4);
}

} // namespace loader
