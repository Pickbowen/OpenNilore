#pragma once

#include <QWidget>

class QLabel;

namespace loader {

class PillSwitch;

// Settings page (flat, shown when the left sidebar's "Settings" entry is
// active). Contains the HWID spoof pill switch plus the Syscall Thread
// toggle (both default OFF), persisted via loader_settings.

class SettingsPage : public QWidget {
    Q_OBJECT
public:
    explicit SettingsPage(QWidget* parent = nullptr);

    bool hwidSpoofEnabled() const;
    bool syscallThreadEnabled() const;

private:
    void buildUi();
    QWidget* makeToggleRow(const QString& name, const QString& desc,
                           PillSwitch* sw, QWidget* parent);

    PillSwitch* hwidSwitch_      = nullptr;
    PillSwitch* syscallSwitch_   = nullptr;
};

} // namespace loader