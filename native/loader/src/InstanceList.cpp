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
    }

    // Add or update / reorder.
    for (int i = 0; i < list.size(); ++i) {
        const auto& it = list[i];
        InstanceRow* r = rows_.value(it.pid, nullptr);
        if (!r) {
            r = new InstanceRow(it.pid, it.title, container_);
            connect(r, &InstanceRow::injectClicked,
                    this, &InstanceList::injectRequested);
            rows_.insert(it.pid, r);
            // Insert at index i (after any preceding rows, before the
            // empty label / stretch).
            containerLayout_->insertWidget(i, r);
            r->playEntrance();
        } else {
            r->updateTitle(it.title);
            int currentIndex = containerLayout_->indexOf(r);
            if (currentIndex != i) {
                containerLayout_->removeWidget(r);
                containerLayout_->insertWidget(i, r);
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

void InstanceList::updateEmptyState() {
    const bool empty = rows_.isEmpty();
    emptyLabel_->setVisible(empty);
}

} // namespace loader
