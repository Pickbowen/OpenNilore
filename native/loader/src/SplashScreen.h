#pragma once

#include <QWidget>

namespace loader {

// Small frameless spinner card shown at cold start, then fades out and
// emits finished(). Used as the cold-start window before MainWindow appears.
class SplashScreen : public QWidget {
    Q_OBJECT
    Q_PROPERTY(qreal spinnerAngle READ spinnerAngle WRITE setSpinnerAngle)

public:
    explicit SplashScreen(QWidget* parent = nullptr);

    // Kicks off the animation sequence. emit finished() at the end.
    void start();

    qreal spinnerAngle() const { return spinnerAngle_; }
    void setSpinnerAngle(qreal v) { spinnerAngle_ = v; update(); }

signals:
    void finished();

protected:
    void paintEvent(QPaintEvent*) override;

private:
    qreal spinnerAngle_ = 0.0;
};

} // namespace loader
