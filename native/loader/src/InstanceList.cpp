#include "InstanceList.h"

#include "InstanceRow.h"

#include <QLabel>
#include <QPushButton>
#include <QScrollArea>
#include <QSet>
#include <QVBoxLayout>

namespace loader {

InstanceList::InstanceList(QWidget* parent)
        : QWidget(parent) {
    auto* root = new QVBoxLayout(this);
    root->setContentsMargins(0, 0, 0, 0);
    root->setSpacing(0);

    scroll_ = new QScrollArea(this);
    scroll_->setFrameShape(QFrame::NoFrame);
    scroll_->setWidgetResizable(true);
    scroll_->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    scroll_->setVerticalScrollBarPolicy(Qt::ScrollBarAsNeeded);
    scroll_->setStyleSheet(QStringLiteral(
        "QScrollArea { background: transparent; border: none; }"
        "QScrollBar:vertical {"
        "  background: transparent; width: 8px; margin: 4px 1px;"
        "}"
        "QScrollBar::handle:vertical {"
        "  background: #3a3d45; border-radius: 4px; min-height: 28px;"
        "}"
        "QScrollBar::handle:vertical:hover { background: #4a4e58; }"
        "QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {"
        "  height: 0;"
        "}"));

    container_ = new QWidget(scroll_);
    container_->setObjectName("instanceContainer");
    container_->setStyleSheet(QStringLiteral(
        "#instanceContainer { background: transparent; }"));

    containerLayout_ = new QVBoxLayout(container_);
    containerLayout_->setContentsMargins(2, 2, 2, 2);
    containerLayout_->setSpacing(0);

    // Small note under the section title, before any instance rows: pins the
    // supported environment so users don't file bug reports for 1.21+ or
    // Java 21 games.
    note_ = new QLabel(
        QStringLiteral("Minecraft 1.20.1 · Forge 47.4.20 · JDK 17"),
        container_);
    note_->setAlignment(Qt::AlignLeft);
    note_->setStyleSheet(QStringLiteral(
        "color: #5c626e; font-size: 11px; padding: 0 10px 10px 10px;"));
    containerLayout_->addWidget(note_);

    emptyLabel_ = new QLabel(
        QStringLiteral("No Minecraft instances detected.\n"
                       "Start the game and it will show up here."),
        container_);
    emptyLabel_->setAlignment(Qt::AlignCenter);
    emptyLabel_->setStyleSheet(QStringLiteral(
        "color: #6a6f7a; font-size: 12px; font-style: italic;"
        "padding: 40px 12px;"));
    containerLayout_->addWidget(emptyLabel_);

    // Stretch keeps rows aligned to the top of the scroll viewport.
    containerLayout_->addStretch(1);

    scroll_->setWidget(container_);
    root->addWidget(scroll_);

    updateEmptyState();
}

void InstanceList::setInstances(const QVector<Instance>& list) {
    // Compute incoming pid set.
    QSet<unsigned long> incoming;
    incoming.reserve(list.size());
    for (const auto& it : list) incoming.insert(it.pid);

    // Remove rows no longer present.
    QList<unsigned long> toRemove;
    toRemove.reserve(rows_.size());
    for (auto it = rows_.constBegin(); it != rows_.constEnd(); ++it) {
        if (!incoming.contains(it.key())) toRemove.push_back(it.key());
    }
    for (auto pid : toRemove) {
        InstanceRow* r = rows_.take(pid);
        containerLayout_->removeWidget(r);
        r->deleteLater();
        // Windows recycles pids; dropping the flag with the row keeps the badge
        // from showing up on an unrelated process later.
        earlyInjected_.remove(pid);
    }

    // Note is index 0; rows start at index 1 so they sit below the note and
    // the empty label / stretch are at the end.
    for (int i = 0; i < list.size(); ++i) {
        const auto& it = list[i];
        InstanceRow* r = rows_.value(it.pid, nullptr);
        if (!r) {
            r = new InstanceRow(it.pid, it.title, it.commandLine, container_);
            connect(r, &InstanceRow::injectClicked,
                    this, &InstanceList::injectRequested);
            rows_.insert(it.pid, r);
            containerLayout_->insertWidget(i + 1, r);
            r->playEntrance();
            if (earlyInjected_.contains(it.pid)) r->setEarlyInjected(true);
        } else {
            r->updateTitle(it.title);
            int currentIndex = containerLayout_->indexOf(r);
            if (currentIndex != i + 1) {
                containerLayout_->removeWidget(r);
                containerLayout_->insertWidget(i + 1, r);
            }
        }
    }

    setInteractive(interactive_);  // re-apply enabled state on new rows
    updateEmptyState();
}

void InstanceList::setInteractive(bool on) {
    interactive_ = on;
    for (auto* r : rows_) {
        // The Inject button is the only interactive child we care about.
        if (auto* btn = r->findChild<QPushButton*>()) {
            btn->setEnabled(on);
        }
    }
}

void InstanceList::markInjected(unsigned long pid) {
    if (auto* r = rows_.value(pid, nullptr)) {
        r->flashHighlight();
    }
}

void InstanceList::markEarlyInjected(unsigned long pid) {
    earlyInjected_.insert(pid);
    if (auto* r = rows_.value(pid, nullptr)) {
        r->setEarlyInjected(true);
    }
}

Instance InstanceList::instanceForPid(unsigned long pid) const {
    Instance out;
    out.pid = 0;
    if (auto* r = rows_.value(pid, nullptr)) {
        out.pid = r->pid();
        out.title = r->title();
        out.commandLine = r->commandLine();
    }
    return out;
}

void InstanceList::updateEmptyState() {
    const bool empty = rows_.isEmpty();
    emptyLabel_->setVisible(empty);
}

} // namespace loader
