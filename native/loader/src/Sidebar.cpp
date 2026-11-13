#include "Sidebar.h"

#include "UiTheme.h"

#include <QButtonGroup>
#include <QPainter>
#include <QPushButton>
#include <QVBoxLayout>

#include <cmath>

namespace loader {

namespace {
constexpr int kSidebarWidth = ui::kSidebarWidth;
constexpr int kNavHeight    = ui::kNavHeight;
constexpr qreal kPi         = 3.14159265358979323846;

const char* kSidebarQss = R"qss(
    QWidget#sidebar {
        background: #121417;
    }
    QPushButton#navBtn {
        background: transparent;
        border: none;
        text-align: left;
        padding: 0 12px 0 34px;
        border-radius: 6px;
        color: #949AA4;
        font-size: 12px;
        font-weight: 500;
    }
    QPushButton#navBtn:hover {
        background: rgba(255, 255, 255, 9);
        color: #D2D6DC;
    }
    QPushButton#navBtn:checked {
        background: rgba(255, 255, 255, 16);
        color: #FFFFFF;
    }
)qss";

enum class NavIcon { Syringe, Gear };

void drawSyringe(QPainter& p) {
    p.save();
    p.translate(7.0, 7.2);
    p.rotate(-45.0);

    p.drawLine(QPointF(-6.0, 0.0), QPointF(-3.6, 0.0));
    p.drawRoundedRect(QRectF(-3.6, -2.0, 5.2, 4.0), 1.0, 1.0);
    p.drawLine(QPointF(-1.3, -1.1), QPointF(-1.3, 1.1));
    p.drawLine(QPointF(1.6, -2.8), QPointF(1.6, 2.8));
    p.drawLine(QPointF(1.6, 0.0), QPointF(3.7, 0.0));
    p.drawLine(QPointF(3.7, -1.5), QPointF(3.7, 1.5));

    p.restore();
}

void drawGear(QPainter& p) {
    const QPointF c(7.0, 7.0);
    p.drawEllipse(c, 4.3, 4.3);
    p.drawEllipse(c, 1.7, 1.7);
    for (int i = 0; i < 8; ++i) {
        const qreal a = i * kPi / 4.0;
        const qreal dx = std::cos(a);
        const qreal dy = std::sin(a);
        p.drawLine(QPointF(c.x() + dx * 4.3, c.y() + dy * 4.3),
                   QPointF(c.x() + dx * 6.0, c.y() + dy * 6.0));
    }
}

class NavButton : public QPushButton {
public:
    NavButton(NavIcon icon, const QString& text, QWidget* parent)
            : QPushButton(text, parent), icon_(icon) {}

protected:
    void paintEvent(QPaintEvent* e) override {
        QPushButton::paintEvent(e);

        QColor tone(0x94, 0x9A, 0xA4);
        if (isChecked())        tone = QColor(0xFF, 0xFF, 0xFF);
        else if (underMouse())  tone = QColor(0xD2, 0xD6, 0xDC);

        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        p.translate(12.0, (height() - 14.0) / 2.0);
        p.setPen(QPen(tone, 1.35, Qt::SolidLine, Qt::RoundCap, Qt::RoundJoin));
        p.setBrush(Qt::NoBrush);

        if (icon_ == NavIcon::Syringe) drawSyringe(p);
        else                           drawGear(p);
    }

private:
    NavIcon icon_;
};
} // namespace

Sidebar::Sidebar(QWidget* parent)
        : QWidget(parent) {
    setObjectName("sidebar");
    setStyleSheet(QString::fromUtf8(kSidebarQss));
    setFixedWidth(kSidebarWidth);
    buildUi();
}

void Sidebar::buildUi() {
    auto* col = new QVBoxLayout(this);
    col->setContentsMargins(8, 14, 8, 12);
    col->setSpacing(0);

    auto* inject = new NavButton(NavIcon::Syringe, QStringLiteral("Inject"), this);
    inject->setObjectName("navBtn");
    inject->setCheckable(true);
    inject->setChecked(true);
    auto* settings = new NavButton(NavIcon::Gear, QStringLiteral("Settings"), this);
    settings->setObjectName("navBtn");
    settings->setCheckable(true);

    for (auto* b : {inject, settings}) {
        b->setCursor(Qt::PointingHandCursor);
        b->setFixedHeight(kNavHeight);
    }
    col->addWidget(inject);
    col->addSpacing(2);
    col->addWidget(settings);
    col->addStretch(1);

    group_ = new QButtonGroup(this);
    group_->setExclusive(true);
    group_->addButton(inject, 0);
    group_->addButton(settings, 1);

    connect(inject,   &QPushButton::clicked, this, &Sidebar::injectClicked);
    connect(settings, &QPushButton::clicked, this, &Sidebar::settingsClicked);
}

} // namespace loader
