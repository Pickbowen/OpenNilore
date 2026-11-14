#pragma once

#include <QFrame>
#include <QString>

class QLabel;

namespace loader {

// A selectable card: title on top, description underneath, the whole thing
// reacts to clicks. Used by the theme page.
class OptionCard : public QFrame {
    Q_OBJECT
    Q_PROPERTY(bool selected READ isSelected WRITE setSelected)

public:
    OptionCard(const QString& title, const QString& description,
               QWidget* parent = nullptr);

    bool isSelected() const { return selected_; }
    void setSelected(bool on);

signals:
    void clicked();

protected:
    void mousePressEvent(QMouseEvent* event) override;

private:
    QLabel* titleLabel_ = nullptr;
    QLabel* descLabel_  = nullptr;
    bool    selected_   = false;
};

// Theme page: lets the user pick how the frame background behaves.
class ThemePage : public QWidget {
    Q_OBJECT
public:
    explicit ThemePage(QWidget* parent = nullptr);

    // Reflects the current mode on the two cards.
    void setTranslucent(bool on);

signals:
    void translucentChanged(bool on);

private:
    OptionCard* translucentCard_ = nullptr;
    OptionCard* normalCard_     = nullptr;
};

} // namespace loader
