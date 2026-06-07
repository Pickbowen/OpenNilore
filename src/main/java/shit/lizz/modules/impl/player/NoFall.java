package shit.lizz.modules.impl.player;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import shit.lizz.event.impl.KeyEvent;
import shit.lizz.event.impl.MotionEvent;
import shit.lizz.event.impl.PacketEvent;
import shit.lizz.event.impl.StrafeEvent;
import shit.lizz.event.impl.TickEvent;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.settings.impl.NumberSetting;
import shit.lizz.utils.misc.PacketUtil;
import shit.lizz.event.EventTarget;

public class NoFall
extends Module {
    public static NoFall INSTANCE;
    private final NumberSetting fallDistanceSetting = new NumberSetting("Fall Distance", 3.0, 0.0, 10.0, 0.5);
    private boolean isFalling = false;
    private boolean sentFlyPacket = false;
    public boolean jumpLandingBoost = false;
    private boolean receivedPositionPacket = false;
    public boolean boostActive = false;
    private int boostTick = 0;
    private int airTicks = 0;
    private boolean jumpToggle = false;

    public NoFall() {
        super("NoFall", Category.PLAYER);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.reset();
    }

    @Override
    public void onDisable() {
        this.reset();
    }

    private void reset() {
        this.isFalling = false;
        this.sentFlyPacket = false;
        this.jumpLandingBoost = false;
        this.receivedPositionPacket = false;
        this.boostActive = false;
        this.jumpToggle = false;
        this.boostTick = 0;
    }

    @EventTarget(value=0)
    public void onTick(TickEvent tickEvent) {
        if (mc.player == null) {
            return;
        }
        this.airTicks = mc.player.onGround() ? 0 : ++this.airTicks;
        if (this.isFalling && this.airTicks > 0) {
            this.jumpToggle = !this.jumpToggle;
            mc.options.keyJump.setDown(this.jumpToggle);
        }
        if (this.receivedPositionPacket) {
            if (mc.player.getY() > 0.3525 && mc.options.keyJump.isDown() && this.airTicks > 0) {
                this.boostActive = true;
            }
            if (this.boostActive) {
                ++this.boostTick;
                if (this.boostTick >= 21) {
                    this.reset();
                }
            }
        }
    }

    @EventTarget(value=0)
    public void onKey(KeyEvent keyEvent) {
        if (mc.player == null) {
            return;
        }
        if (this.isFalling && !mc.player.onGround() && keyEvent.getKeyCode() == 32) {
            keyEvent.setCancelled(true);
        }
        if (this.boostTick > 0 && keyEvent.getKeyCode() == 32) {
            keyEvent.setCancelled(true);
        }
    }

    @EventTarget
    public void onMotion(MotionEvent motionEvent) {
        if (mc.player == null) {
            return;
        }
        if (motionEvent.isPre() || mc.isSingleplayer()) {
            return;
        }
        if (mc.player.fallDistance >= this.fallDistanceSetting.getValue().floatValue()) {
            this.isFalling = true;
        }
        if (mc.player.onGround() && mc.player.verticalCollision && this.isFalling) {
            this.jumpLandingBoost = true;
            this.sentFlyPacket = true;
            this.sendFlyPacket();
        }
    }

    @EventTarget
    public void onStrafe(StrafeEvent strafeEvent) {
        if (mc.player == null) {
            return;
        }
        if (this.jumpLandingBoost) {
            mc.options.keyJump.setDown(true);
        } else if (!this.isFalling || mc.player.onGround()) {
            mc.options.keyJump.setDown(InputConstants.isKeyDown(mc.getWindow().getWindow(), mc.options.keyJump.getKey().getValue()));
        }
    }

    @EventTarget
    public void onPacket(PacketEvent packetEvent) {
        if (packetEvent.getPacket() instanceof ClientboundPlayerPositionPacket && this.sentFlyPacket) {
            this.receivedPositionPacket = true;
        }
    }

    private void sendFlyPacket() {
        if (mc.player == null) {
            return;
        }
        PacketUtil.sendQueued(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
    }
}