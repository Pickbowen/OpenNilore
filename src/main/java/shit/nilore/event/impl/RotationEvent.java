package shit.nilore.event.impl;

import lombok.Getter;
import lombok.Setter;
import lombok.Generated;
import shit.nilore.event.EventMarker;

public class RotationEvent
implements EventMarker {
    @Getter @Setter
    private float yaw;
    @Getter @Setter
    private float pitch;

    @Generated
    public RotationEvent(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }
}