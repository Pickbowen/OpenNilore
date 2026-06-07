package shit.lizz.event.impl;

import lombok.Getter;
import lombok.Setter;
import lombok.Generated;
import shit.lizz.event.Event;

public class MotionEvent
extends Event {
    @Getter @Setter
    public boolean pre;
    @Getter @Setter
    public double x;
    @Getter @Setter
    public double y;
    @Getter @Setter
    public double z;
    @Getter @Setter
    public float yaw;
    @Getter @Setter
    public float pitch;
    @Getter @Setter
    public boolean onGround;

    public boolean isPost() {
        return !this.isPre();
    }

    @Generated
    public MotionEvent(boolean pre, double x, double y, double z, float yaw, float pitch, boolean onGround) {
        this.pre = pre;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.onGround = onGround;
    }
}