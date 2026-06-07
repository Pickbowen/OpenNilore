package shit.lizz.modules.impl.player.helper;

import lombok.Getter;
import shit.lizz.ClientBase;
import shit.lizz.event.impl.MotionEvent;
import shit.lizz.event.impl.PreMotionEvent;
import shit.lizz.event.impl.RenderEvent;
import shit.lizz.event.impl.TickEvent;
import shit.lizz.utils.rotation.Rotation;

public abstract class HelperBase
extends ClientBase {
    @Getter
    private final String name;

    public HelperBase(String string) {
        this.name = string;
    }

    public void onEnable() {
    }

    public void onDisable() {
    }

    public void onTick(TickEvent tickEvent) {
    }

    public void onMotion(MotionEvent motionEvent) {
    }

    public void onRender(RenderEvent renderEvent) {
    }

    public void onPreMotion(PreMotionEvent preMotionEvent) {
    }

    public boolean isActive() {
        return false;
    }

    public Rotation getTargetRotation() {
        return null;
    }

    }