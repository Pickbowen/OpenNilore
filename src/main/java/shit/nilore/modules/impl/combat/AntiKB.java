package shit.nilore.modules.impl.combat;

import java.util.Arrays;
import java.util.Optional;
import shit.nilore.event.impl.DisconnectEvent;
import shit.nilore.event.impl.GameTickEvent;
import shit.nilore.event.impl.MotionEvent;
import shit.nilore.event.impl.PreMotionEvent;
import shit.nilore.event.impl.ReceivePacketEvent;
import shit.nilore.event.impl.Render2DEvent;
import shit.nilore.event.impl.RenderEvent;
import shit.nilore.event.impl.RotationEvent;
import shit.nilore.event.impl.SprintEvent;
import shit.nilore.event.impl.StrafeEvent;
import shit.nilore.event.impl.TickEvent;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.modules.impl.combat.antikb.AntiKBMode;
import shit.nilore.modules.impl.movement.FireballBlink;
import shit.nilore.modules.impl.movement.HighJump;
import shit.nilore.settings.impl.BooleanSetting;
import shit.nilore.settings.impl.ModeSetting;
import shit.nilore.settings.impl.NumberSetting;
import shit.nilore.utils.rotation.Rotation;
import shit.nilore.event.EventTarget;

public class AntiKB
extends Module {
    public static AntiKB INSTANCE;
    public static Rotation rotation;
    public static ModeSetting mode;
    public final BooleanSetting autoJump = new BooleanSetting("Auto Jump", false, () -> mode.is("Grim Full") || mode.is("Grim Fast"));
    public final BooleanSetting rotate = new BooleanSetting("Rotate", false, () -> mode.is("Jump Reset") || mode.is("Mix"));
    public final BooleanSetting tryAttack = new BooleanSetting("Try Attack", false, () -> mode.is("Mix"));
    public final BooleanSetting movementOverride = new BooleanSetting("Movement Override", false, () -> mode.is("Mix"));
    public final BooleanSetting followDirection = new BooleanSetting("Follow Direction", false, () -> mode.is("Jump Reset"));
    public final NumberSetting rotateTicks = new NumberSetting("Rotate Ticks", 12, 3, 20, 1, () -> mode.is("Jump Reset") && (this.rotate.getValue() != false || this.followDirection.getValue() != false));
    public final NumberSetting attackAmount = new NumberSetting("Attack amount", 5.0, 1.0, 20.0, 1, () -> mode.is("NoXZ"));
    public final BooleanSetting instantAttack = new BooleanSetting("Instant Attack", false, () -> mode.is("NoXZ"));
    public final BooleanSetting sprintStateCheck = new BooleanSetting("Sprint state check", true, () -> mode.is("NoXZ"));

    public AntiKB() {
        super("AntiKB", Category.COMBAT);
        INSTANCE = this;
        AntiKBMode.initModes();
    }

    @Override
    public void onEnable() {
        Optional<AntiKBMode> optional;
        rotation = null;
        if (!Arrays.stream((Object[])mode.getModes()).toList().contains(mode.getValue())) {
            mode.withDefault("NoXZ");
        }
        if ((optional = AntiKBMode.findMode(mode.getValue())).isEmpty()) {
            return;
        }
        optional.get().onEnable();
    }

    @Override
    public void onDisable() {
        rotation = null;
        Optional<AntiKBMode> optional = AntiKBMode.findMode(mode.getValue());
        if (optional.isEmpty()) {
            return;
        }
        optional.get().onDisable();
    }

    @EventTarget
    public void onGameTick(GameTickEvent gameTickEvent) {
        Optional<AntiKBMode> optional = AntiKBMode.findMode(mode.getValue());
        if (FireballBlink.INSTANCE.isEnabled() || HighJump.INSTANCE.isEnabled() || optional.isEmpty()) {
            return;
        }
        optional.get().onGameTick(gameTickEvent);
    }

    @EventTarget
    public void onPreMotion(PreMotionEvent preMotionEvent) {
        Optional<AntiKBMode> optional = AntiKBMode.findMode(mode.getValue());
        if (FireballBlink.INSTANCE.isEnabled() || HighJump.INSTANCE.isEnabled() || optional.isEmpty()) {
            return;
        }
        optional.get().onPreMotion(preMotionEvent);
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        Optional<AntiKBMode> optional = AntiKBMode.findMode(mode.getValue());
        if (FireballBlink.INSTANCE.isEnabled() || HighJump.INSTANCE.isEnabled() || optional.isEmpty()) {
            return;
        }
        optional.get().onTick(tickEvent);
    }

    @EventTarget
    public void onSprint(SprintEvent sprintEvent) {
        Optional<AntiKBMode> optional = AntiKBMode.findMode(mode.getValue());
        if (FireballBlink.INSTANCE.isEnabled() || HighJump.INSTANCE.isEnabled() || optional.isEmpty()) {
            return;
        }
        optional.get().onSprint(sprintEvent);
    }

    @EventTarget
    public void onRotation(RotationEvent rotationEvent) {
        Optional<AntiKBMode> optional = AntiKBMode.findMode(mode.getValue());
        if (FireballBlink.INSTANCE.isEnabled() || HighJump.INSTANCE.isEnabled() || optional.isEmpty()) {
            return;
        }
        optional.get().onRotation(rotationEvent);
    }

    @EventTarget
    public void onMotion(MotionEvent motionEvent) {
        Optional<AntiKBMode> optional = AntiKBMode.findMode(mode.getValue());
        if (FireballBlink.INSTANCE.isEnabled() || HighJump.INSTANCE.isEnabled() || optional.isEmpty()) {
            return;
        }
        optional.get().onMotion(motionEvent);
    }

    @EventTarget(value=1)
    public void onReceivePacket(ReceivePacketEvent receivePacketEvent) {
        Optional<AntiKBMode> optional = AntiKBMode.findMode(mode.getValue());
        if (FireballBlink.INSTANCE.isEnabled() || HighJump.INSTANCE.isEnabled() || optional.isEmpty()) {
            return;
        }
        optional.get().onReceivePacket(receivePacketEvent);
    }

    @EventTarget
    public void onDisconnect(DisconnectEvent disconnectEvent) {
        Optional<AntiKBMode> optional = AntiKBMode.findMode(mode.getValue());
        if (optional.isEmpty()) {
            return;
        }
        optional.get().onDisconnect(disconnectEvent);
    }

    @EventTarget(value=3)
    public void onStrafe(StrafeEvent strafeEvent) {
        Optional<AntiKBMode> optional = AntiKBMode.findMode(mode.getValue());
        if (FireballBlink.INSTANCE.isEnabled() || HighJump.INSTANCE.isEnabled() || optional.isEmpty()) {
            return;
        }
        optional.get().onStrafe(strafeEvent);
    }

    @EventTarget
    public void onRender(RenderEvent renderEvent) {
        Optional<AntiKBMode> optional = AntiKBMode.findMode(mode.getValue());
        if (FireballBlink.INSTANCE.isEnabled() || HighJump.INSTANCE.isEnabled() || optional.isEmpty()) {
            return;
        }
        optional.get().onRender(renderEvent);
    }

    @EventTarget
    public void onRender2D(Render2DEvent render2DEvent) {
        Optional<AntiKBMode> optional = AntiKBMode.findMode(mode.getValue());
        if (FireballBlink.INSTANCE.isEnabled() || HighJump.INSTANCE.isEnabled() || optional.isEmpty()) {
            return;
        }
        optional.get().onRender2D(render2DEvent);
    }

    static {
        mode = new ModeSetting("Mode", "Jump Reset", "Mix", "NoXZ").withDefault("NoXZ");
    }
}