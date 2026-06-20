package shit.nilore.event.impl;

import lombok.Setter;
import lombok.Generated;
import shit.nilore.event.EventMarker;

public class SlowdownEvent
implements EventMarker {
    @Setter
    private boolean slowDown;

    public boolean isSlowDown() {
        return this.slowDown;
    }

    @Generated
    public SlowdownEvent(boolean slowDown) {
        this.slowDown = slowDown;
    }
}