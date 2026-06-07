package shit.lizz.event.impl;

import lombok.Getter;
import lombok.Setter;
import lombok.Generated;
import shit.lizz.event.EventMarker;

public class StrafeEvent
implements EventMarker {
    @Getter @Setter
    private float forward;
    @Getter @Setter
    private float strafe;
    @Getter @Setter
    private boolean sprinting;

    @Generated
    public StrafeEvent(float forward, float strafe, boolean sprinting) {
        this.forward = forward;
        this.strafe = strafe;
        this.sprinting = sprinting;
    }
}