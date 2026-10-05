#pragma once

#include <QHash>
#include <QSet>
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
    QString commandLine;   // full command line (from JavaProcess), used by Early Mode to relaunch
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

    // True if a row already exists for this pid.
    bool containsPid(unsigned long pid) const { return rows_.contains(pid); }

    // Return the instance entry backing the row for pid; zero pid if missing.
    Instance instanceForPid(unsigned long pid) const;

    // Disables inject buttons across all rows (used while an injection is
    // already in progress so the user can't queue a second one).
    void setInteractive(bool on);

    // Visual ping on the row for the given pid: draws a yellow outline ring
    // that fades out.
    void markInjected(unsigned long pid);

    // Tag the row with the green Early Mode badge. Remembered per pid so the
    // badge survives the list rebuild that happens once per second.
    void markEarlyInjected(unsigned long pid);

signals:
    void injectRequested(unsigned long pid, const QString& title, const QString& commandLine);

private:
    void updateEmptyState();

    QScrollArea* scroll_           = nullptr;
    QWidget*     container_        = nullptr;
    QVBoxLayout* containerLayout_  = nullptr;
    QLabel*      note_             = nullptr;
    QLabel*      emptyLabel_       = nullptr;

    QHash<unsigned long, InstanceRow*> rows_;
    // Pids Early Mode has taken over, so the badge is re-applied whenever the
    // row is rebuilt (rows are recreated on refresh).
    QSet<unsigned long> earlyInjected_;
    bool interactive_ = true;
};

} // namespace loader
