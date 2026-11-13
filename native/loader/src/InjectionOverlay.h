#pragma once

#include <QString>
#include <QWidget>

class QPropertyAnimation;

namespace loader {

// Modal-style frameless overlay shown while injection runs. A spinner (or a
// check / cross once the worker reports back) sits on the left with the
// status text to its right; after a short hold it fades out and emits
// completed().
class InjectionOverlay : public QWidget {
    Q_OBJECT
    Q_PROPERTY(qreal spinnerAngle      READ spinnerAngle      WRITE setSpinnerAngle)
    Q_PROPERTY(qreal panelOpacity      READ panelOpacity      WRITE setPanelOpacity)
    Q_PROPERTY(qreal checkmarkProgress READ checkmarkProgress WRITE setCheckmarkProgress)

public:
    InjectionOverlay(unsigned long pid, const QString& target,
                     const QString& commandLine,
                     QWidget* parent = nullptr);

    void start();

    qreal spinnerAngle()      const { return spinnerAngle_; }
    qreal panelOpacity()      const { return panelOpacity_; }
    qreal checkmarkProgress() const { return checkmarkProgress_; }

    void setSpinnerAngle(qreal v)      { spinnerAngle_      = v; update(); }
    void setPanelOpacity(qreal v)      { panelOpacity_      = v; update(); }
    void setCheckmarkProgress(qreal v) { checkmarkProgress_ = v; update(); }

signals:
    void completed(bool success, unsigned long pid);

protected:
    void paintEvent(QPaintEvent*) override;

private:
    void onInjectResult(QString err);

    QString subtitle() const;
    int     preferredWidth() const;
    void    resizeCentered();

    unsigned long pid_;
    QString       target_;
    QString       commandLine_;
    QString       statusText_ = QStringLiteral("Injecting");

    qreal spinnerAngle_      = 0.0;
    qreal panelOpacity_      = 0.0;
    qreal checkmarkProgress_ = 0.0;

    bool    injectDone_ = false;
    bool    injectOk_   = false;
    QString injectErr_;

    QPropertyAnimation* spinnerAnim_ = nullptr;
};

} // namespace loader
