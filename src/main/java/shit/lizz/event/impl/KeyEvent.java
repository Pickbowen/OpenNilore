package shit.lizz.event.impl;

import lombok.Getter;
import lombok.Generated;
import shit.lizz.event.Event;

public class KeyEvent
extends Event {
    @Getter
    private final int keyCode;
    @Getter
    private final boolean pressed;

    @Generated
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof KeyEvent otherEvent)) {
            return false;
        }
        if (!otherEvent.canEqual(this)) {
            return false;
        }
        if (this.getKeyCode() != otherEvent.getKeyCode()) {
            return false;
        }
        return this.isPressed() == otherEvent.isPressed();
    }

    @Generated
    protected boolean canEqual(Object other) {
        return other instanceof KeyEvent;
    }

    @Generated
    public int hashCode() {
        int prime = 59;
        int result = 1;
        result = result * 59 + this.getKeyCode();
        result = result * 59 + (this.isPressed() ? 79 : 97);
        return result;
    }

    @Generated
    public String toString() {
        return "KeyEvent(key=" + this.getKeyCode() + ", state=" + this.isPressed() + ")";
    }

    @Generated
    public KeyEvent(int keyCode, boolean pressed) {
        this.keyCode = keyCode;
        this.pressed = pressed;
    }
}