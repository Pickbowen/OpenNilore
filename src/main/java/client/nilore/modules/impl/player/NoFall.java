package client.nilore.modules.impl.player;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import client.nilore.event.impl.GameTickEvent;
import client.nilore.event.impl.MotionEvent;
import client.nilore.event.impl.PacketEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.movement.Scaffold;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.misc.PacketUtil;
import client.nilore.event.EventTarget;

public class NoFall
extends Module {
    public static NoFall INSTANCE;
    private static final int GROUND_CANCEL_TICKS = 3;
    private static final int BOOST_TIMEOUT_TICKS = 20;
    private static final double LANDING_LIFT = 0.001D;

    public boolean jumpLandingBoost = false;
    public boolean boostActive = false;
    private final NumberSetting fallDistanceSetting = new NumberSetting("Fall Distance", 3.0, 0.0, 10.0, 0.5);
    private boolean fallDistanceReached = false;
    private boolean sentFlyPacket = false;
    private int jumpTicks = 0;
    private boolean jumpRestorePending = false;
    private int suppressGroundTicks = 0;
    private int boostTicks = 0;

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
        this.fallDistanceReached = false;
        this.sentFlyPacket = false;
        this.jumpLandingBoost = false;
        this.boostActive = false;
        this.jumpTicks = 0;
        this.jumpRestorePending = false;
        this.suppressGroundTicks = 0;
        this.boostTicks = 0;
    }

    @EventTarget(value=0)
    public void onMotion(MotionEvent motionEvent) {
        if (mc.player == null || mc.isSingleplayer()) {
            return;
        }
        if (mc.player.onClimbable()) {
            this.fallDistanceReached = false;
            return;
        }
        if (!mc.player.onGround()) {
            if (mc.player.fallDistance >= this.fallDistanceSetting.getValue().floatValue()) {
                this.fallDistanceReached = true;
            } else if (!this.boostActive) {
                // Only re-arm once the previous boost is finished. Clearing it while the packet is
                // still in flight would drop the lift that the server's answer triggers, and
                // leaving it latched forever (no answer at all) is handled by the boost timeout.
                this.sentFlyPacket = false;
            }
            return;
        }
        if (this.fallDistanceReached) {
            this.fallDistanceReached = false;
            if (!this.sentFlyPacket) {
                this.sentFlyPacket = true;
                this.boostActive = true;
                // 1) Tell the server we started gliding so it stops tracking fall damage.
                PacketUtil.sendQueued(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                // 2) Lift ourselves off the ground ...
                mc.player.setPos(mc.player.getX(), mc.player.getY() + LANDING_LIFT, mc.player.getZ());
                // 3) ... keep the landing out of the movement stream for a few ticks ...
                this.suppressGroundTicks = GROUND_CANCEL_TICKS;
                this.boostTicks = BOOST_TIMEOUT_TICKS;
                // 4) ... and jump, so the next tick reports onGround=false on its own.
                this.jumpTicks = 1;
                this.jumpRestorePending = true;
            }
        }
    }

    @EventTarget
    public void onPacketSend(PacketEvent packetEvent) {
        if (mc.player == null || packetEvent.isIncoming() || this.suppressGroundTicks <= 0) {
            return;
        }
        if (packetEvent.getPacket() instanceof ServerboundMovePlayerPacket movePacket && movePacket.isOnGround()) {
            packetEvent.setCancelled(true);
        }
    }

    @EventTarget
    public void onGameTick(GameTickEvent gameTickEvent) {
        if (mc.player == null || mc.options == null || mc.getWindow() == null) {
            return;
        }
        if (this.suppressGroundTicks > 0) {
            --this.suppressGroundTicks;
        }
        if (this.boostTicks > 0 && --this.boostTicks == 0) {
            // No answer from the server within the window — re-arm so the next fall can fire again.
            this.boostActive = false;
            this.sentFlyPacket = false;
        }
        if (this.jumpTicks > 0) {
            --this.jumpTicks;
            if (Scaffold.INSTANCE != null && Scaffold.INSTANCE.isEnabled()) {
                this.jumpTicks = 0;
                return;
            }
            mc.options.keyJump.setDown(true);
            return;
        }
        if (this.jumpRestorePending) {
            this.jumpRestorePending = false;
            mc.options.keyJump.setDown(InputConstants.isKeyDown(mc.getWindow().getWindow(), mc.options.keyJump.getKey().getValue()));
        }
    }

    @EventTarget
    public void onPacket(PacketEvent packetEvent) {
        if (mc.player == null) {
            return;
        }
        if (this.sentFlyPacket && packetEvent.getPacket() instanceof ClientboundPlayerPositionPacket) {
            // Server answered the elytra packet: nudge ourselves up so we are not parked exactly on
            // the block face the server just measured.
            mc.player.setPos(mc.player.getX(), mc.player.getY() + 1.0E-9, mc.player.getZ());
            this.sentFlyPacket = false;
            this.boostActive = false;
            this.boostTicks = 0;
        }
    }
}
