package client.nilore.modules.impl.combat.antikb;

import java.util.HashMap;
import java.util.Optional;
import client.nilore.ClientBase;
import client.nilore.event.impl.DisconnectEvent;
import client.nilore.event.impl.EntityRemoveEvent;
import client.nilore.event.impl.GameTickEvent;
import client.nilore.event.impl.MotionEvent;
import client.nilore.event.impl.PreMotionEvent;
import client.nilore.event.impl.ReceivePacketEvent;
import client.nilore.event.impl.Render2DEvent;
import client.nilore.event.impl.RenderEvent;
import client.nilore.event.impl.RotationEvent;
import client.nilore.event.impl.SprintEvent;
import client.nilore.event.impl.StrafeEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.modules.impl.combat.antikb.JumpResetMode;
import client.nilore.modules.impl.combat.antikb.NoXZMode;

public abstract class AntiKBMode
extends ClientBase {
    protected final String name;
    private static final HashMap<Class<? extends AntiKBMode>, AntiKBMode> modes = new HashMap<>();

    public AntiKBMode(String string) {
        this.name = string;
    }

    public static void initModes() {
        modes.put(JumpResetMode.class, new JumpResetMode());
        modes.put(NoXZMode.class, new NoXZMode());
    }

    public abstract void onEnable();

    public abstract void onDisable();

    public abstract String getName();

    public static Optional<AntiKBMode> findMode(String string) {
        return modes.values().stream().filter(antiKBMode -> antiKBMode.name.equals(string)).findFirst();
    }

    public abstract void onRotation(RotationEvent var1);

    public abstract void onReceivePacket(ReceivePacketEvent var1);

    public abstract void onDisconnect(DisconnectEvent var1);

    public abstract void onPreMotion(PreMotionEvent var1);

    public abstract void onGameTick(GameTickEvent var1);

    public abstract void onSprint(SprintEvent var1);

    public abstract void onTick(TickEvent var1);

    public abstract void onStrafe(StrafeEvent var1);

    public abstract void onMotion(MotionEvent var1);

    public abstract void onAttack(EntityRemoveEvent var1);

    public abstract void onEntityHurt(client.nilore.event.impl.EntityHurtEvent var1);

    public void onRender(RenderEvent renderEvent) {
    }

    public void onRender2D(Render2DEvent render2DEvent) {
    }

    public boolean isActive() {
        return false;
    }
}