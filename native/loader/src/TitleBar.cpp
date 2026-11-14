#include "TitleBar.h"

#include "UiTheme.h"

#include <QHBoxLayout>
#include <QLabel>
#include <QMouseEvent>
#include <QPainter>
#include <QPropertyAnimation>
#include <QToolButton>
#include <QWindow>

namespace loader {

namespace {
constexpr int kBarHeight   = ui::kTitleBarHeight;
constexpr int kButtonWidth = 44;
constexpr int kDotCx       = ui::kGutter;
constexpr int kDotR        = 3;

const char* kCommonBtnQss = R"qss(
    QToolButton {
        background: transparent;
        border: none;
        color: #5E656F;
        font-size: 13px;
    }
    QToolButton:hover {
        background: rgba(255, 255, 255, 12);
        color: #FFFFFF;
    }
    QToolButton:pressed {
        background: rgba(255, 255, 255, 22);
    }
)qss";

QString toolButtonStyle(const char* qss) {
    return QString::fromUtf8(qss);
}
} // namespace

TitleBar::TitleBar(QWidget* parent)
        : QWidget(parent) {
    setFixedHeight(kBarHeight);
    setAttribute(Qt::WA_StyledBackground, false);
    setAutoFillBackground(false);

    auto* layout = new QHBoxLayout(this);
    layout->setContentsMargins(kDotCx + kDotR * 2 + 10, 0, 0, 0);
    layout->setSpacing(0);

    titleLabel_ = new QLabel(QStringLiteral("OpenNilore Loader"), this);
    titleLabel_->setStyleSheet(QStringLiteral(
        "color: #939AA5; font-size: 12px; font-weight: 600;"));
    layout->addWidget(titleLabel_);

    layout->addStretch(1);

    minBtn_ = new QToolButton(this);
    minBtn_->setText(QStringLiteral("–"));
    minBtn_->setCursor(Qt::PointingHandCursor);
    minBtn_->setFixedSize(kButtonWidth, kBarHeight);
    minBtn_->setStyleSheet(toolButtonStyle(kCommonBtnQss));
    minBtn_->setToolTip(QStringLiteral("Minimize"));
    connect(minBtn_, &QToolButton::clicked, this, [this] {
        if (auto* w = window()) w->showMinimized();
    });
    layout->addWidget(minBtn_);

    closeBtn_ = new QToolButton(this);
    closeBtn_->setText(QStringLiteral("✕"));
    closeBtn_->setCursor(Qt::PointingHandCursor);
    closeBtn_->setFixedSize(kButtonWidth, kBarHeight);
    closeBtn_->setStyleSheet(toolButtonStyle(kCommonBtnQss));
    closeBtn_->setToolTip(QStringLiteral("Close"));
    connect(closeBtn_, &QToolButton::clicked, this, [this] {
        if (auto* w = window()) w->close();
    });
    layout->addWidget(closeBtn_);

    auto* pulseAnim = new QPropertyAnimation(this, "pulse", this);
    pulseAnim->setDuration(2000);
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
    update(QRect(kDotCx - kDotR - 3, 0, kDotR * 2 + 6, height()));
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

    const QRect r = rect();
    const QPointF c(kDotCx, r.center().y() + 0.5);
    QColor halo = ui::success();
    halo.setAlpha(static_cast<int>(26 + 54 * pulse_));
    const qreal haloR = kDotR + 2.0 + 2.0 * pulse_;

    p.setPen(Qt::NoPen);
    p.setBrush(halo);
    p.drawEllipse(c, haloR, haloR);
    p.setBrush(ui::success());
    p.drawEllipse(c, kDotR, kDotR);
}

} // namespace loader
