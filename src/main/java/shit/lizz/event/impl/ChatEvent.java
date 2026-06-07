package shit.lizz.event.impl;

import lombok.Getter;
import lombok.Generated;
import shit.lizz.event.Event;

import java.util.Objects;

public class ChatEvent
extends Event {
    @Getter
    private final String message;
    private static final String TO_STRING_PREFIX = "ChatEvent(message=";

    @Generated
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof ChatEvent otherEvent)) {
            return false;
        }
        if (!otherEvent.canEqual(this)) {
            return false;
        }
        String thisMessage = this.getMessage();
        String otherMessage = otherEvent.getMessage();
        return !(!Objects.equals(thisMessage, otherMessage));
    }

    @Generated
    protected boolean canEqual(Object other) {
        return other instanceof ChatEvent;
    }

    @Generated
    public int hashCode() {
        int prime = 59;
        int result = 1;
        String msg = this.getMessage();
        result = result * 59 + (msg == null ? 43 : msg.hashCode());
        return result;
    }

    @Generated
    public String toString() {
        return TO_STRING_PREFIX + this.getMessage() + ")";
    }

    @Generated
    public ChatEvent(String message) {
        this.message = message;
    }
}