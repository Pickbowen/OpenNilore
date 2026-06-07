#include "InstanceRow.h"

#include <QEasingCurve>
#include <QEnterEvent>
#include <QHBoxLayout>
#include <QLabel>
#include <QLinearGradient>
#include <QPainter>
#include <QPainterPath>
#include <QPropertyAnimation>
#include <QPushButton>

namespace loader {

namespace {
constexpr int kRowHeight   = 56;
constexpr int kCornerRadius = 9;

const char* kInjectBtnQss = R"qss(
    QPushButton {
        background: qlineargradient(x1:0, y1:0, x2:0, y2:1,
                                    stop:0 #4a83e0, stop:1 #3260bd);
        color: white;
        border: 1px solid rgba(255, 255, 255, 22);
        border-radius: 6px;
        padding: 6px 18px;
        font-weight: 600;
        font-size: 12px;
        font-family: "Segoe UI", "Microsoft YaHei UI", sans-serif;
    }
    QPushButton:hover {
        background: qlineargradient(x1:0, y1:0, x2:0, y2:1,
                                    stop:0 #5a93f0, stop:1 #4275d8);
        border: 1px solid rgba(255, 255, 255, 60);
    }
    QPushButton:pressed {
        background: qlineargradient(x1:0, y1:0, x2:0, y2:1,
                                    stop:0 #2c5db0, stop:1 #1e468f);
    }
    QPushButton:disabled {
        background: #2c2f37;
        color: #6a6f7a;
        border: 1px solid #2c2f37;
    }
)qss";
} // namespace

InstanceRow::InstanceRow(unsigned long pid, const QString& title, QWidget* parent)
        : QFrame(parent), pid_(pid), title_(title) {
    setFixedHeight(kRowHeight);
    setAttribute(Qt::WA_StyledBackground, false);
    setMouseTracking(true);

    auto* layout = new QHBoxLayout(this);
    // Leave a slight inner padding so the painted panel has visible breathing
    // room from the sibling rows above/below it.
    layout->setContentsMargins(14, 6, 12, 6);
    layout->setSpacing(12);

    pidLabel_ = new QLabel(QString::number(pid_), this);
    pidLabel_->setMinimumWidth(72);
    pidLabel_->setAlignment(Qt::AlignVCenter | Qt::AlignLeft);
    pidLabel_->setStyleSheet(QStringLiteral(
        "color: #9aa3b2;"
        "font-family: 'JetBrains Mono', 'Consolas', 'Segoe UI', monospace;"
        "font-size: 12px; font-weight: 600;"));
    layout->addWidget(pidLabel_);

    titleLabel_ = new QLabel(title_, this);
    titleLabel_->setAlignment(Qt::AlignVCenter | Qt::AlignLeft);
    titleLabel_->setStyleSheet(QStringLiteral(
        "color: #e7eaf2; font-size: 13px;"
        "font-family: 'Segoe UI', 'Microsoft YaHei UI', sans-serif;"));
    titleLabel_->setTextInteractionFlags(Qt::NoTextInteraction);
    layout->addWidget(titleLabel_, 1);

    injectBtn_ = new QPushButton(QStringLiteral("Inject"), this);
    injectBtn_->setCursor(Qt::PointingHandCursor);
    injectBtn_->setStyleSheet(QString::fromUtf8(kInjectBtnQss));
    injectBtn_->setFixedHeight(30);
    connect(injectBtn_, &QPushButton::clicked, this, [this] {
        emit injectClicked(pid_, title_);
    });
    layout->addWidget(injectBtn_);
}

void InstanceRow::updateTitle(const QString& title) {
    if (title == title_) return;
    title_ = title;
    titleLabel_->setText(title_);
}

void InstanceRow::setEntrance(qreal v) {
    entrance_ = v;
    // Slide-in vertical offset + opacity baked into a single property so we
    // can drive them with one animation.
    setWindowOpacity(1.0);                  // no-op on child widgets, kept for clarity
    auto* eff = graphicsEffect();
    Q_UNUSED(eff);
    update();
    // Translate by setting contents margins doesn't move us. Instead we
    // shift via a property-based geometry: simplest is to update an
    // internal offset that paintEvent honours. We just call update() and
    // let paintEvent read entrance_ to translate / fade.
}

void InstanceRow::playEntrance() {
    entrance_ = 0.0;
    update();

    auto* anim = new QPropertyAnimation(this, "entrance", this);
    anim->setDuration(280);
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
    hoverAnim_->setDuration(140);
    hoverAnim_->setStartValue(hoverIntensity_);
    hoverAnim_->setEndValue(target);
    hoverAnim_->setEasingCurve(QEasingCurve::OutCubic);
    hoverAnim_->start();
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

    // Combine entrance offset (slight slide up) + opacity in one go. The
    // labels and button still paint at their layout positions because they
    // are children with their own paint passes - so the visual effect of
    // entrance_ is limited to the painted background. To get the labels to
    // fade in too we apply a global opacity to ourselves via setWindowOpacity
    // (no-op for child widget), so instead drop opacity here only for the
    // panel; the labels appear "writing in" against a darker panel which
    // reads as a fade-in anyway in practice.
    qreal slideDy = (1.0 - entrance_) * 8.0;
    qreal panelAlpha = entrance_;

    QRectF rect = QRectF(this->rect()).adjusted(4, 4 + slideDy, -4, -4 + slideDy);
    QPainterPath panel;
    panel.addRoundedRect(rect, kCornerRadius, kCornerRadius);

    // Base fill is the dimmest; hoverIntensity_ blends in a brighter
    // gradient on top.
    QLinearGradient base(rect.topLeft(), rect.bottomLeft());
    base.setColorAt(0.0, QColor(38, 41, 48, int(255 * panelAlpha)));
    base.setColorAt(1.0, QColor(30, 32, 38, int(255 * panelAlpha)));
    p.fillPath(panel, base);

    if (hoverIntensity_ > 0.001) {
        QLinearGradient hover(rect.topLeft(), rect.bottomLeft());
        hover.setColorAt(0.0,
            QColor(74, 131, 224, int(38 * hoverIntensity_ * panelAlpha)));
        hover.setColorAt(1.0,
            QColor(50, 96, 189, int(22 * hoverIntensity_ * panelAlpha)));
        p.fillPath(panel, hover);
    }

    // Left accent stripe that brightens with hover.
    {
        QRectF stripe = rect;
        stripe.setRight(stripe.left() + 3);
        QColor stripeCol(85, 135, 235,
            int(60 + 160 * hoverIntensity_) * (panelAlpha < 0.999 ? panelAlpha : 1.0));
        QPainterPath sp;
        sp.addRoundedRect(stripe, 2.0, 2.0);
        p.fillPath(sp, stripeCol);
    }

    // Outline that brightens with hover.
    QPen border(QColor(255, 255, 255,
        int((22 + 36 * hoverIntensity_) * panelAlpha)), 1);
    p.setPen(border);
    p.setBrush(Qt::NoBrush);
    p.drawPath(panel);
}

} // namespace loader
