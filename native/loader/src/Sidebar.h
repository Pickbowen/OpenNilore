#pragma once

#include <QWidget>

class QButtonGroup;
class QVBoxLayout;

namespace loader {

// Flat left navigation sidebar (DeepSeek-style): a fixed-width column with
// two nav buttons, "Inject" and "Settings". Purely a button column - it does
// NOT own any page widgets. Selection is forwarded via signals; the shell
// (SidebarShell) decides which central page is visible.
//
// Stability note: InstanceList must stay a direct child of the MainWindow
// central layout (proven stable). It is never put inside a QStackedWidget or
// any intermediate container - that triggered a vtable UAF (exe+0x246655).
// Settings is a sibling overlay page that is shown/hidden via setVisible.

class Sidebar : public QWidget {
    Q_OBJECT
public:
    explicit Sidebar(QWidget* parent = nullptr);

signals:
    void injectClicked();
    void settingsClicked();

private:
    void buildUi();

    QButtonGroup* group_ = nullptr;
};

} // namespace loader