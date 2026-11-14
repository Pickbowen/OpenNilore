#include "SettingsPage.h"

#include "PillSwitch.h"
#include "UiTheme.h"
#include "loader_settings.h"

#include <QFrame>
#include <QHBoxLayout>
#include <QLabel>
#include <QVBoxLayout>

namespace loader {

namespace {
const char* kSettingsQss = R"qss(
    QFrame#toggleRow {
        background: #1D2024;
        border: 1px solid rgba(255, 255, 255, 10);
        border-radius: 8px;
    }
    QLabel#toggleName {
        color: #E6EAF0;
        font-size: 12px;
        font-weight: 600;
    }
    QLabel#toggleDesc {
        color: #5E656F;
        font-size: 11px;
    }
)qss";
} // namespace

SettingsPage::SettingsPage(QWidget* parent)
        : QWidget(parent) {
    setStyleSheet(QString::fromUtf8(kSettingsQss));
    buildUi();
}

bool SettingsPage::hwidSpoofEnabled() const {
    return hwidSwitch_ && hwidSwitch_->isChecked();
}

QWidget* SettingsPage::makeToggleRow(const QString& name, const QString& desc,
                                     PillSwitch* sw, QWidget* parent) {
    auto* row = new QFrame(parent);
    row->setObjectName("toggleRow");
    row->setMinimumHeight(56);
    auto* rowLayout = new QHBoxLayout(row);
    rowLayout->setContentsMargins(ui::kCardPadX, 12, ui::kCardPadX, 12);
    rowLayout->setSpacing(12);

    auto* names = new QVBoxLayout();
    names->setContentsMargins(0, 0, 0, 0);
    names->setSpacing(2);
    auto* nameLabel = new QLabel(name, row);
    nameLabel->setObjectName("toggleName");
    auto* descLabel = new QLabel(desc, row);
    descLabel->setObjectName("toggleDesc");
    descLabel->setWordWrap(true);
    names->addWidget(nameLabel);
    names->addWidget(descLabel);

    rowLayout->addLayout(names, 1);
    rowLayout->addWidget(sw, 0, Qt::AlignVCenter);
    return row;
}

void SettingsPage::buildUi() {
    auto* col = new QVBoxLayout(this);
    col->setContentsMargins(ui::kGutter, 24, ui::kGutterRight, 24);
    col->setSpacing(0);

    auto* title = new QLabel(QStringLiteral("Settings"), this);
    title->setObjectName("title");
    title->setAlignment(Qt::AlignLeft);
    col->addWidget(title);
    col->addSpacing(18);

    hwidSwitch_ = new PillSwitch(this);
    hwidSwitch_->setChecked(loader_settings::is_hwid_spoof_enabled());
    connect(hwidSwitch_, &PillSwitch::toggled, this, [](bool on) {
        loader_settings::save_value(loader_settings::kKeyHwidSpoof(), on);
    });
    col->addWidget(makeToggleRow(
        QStringLiteral("HWID Spoof"),
        QStringLiteral("Spoofs machine fingerprint surfaces (MachineGuid, volume "
                       "serial, MAC, computer name). Default OFF."),
        hwidSwitch_, this));
    col->addSpacing(8);

    nativeBypassSwitch_ = new PillSwitch(this);
    nativeBypassSwitch_->setChecked(loader_settings::is_native_bypass_enabled());
    connect(nativeBypassSwitch_, &PillSwitch::toggled, this, [](bool on) {
        loader_settings::save_value(loader_settings::kKeyNativeBypass(), on);
    });
    col->addWidget(makeToggleRow(
        QStringLiteral("Native Bypass"),
        QStringLiteral("Auto: as soon as a Minecraft instance shows up, restart "
                       "it suspended from its own command line, map the DLL in "
                       "before it resumes, then let it go. The game window reopens "
                       "and you rejoin the server. Clicking Inject does the same "
                       "on demand. Default OFF."),
        nativeBypassSwitch_, this));

    col->addStretch(1);
}

} // namespace loader
