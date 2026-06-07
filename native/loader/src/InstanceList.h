#pragma once

#include <QHash>
#include <QString>
#include <QVector>
#include <QWidget>

class QLabel;
class QScrollArea;
class QVBoxLayout;

namespace loader {

struct Instance {
    unsigned long pid;
    QString title;
};

class InstanceRow;

// Scrollable column of InstanceRow widgets. Replaces QTableWidget: rows are
// not selectable, there is no double-click affordance; each row owns its
// own Inject button and forwards clicks through injectRequested().
class InstanceList : public QWidget {
    Q_OBJECT
public:
    explicit InstanceList(QWidget* parent = nullptr);

    // Incremental update: rows for pids no longer present are removed
    // (animated out of the layout), rows for new pids are inserted at the
    // right index and play an entrance animation, existing rows just get
    // their title refreshed.
    void setInstances(const QVector<Instance>& list);

    int count() const { return rows_.size(); }

    // Disables inject buttons across all rows (used while an injection is
    // already in progress so the user can't queue a second one).
    void setInteractive(bool on);

signals:
    void injectRequested(unsigned long pid, const QString& title);

private:
    void updateEmptyState();

    QScrollArea* scroll_           = nullptr;
    QWidget*     container_        = nullptr;
    QVBoxLayout* containerLayout_  = nullptr;
    QLabel*      emptyLabel_       = nullptr;

    QHash<unsigned long, InstanceRow*> rows_;
    bool interactive_ = true;
};

} // namespace loader
