package shit.nilore.modules.impl.player.helper;

import lombok.Getter;
import shit.nilore.ClientBase;
import shit.nilore.event.impl.MotionEvent;
import shit.nilore.event.impl.PreMotionEvent;
import shit.nilore.event.impl.RenderEvent;
import shit.nilore.event.impl.TickEvent;
import shit.nilore.utils.rotation.Rotation;

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