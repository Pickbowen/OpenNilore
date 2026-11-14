#include "InstanceRow.h"

#include "UiTheme.h"

#include <QEasingCurve>
#include <QEnterEvent>
#include <QFontMetricsF>
#include <QHBoxLayout>
#include <QLabel>
#include <QPainter>
#include <QPainterPath>
#include <QPropertyAnimation>
#include <QPushButton>

namespace loader {

namespace {
constexpr int kRowHeight    = ui::kRowHeight;
constexpr int kCornerRadius = ui::kRadiusCard;

const char* kInjectBtnQss = R"qss(
    QPushButton {
        background: #5C9DFF;
        color: #FFFFFF;
        border: none;
        border-radius: 6px;
        padding: 0 14px;
        font-size: 12px;
        font-weight: 600;
    }
    QPushButton:hover {
        background: #70AAFF;
    }
    QPushButton:pressed {
        background: #4A88E6;
    }
    QPushButton:disabled {
        background: #2A2F35;
        color: #5E656F;
    }
)qss";

int lerp(int a, int b, qreal t) {
    return int(a + (b - a) * t);
}
} // namespace

InstanceRow::InstanceRow(unsigned long pid, const QString& title,
                         const QString& commandLine, QWidget* parent)
        : QFrame(parent), pid_(pid), title_(title), commandLine_(commandLine) {
    setFixedHeight(kRowHeight);
    setAttribute(Qt::WA_StyledBackground, false);
    setMouseTracking(true);

    auto* layout = new QHBoxLayout(this);
    layout->setContentsMargins(ui::kCardPadX, 0, 10, 0);
    layout->setSpacing(12);

    pidLabel_ = new QLabel(QString::number(pid_), this);
    pidLabel_->setFixedWidth(64);
    pidLabel_->setAlignment(Qt::AlignVCenter | Qt::AlignLeft);
    pidLabel_->setStyleSheet(QStringLiteral(
        "color: #5E656F;"
        "font-family: 'Consolas';"
        "font-size: 11px;"));
    layout->addWidget(pidLabel_);

    titleLabel_ = new QLabel(title_, this);
    titleLabel_->setAlignment(Qt::AlignVCenter | Qt::AlignLeft);
    titleLabel_->setStyleSheet(QStringLiteral(
        "color: #E6EAF0;"
        "font-size: 13px;"
        "font-family: 'Segoe UI', 'Microsoft YaHei UI', sans-serif;"));
    titleLabel_->setTextInteractionFlags(Qt::NoTextInteraction);
    layout->addWidget(titleLabel_, 1);

    nativeBadge_ = new QLabel(QStringLiteral("Native Bypass"), this);
    nativeBadge_->setAlignment(Qt::AlignVCenter | Qt::AlignLeft);
    nativeBadge_->setStyleSheet(QStringLiteral(
        "color: #4ADE80;"
        "background: rgba(74, 222, 128, 26);"
        "border-radius: 5px;"
        "padding: 2px 7px;"
        "font-size: 11px;"));
    nativeBadge_->setVisible(false);
    layout->addWidget(nativeBadge_, 0, Qt::AlignVCenter);

    injectBtn_ = new QPushButton(QStringLiteral("Inject"), this);
    injectBtn_->setCursor(Qt::PointingHandCursor);
    injectBtn_->setStyleSheet(QString::fromUtf8(kInjectBtnQss));
    injectBtn_->setFixedHeight(28);
    connect(injectBtn_, &QPushButton::clicked, this, [this] {
        emit injectClicked(pid_, title_, commandLine_);
    });
    layout->addWidget(injectBtn_);
}

void InstanceRow::updateTitle(const QString& title) {
    if (title == title_) return;
    title_ = title;
    titleLabel_->setText(title_);
}

void InstanceRow::setNativeBypassed(bool on) {
    if (nativeBadge_) {
        nativeBadge_->setVisible(on);
    }
}

void InstanceRow::setEntrance(qreal v) {
    entrance_ = v;
    update();
}

void InstanceRow::playEntrance() {
    entrance_ = 0.0;
    update();

    auto* anim = new QPropertyAnimation(this, "entrance", this);
    anim->setDuration(220);
    anim->setStartValue(0.0);
    anim->setEndValue(1.0);
    anim->setEasingCurve(QEasingCurve::OutCubic);
    anim->start(QAbstractAnimation::DeleteWhenStopped);
}

void InstanceRow::animateHover(qreal target) {
    if (hoverAnim_) {
        hoverAnim_->stop();
        hoverAnim_->deleteLater();
    }
    hoverAnim_ = new QPropertyAnimation(this, "hoverIntensity", this);
    hoverAnim_->setDuration(120);
    hoverAnim_->setStartValue(hoverIntensity_);
    hoverAnim_->setEndValue(target);
    hoverAnim_->setEasingCurve(QEasingCurve::OutCubic);
    hoverAnim_->start();
}

void InstanceRow::flashHighlight() {
    if (highlightAnim_) {
        highlightAnim_->stop();
        highlightAnim_->deleteLater();
    }
    highlightAnim_ = new QPropertyAnimation(this, "highlight", this);
    highlightAnim_->setDuration(1400);
    highlightAnim_->setStartValue(0.0);
    highlightAnim_->setKeyValueAt(0.25, 1.0);
    highlightAnim_->setKeyValueAt(0.7, 1.0);
    highlightAnim_->setEndValue(0.0);
    highlightAnim_->setEasingCurve(QEasingCurve::OutCubic);
    highlightAnim_->start();
    update();
}

void InstanceRow::enterEvent(QEnterEvent*) {
    animateHover(1.0);
}

void InstanceRow::leaveEvent(QEvent*) {
    animateHover(0.0);
}

void InstanceRow::paintEvent(QPaintEvent*) {
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);

    const qreal slideDy    = (1.0 - entrance_) * 6.0;
    const qreal panelAlpha = entrance_;

    const QRectF r = QRectF(rect()).adjusted(0, slideDy, 0, slideDy);
    QPainterPath panel;
    panel.addRoundedRect(r, kCornerRadius, kCornerRadius);

    const QColor card   = ui::bgCard();
    const QColor raised = ui::bgRaised();
    const QColor base(lerp(card.red(),   raised.red(),   hoverIntensity_),
                      lerp(card.green(), raised.green(), hoverIntensity_),
                      lerp(card.blue(),  raised.blue(),  hoverIntensity_),
                      int(255 * panelAlpha));
    p.fillPath(panel, base);

    const int borderAlpha = int((10 + 16 * hoverIntensity_) * panelAlpha);
    p.setPen(QPen(QColor(255, 255, 255, borderAlpha), 1));
    p.setBrush(Qt::NoBrush);
    p.drawPath(panel);

    if (highlight_ > 0.001) {
        QColor ring = ui::success();
        ring.setAlpha(int(210 * highlight_));
        p.setPen(QPen(ring, 1.5));
        p.setBrush(Qt::NoBrush);
        p.drawRoundedRect(r.adjusted(0.5, 0.5, -0.5, -0.5),
                          kCornerRadius, kCornerRadius);
    }
}

} // namespace loader
