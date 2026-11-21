package client.nilore.modules.impl.combat;

import java.util.Arrays;
import java.util.Optional;
import client.nilore.event.impl.DisconnectEvent;
import client.nilore.event.impl.EntityHurtEvent;
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
import client.nilore.event.impl.UseItemRayTraceEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.combat.antikb.AntiKBMode;
import client.nilore.modules.impl.combat.antikb.NoXZMode;
import client.nilore.modules.impl.movement.FireballBlink;
import client.nilore.modules.impl.movement.HighJump;
import client.nilore.modules.impl.movement.Scaffold;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.animation.Timer;
import client.nilore.utils.rotation.Rotation;
import client.nilore.event.EventTarget;

public class AntiKB
        extends Module {
    public static AntiKB INSTANCE;
    public static Rotation rotation;
    public static ModeSetting mode;
    public final BooleanSetting rotate = new BooleanSetting("Rotate", true, () -> mode.is("Jump Reset"));
    public final BooleanSetting followDirection = new BooleanSetting("Follow Direction", true, () -> mode.is("Jump Reset"));
    public final BooleanSetting predict = new BooleanSetting("Predict", false, () -> mode.is("Jump Reset"));
    public final NumberSetting rotateTicks = new NumberSetting("Rotate Ticks", 12, 3, 20, 1, () -> mode.is("Jump Reset") && (this.rotate.getValue() != false || this.followDirection.getValue() != false));
    public final BooleanSetting requireAura = new BooleanSetting("Require Aura", true, () -> mode.is("NoXZ"));
    public final BooleanSetting delayTillGround = new BooleanSetting("Delay Till Ground", false, () -> mode.is("NoXZ"));
    public final NumberSetting targetMotion = new NumberSetting("Target Motion", 0.15, 0.05, 0.5, 0.05, () -> mode.is("NoXZ"));
    public final NumberSetting releaseDistance = new NumberSetting("Release Distance", 1.6, 0.1, 3.0, 0.1, () -> mode.is("NoXZ"));
    public final BooleanSetting renderBar = new BooleanSetting("Render Bar", true, () -> mode.is("NoXZ"));
    public final BooleanSetting debugLog = new BooleanSetting("Debug Log", false);
    private final Timer grimSyncTimer = new Timer();
    private AntiKBMode activeMode;
    public AntiKB() {
        super("AntiKB", Category.COMBAT);
        INSTANCE = this;
        AntiKBMode.initModes();
    }

    @Override
    public void onEnable() {
        this.grimSyncTimer.reset();
        Optional<AntiKBMode> optional;
        rotation = null;
        if (!Arrays.stream((Object[])mode.getModes()).toList().contains(mode.getValue())) {
            mode.withDefault("NoXZ");
        }
        if ((optional = AntiKBMode.findMode(mode.getValue())).isEmpty()) {
            return;
        }
        this.activeMode = optional.get();
        this.activeMode.onEnable();
    }

    @Override
    public void onDisable() {
        rotation = null;
        if (this.activeMode != null) {
            this.activeMode.onDisable();
            this.activeMode = null;
        }
    }

    private Optional<AntiKBMode> currentMode() {
        Optional<AntiKBMode> selected = AntiKBMode.findMode(mode.getValue());
        AntiKBMode next = selected.orElse(null);
        if (this.activeMode != next) {
            if (this.activeMode != null) {
                this.activeMode.onDisable();
            }
            rotation = null;
            this.activeMode = next;
            if (next != null) {
                next.onEnable();
            }
        }
        return selected;
    }

    private boolean shouldSuspend(Optional<AntiKBMode> selected) {
        boolean suspended = FireballBlink.INSTANCE.isEnabled() || HighJump.INSTANCE.isEnabled()
                || Scaffold.INSTANCE.isEnabled();
        if (suspended && this.activeMode instanceof NoXZMode && this.activeMode.isActive()) {
            this.activeMode.onDisable();
        }
        return suspended || selected.isEmpty();
    }

    @EventTarget
    public void onGameTick(GameTickEvent gameTickEvent) {
        Optional<AntiKBMode> optional = this.currentMode();
        if (this.shouldSuspend(optional)) {
            return;
        }
        optional.get().onGameTick(gameTickEvent);
    }

    @EventTarget(value=0)
    public void onPreMotion(PreMotionEvent preMotionEvent) {
        Optional<AntiKBMode> optional = this.currentMode();
        if (this.shouldSuspend(optional)) {
            return;
        }
        optional.get().onPreMotion(preMotionEvent);
    }

    /**
     * LOW, not NORMAL: the mode state machine (NoXZ's window / counter) gates on "is the aura aimed
     * at the target", and both that gate and {@code KillAura.target} are read inside it. Everything
     * the aura publishes for the current tick - {@code KillAura.target}, {@code KillAura.rotation} -
     * is written by its own TickEvent listener, and {@code RotationHandler.onTickHigh} (LOWEST) only
     * hands the rotation over at the very end of the same dispatch. At NORMAL the order was decided
     * by whichever module the player happened to enable first, so the whole gate ran against the
     * previous tick's aim and only passed once the player's own view sat on the target. LOW puts it
     * after every NORMAL listener deterministically.
     */
    @EventTarget(value = 3)
    public void onTick(TickEvent tickEvent) {
        Optional<AntiKBMode> optional = this.currentMode();
        if (this.shouldSuspend(optional)) {
            return;
        }
        optional.get().onTick(tickEvent);
    }

    @EventTarget
    public void onSprint(SprintEvent sprintEvent) {
        Optional<AntiKBMode> optional = this.currentMode();
        if (this.shouldSuspend(optional)) {
            return;
        }
        optional.get().onSprint(sprintEvent);
    }

    @EventTarget
    public void onRotation(RotationEvent rotationEvent) {
        Optional<AntiKBMode> optional = this.currentMode();
        if (this.shouldSuspend(optional)) {
            return;
        }
        optional.get().onRotation(rotationEvent);
    }

    @EventTarget
    public void onMotion(MotionEvent motionEvent) {
        Optional<AntiKBMode> optional = this.currentMode();
        if (this.shouldSuspend(optional)) {
            return;
        }
        optional.get().onMotion(motionEvent);
    }

    @EventTarget(value=1)
    public void onReceivePacket(ReceivePacketEvent receivePacketEvent) {
        Optional<AntiKBMode> optional = this.currentMode();
        if (this.shouldSuspend(optional)) {
            return;
        }
        optional.get().onReceivePacket(receivePacketEvent);
    }

    @EventTarget
    public void onItemUseRayTrace(UseItemRayTraceEvent event) {
        Optional<AntiKBMode> optional = this.currentMode();
        if (this.shouldSuspend(optional)) {
            return;
        }
        optional.get().onItemUseRayTrace(event);
    }

    @EventTarget
    public void onDisconnect(DisconnectEvent disconnectEvent) {
        if (this.activeMode != null) {
            this.activeMode.onDisconnect(disconnectEvent);
        }
    }

    @EventTarget(value=3)
    public void onStrafe(StrafeEvent strafeEvent) {
        Optional<AntiKBMode> optional = this.currentMode();
        if (this.shouldSuspend(optional)) {
            return;
        }
        optional.get().onStrafe(strafeEvent);
    }

    @EventTarget
    public void onAttack(EntityRemoveEvent event) {
        Optional<AntiKBMode> optional = this.currentMode();
        if (optional.isEmpty()) {
            return;
        }
        optional.get().onAttack(event);
    }

    @EventTarget
    public void onEntityHurt(EntityHurtEvent event) {
        Optional<AntiKBMode> optional = this.currentMode();
        if (optional.isEmpty()) {
            return;
        }
        optional.get().onEntityHurt(event);
    }

    @EventTarget
    public void onRender(RenderEvent renderEvent) {
        Optional<AntiKBMode> optional = this.currentMode();
        if (this.shouldSuspend(optional)) {
            return;
        }
        optional.get().onRender(renderEvent);
    }

    @EventTarget
    public void onRender2D(Render2DEvent render2DEvent) {
        Optional<AntiKBMode> optional = this.currentMode();
        if (this.shouldSuspend(optional)) {
            return;
        }
        optional.get().onRender2D(render2DEvent);
    }

    static {
        mode = new ModeSetting("Mode", "Jump Reset", "NoXZ").withDefault("NoXZ");
    }
}