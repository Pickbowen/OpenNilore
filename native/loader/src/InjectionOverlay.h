#pragma once

#include <QString>
#include <QWidget>

class QPropertyAnimation;

namespace loader {

// Modal-style frameless overlay shown while injection runs. Drives a
// spinner + progress bar, then a checkmark (or X) when the inject worker
// thread reports back. After a short hold it fades out and emits
// completed(); the application's main wires that to QCoreApplication::quit.
class InjectionOverlay : public QWidget {
    Q_OBJECT
    Q_PROPERTY(qreal spinnerAngle      READ spinnerAngle      WRITE setSpinnerAngle)
    Q_PROPERTY(qreal progress          READ progress          WRITE setProgress)
    Q_PROPERTY(qreal panelOpacity      READ panelOpacity      WRITE setPanelOpacity)
    Q_PROPERTY(qreal checkmarkProgress READ checkmarkProgress WRITE setCheckmarkProgress)

public:
    InjectionOverlay(unsigned long pid, const QString& target,
                     QWidget* parent = nullptr);

    void start();

    qreal spinnerAngle()      const { return spinnerAngle_; }
    qreal progress()          const { return progress_; }
    qreal panelOpacity()      const { return panelOpacity_; }
    qreal checkmarkProgress() const { return checkmarkProgress_; }

    void setSpinnerAngle(qreal v)      { spinnerAngle_      = v; update(); }
    void setProgress(qreal v)          { progress_          = v; update(); }
    void setPanelOpacity(qreal v)      { panelOpacity_      = v; update(); }
    void setCheckmarkProgress(qreal v) { checkmarkProgress_ = v; update(); }

signals:
    void completed(bool success);

protected:
    void paintEvent(QPaintEvent*) override;

private:
    void onInjectResult(QString err);

    unsigned long pid_;
    QString       target_;
    QString       statusText_       = QStringLiteral("Injecting OpenZen…");

    qreal spinnerAngle_      = 0.0;
    qreal progress_          = 0.0;
    qreal panelOpacity_      = 0.0;
    qreal checkmarkProgress_ = 0.0;

    bool    injectDone_ = false;
    bool    injectOk_   = false;
    QString injectErr_;

    QPropertyAnimation* spinnerAnim_  = nullptr;
    QPropertyAnimation* progressAnim_ = nullptr;
};

} // namespace loader
