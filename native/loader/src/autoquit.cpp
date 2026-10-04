#include "autoquit.h"

#include <QCoreApplication>
#include <QTimer>

namespace loader::dbg {

void installAutoQuit(QObject* parent) {
    auto* t = new QTimer(parent);
    QObject::connect(t, &QTimer::timeout,
                     qApp, &QCoreApplication::quit);
    t->setSingleShot(true);
    t->start(1200);
}

} // namespace loader::dbg