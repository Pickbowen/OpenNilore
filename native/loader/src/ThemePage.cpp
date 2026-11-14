#include "ThemePage.h"

#include "UiTheme.h"

#include <QHBoxLayout>
#include <QLabel>
#include <QMouseEvent>
#include <QStyle>
#include <QVBoxLayout>

namespace loader {

namespace {
const char* kThemeQss = R"qss(
    QFrame#optionCard {
        background: #191F29;
        border: 1px solid rgba(255, 255, 255, 14);
        border-radius: 8px;
    }
    QFrame#optionCard:hover {
        background: #212833;
        border: 1px solid rgba(255, 255, 255, 26);
    }
    QFrame#optionCard[selected="true"] {
        background: #212833;
        border: 1px solid #5C9DFF;
    }
    QLabel#optionTitle {
        color: #E6EAF0;
        font-size: 12px;
        font-weight: 600;
    }
    QLabel#optionDesc {
        color: #7C838E;
        font-size: 11px;
    }
)qss";
} // namespace

OptionCard::OptionCard(const QString& title, const QString& description,
                       QWidget* parent)
        : QFrame(parent) {
    setObjectName("optionCard");
    setCursor(Qt::PointingHandCursor);
    setAttribute(Qt::WA_Hover, true);

    auto* col = new QVBoxLayout(this);
    col->setContentsMargins(14, 12, 14, 12);
    col->setSpacing(3);

    titleLabel_ = new QLabel(title, this);
    titleLabel_->setObjectName("optionTitle");
    col->addWidget(titleLabel_);

    descLabel_ = new QLabel(description, this);
    descLabel_->setObjectName("optionDesc");
    descLabel_->setWordWrap(true);
    col->addWidget(descLabel_);
}

void OptionCard::setSelected(bool on) {
    if (selected_ == on) return;
    selected_ = on;
    style()->unpolish(this);
    style()->polish(this);
    update();
}

void OptionCard::mousePressEvent(QMouseEvent* event) {
    if (event->button() == Qt::LeftButton) {
        emit clicked();
        event->accept();
        return;
    }
    QFrame::mousePressEvent(event);
}

ThemePage::ThemePage(QWidget* parent)
        : QWidget(parent) {
    setStyleSheet(QString::fromUtf8(kThemeQss));

    auto* col = new QVBoxLayout(this);
    col->setContentsMargins(ui::kGutter, 24, ui::kGutterRight, 24);
    col->setSpacing(0);

    auto* title = new QLabel(QStringLiteral("Theme"), this);
    title->setObjectName("title");
    title->setAlignment(Qt::AlignLeft);
    col->addWidget(title);
    col->addSpacing(18);

    translucentCard_ = new OptionCard(QStringLiteral("Translucent"),
                                      QStringLiteral("The desktop shows through the frame"),
                                      this);
    normalCard_ = new OptionCard(QStringLiteral("Normal"),
                                  QStringLiteral("Solid blue-black background"),
                                  this);

    col->addWidget(translucentCard_);
    col->addSpacing(8);
    col->addWidget(normalCard_);
    col->addStretch(1);

    connect(translucentCard_, &OptionCard::clicked, this, [this] {
        emit translucentChanged(true);
    });
    connect(normalCard_, &OptionCard::clicked, this, [this] {
        emit translucentChanged(false);
    });
}

void ThemePage::setTranslucent(bool on) {
    translucentCard_->setSelected(on);
    normalCard_->setSelected(!on);
}

} // namespace loader
