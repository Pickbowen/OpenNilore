#pragma once

#include <QWidget>

namespace loader {

// Frameless, translucent, top-most splash that animates the OpenZen
// wordmark and a scanning line, then fades itself out and emits
// finished(). Used as the cold-start window before MainWindow appears.
class SplashScreen : public QWidget {
    Q_OBJECT
    Q_PROPERTY(qreal logoOpacity  READ logoOpacity  WRITE setLogoOpacity)
    Q_PROPERTY(qreal logoScale    READ logoScale    WRITE setLogoScale)
    Q_PROPERTY(qreal scanProgress READ scanProgress WRITE setScanProgress)
    Q_PROPERTY(qreal glowPulse    READ glowPulse    WRITE setGlowPulse)

public:
    explicit SplashScreen(QWidget* parent = nullptr);

    // Kicks off the entire animation sequence. emit finished() at the end.
    void start();

    qreal logoOpacity()  const { return logoOpacity_; }
    qreal logoScale()    const { return logoScale_; }
    qreal scanProgress() const { return scanProgress_; }
    qreal glowPulse()    const { return glowPulse_; }

    void setLogoOpacity(qreal v)  { logoOpacity_  = v; update(); }
    void setLogoScale(qreal v)    { logoScale_    = v; update(); }
    void setScanProgress(qreal v) { scanProgress_ = v; update(); }
    void setGlowPulse(qreal v)    { glowPulse_    = v; update(); }

signals:
    void finished();

protected:
    void paintEvent(QPaintEvent*) override;

private:
    qreal logoOpacity_  = 0.0;
    qreal logoScale_    = 0.6;
    qreal scanProgress_ = 0.0;
    qreal glowPulse_    = 0.0;
};

} // namespace loader
