#include "Sidebar.h"

#include <QButtonGroup>
#include <QLabel>
#include <QPushButton>
#include <QVBoxLayout>

namespace loader {

namespace {
constexpr int kSidebarWidth = 200;

const char* kSidebarQss = R"qss(
    QWidget#sidebar {
        background: #191b20;
        border-right: 1px solid #26282f;
    }
    QPushButton#navBtn {
        background: transparent;
        border: none;
        text-align: left;
        padding: 10px 16px;
        border-radius: 8px;
        color: #9aa2b0;
        font-size: 13px;
        font-weight: 600;
        font-family: "Segoe UI", "Microsoft YaHei UI", sans-serif;
    }
    QPushButton#navBtn:hover {
        background: rgba(255, 255, 255, 0.06);
        color: #dfe3ea;
    }
    QPushButton#navBtn:checked {
        background: rgba(74, 131, 224, 0.16);
        color: #dfe8f7;
    }
)qss";
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
    col->setContentsMargins(10, 12, 10, 12);
    col->setSpacing(4);

    auto* brand = new QLabel(QStringLiteral("OpenNilore"), this);
    brand->setStyleSheet(QStringLiteral(
        "color:#e7ecf5;font-size:14px;font-weight:700;padding:4px 6px 12px 6px;"));
    col->addWidget(brand);

    auto* inject = new QPushButton(QStringLiteral("Inject"), this);
    inject->setObjectName("navBtn");
    inject->setCheckable(true);
    inject->setChecked(true);
    auto* settings = new QPushButton(QStringLiteral("Settings"), this);
    settings->setObjectName("navBtn");
    settings->setCheckable(true);

    for (auto* b : {inject, settings}) b->setCursor(Qt::PointingHandCursor);
    col->addWidget(inject);
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