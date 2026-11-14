#include "Sidebar.h"

#include "UiTheme.h"

#include <QButtonGroup>
#include <QPainter>
#include <QPainterPath>
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
        color: #939AA5;
        font-size: 12px;
        font-weight: 500;
    }
    QPushButton#navBtn:hover {
        background: rgba(255, 255, 255, 9);
        color: #E6EAF0;
    }
    QPushButton#navBtn:checked {
        background: rgba(255, 255, 255, 16);
        color: #FFFFFF;
    }
)qss";

enum class NavIcon { Syringe, Gear, Contrast };

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

// A circle split into a light and a dark half - reads as a contrast/theme mark.
void drawContrast(QPainter& p) {
    const QRectF box(1.5, 1.5, 11.0, 11.0);
    const QPointF c = box.center();

    QPainterPath light;
    light.moveTo(c.x(), box.top());
    light.arcTo(box, 90, 180);
    light.closeSubpath();

    p.save();
    p.setPen(Qt::NoPen);
    p.setBrush(QColor(0xE6, 0xEA, 0xF0));
    p.drawPath(light);
    p.restore();

    p.setPen(QPen(QColor(0x94, 0x9A, 0xA4), 1.2));
    p.setBrush(Qt::NoBrush);
    p.drawEllipse(c, 5.5, 5.5);
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
        else if (icon_ == NavIcon::Gear) drawGear(p);
        else                           drawContrast(p);
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
    auto* theme = new NavButton(NavIcon::Contrast, QStringLiteral("Theme"), this);
    theme->setObjectName("navBtn");
    theme->setCheckable(true);

    for (auto* b : {inject, settings, theme}) {
        b->setCursor(Qt::PointingHandCursor);
        b->setFixedHeight(kNavHeight);
    }
    col->addWidget(inject);
    col->addSpacing(2);
    col->addWidget(settings);
    col->addSpacing(2);
    col->addWidget(theme);
    col->addStretch(1);

    group_ = new QButtonGroup(this);
    group_->setExclusive(true);
    group_->addButton(inject, 0);
    group_->addButton(settings, 1);
    group_->addButton(theme, 2);

    connect(inject,   &QPushButton::clicked, this, &Sidebar::injectClicked);
    connect(settings, &QPushButton::clicked, this, &Sidebar::settingsClicked);
    connect(theme,    &QPushButton::clicked, this, &Sidebar::themeClicked);
}

} // namespace loader
