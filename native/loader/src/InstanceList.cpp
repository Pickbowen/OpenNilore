#include "InstanceList.h"

#include "InstanceRow.h"
#include "UiTheme.h"

#include <QLabel>
#include <QPushButton>
#include <QScrollArea>
#include <QSet>
#include <QVBoxLayout>

namespace loader {

namespace {
const char* kScrollQss = R"qss(
    QScrollArea { background: transparent; border: none; }
)qss";
} // namespace

InstanceList::InstanceList(QWidget* parent)
        : QWidget(parent) {
    auto* root = new QVBoxLayout(this);
    root->setContentsMargins(0, 0, 0, 0);
    root->setSpacing(0);

    auto* header = new QWidget(this);
    auto* headerLayout = new QVBoxLayout(header);
    headerLayout->setContentsMargins(ui::kGutter, 24, ui::kGutterRight, 14);
    headerLayout->setSpacing(0);

    auto* title = new QLabel(QStringLiteral("Instances"), header);
    title->setStyleSheet(QStringLiteral(
        "color: #FFFFFF; font-size: 17px; font-weight: 600;"));
    headerLayout->addWidget(title);
    headerLayout->addSpacing(4);

    note_ = new QLabel(
        QStringLiteral("Minecraft 1.20.1 · Forge 47.4.20 · JDK 17"),
        header);
    note_->setAlignment(Qt::AlignLeft);
    note_->setStyleSheet(QStringLiteral(
        "color: #5E656F; font-size: 11px;"));
    headerLayout->addWidget(note_);
    root->addWidget(header);

    scroll_ = new QScrollArea(this);
    scroll_->setFrameShape(QFrame::NoFrame);
    scroll_->setWidgetResizable(true);
    scroll_->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    scroll_->setVerticalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    scroll_->setStyleSheet(QString::fromUtf8(kScrollQss));

    container_ = new QWidget(scroll_);
    container_->setObjectName("instanceContainer");
    container_->setStyleSheet(QStringLiteral(
        "#instanceContainer { background: transparent; }"));

    containerLayout_ = new QVBoxLayout(container_);
    containerLayout_->setContentsMargins(ui::kGutter, 0, ui::kGutterRight, 8);
    containerLayout_->setSpacing(ui::kRowSpacing);

    emptyLabel_ = new QLabel(
        QStringLiteral("No Minecraft instances detected.\n"
                       "Start the game and it will show up here."),
        container_);
    emptyLabel_->setAlignment(Qt::AlignCenter);
    emptyLabel_->setStyleSheet(QStringLiteral(
        "color: #5E656F; font-size: 11px; padding: 40px 12px;"));
    containerLayout_->addWidget(emptyLabel_);

    containerLayout_->addStretch(1);

    scroll_->setWidget(container_);
    root->addWidget(scroll_, 1);

    updateEmptyState();
}

void InstanceList::setInstances(const QVector<Instance>& list) {
    QSet<unsigned long> incoming;
    incoming.reserve(list.size());
    for (const auto& it : list) incoming.insert(it.pid);

    QList<unsigned long> toRemove;
    toRemove.reserve(rows_.size());
    for (auto it = rows_.constBegin(); it != rows_.constEnd(); ++it) {
        if (!incoming.contains(it.key())) toRemove.push_back(it.key());
    }
    for (auto pid : toRemove) {
        InstanceRow* r = rows_.take(pid);
        containerLayout_->removeWidget(r);
        r->deleteLater();
        nativeBypassed_.remove(pid);
    }

    for (int i = 0; i < list.size(); ++i) {
        const auto& it = list[i];
        InstanceRow* r = rows_.value(it.pid, nullptr);
        if (!r) {
            r = new InstanceRow(it.pid, it.title, it.commandLine, container_);
            connect(r, &InstanceRow::injectClicked,
                    this, &InstanceList::injectRequested);
            rows_.insert(it.pid, r);
            containerLayout_->insertWidget(i, r);
            r->playEntrance();
            if (nativeBypassed_.contains(it.pid)) r->setNativeBypassed(true);
        } else {
            r->updateTitle(it.title);
            int currentIndex = containerLayout_->indexOf(r);
            if (currentIndex != i) {
                containerLayout_->removeWidget(r);
                containerLayout_->insertWidget(i, r);
            }
        }
    }

    setInteractive(interactive_);
    updateEmptyState();
}

void InstanceList::setInteractive(bool on) {
    interactive_ = on;
    for (auto* r : rows_) {
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

void InstanceList::markNativeBypassed(unsigned long pid) {
    nativeBypassed_.insert(pid);
    if (auto* r = rows_.value(pid, nullptr)) {
        r->setNativeBypassed(true);
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
