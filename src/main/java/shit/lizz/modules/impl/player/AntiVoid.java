package shit.lizz.modules.impl.player;

import java.util.concurrent.LinkedBlockingDeque;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.level.block.Blocks;
import shit.lizz.event.impl.MotionEvent;
import shit.lizz.event.impl.PacketEvent;
import shit.lizz.event.impl.ReceivePacketEvent;
import shit.lizz.event.impl.StrafeEvent;
import shit.lizz.event.impl.TickEvent;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.modules.impl.movement.Scaffold;
import shit.lizz.settings.impl.NumberSetting;
import shit.lizz.utils.game.MotionSimulator;
import shit.lizz.utils.game.PlayerUtil;
import shit.lizz.utils.misc.PacketUtil;
import shit.lizz.event.EventTarget;

public class AntiVoid
extends Module {
    public static AntiVoid INSTANCE;
    private final NumberSetting fallDistanceSetting = new NumberSetting("Fall Distance", 5.0, 1.0, 10.0, 0.5);
    private final LinkedBlockingDeque<Packet<ServerGamePacketListener>> bufferedPackets = new LinkedBlockingDeque();
    private boolean scaffoldWasActive = false;
    public boolean bufferingPackets = false;
    private boolean sentFlyPacket = false;
    private boolean jumpBoostActive = false;
    private boolean receivedPositionPacket = false;
    private int jumpBoostTick = 0;
    private int groundTicks = 0;
    private boolean awaitingScaffoldDisable;

    public AntiVoid() {
        super("AntiVoid", Category.PLAYER);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.scaffoldWasActive = false;
        this.reset();
        this.awaitingScaffoldDisable = false;
    }

    @Override
    public void onDisable() {
        this.scaffoldWasActive = false;
        if (!this.bufferedPackets.isEmpty()) {
            this.bufferedPackets.forEach(PacketUtil::sendQueued);
        }
        this.reset();
    }

    @EventTarget
    public void onMotion(MotionEvent motionEvent) {
        if (mc.player == null || mc.level == null || motionEvent.isPre()) {
            return;
        }
        this.groundTicks = mc.player.onGround() ? ++this.groundTicks : 0;
        if (mc.player.onGround()) {
            if (this.awaitingScaffoldDisable && this.groundTicks > 6 && new MotionSimulator(mc.player).findLandingBlock(60) != null) {
                Scaffold.INSTANCE.setEnabled(false);
                this.awaitingScaffoldDisable = false;
            }
            if (this.receivedPositionPacket) {
                if (Scaffold.INSTANCE.isEnabled()) {
                    this.awaitingScaffoldDisable = true;
                }
                if (this.jumpBoostActive) {
                    this.jumpBoostActive = false;
                    mc.options.keyJump.setDown(false);
                }
                this.reset();
                this.receivedPositionPacket = false;
            }
            if (this.bufferingPackets || this.sentFlyPacket) {
                this.reset();
            }
            return;
        }
        if (!this.bufferingPackets && !this.sentFlyPacket && !mc.player.onGround() && mc.player.getDeltaMovement().y < 0.0) {
            boolean shouldBuffer = mc.player.getDeltaMovement().y < 0.1 && this.isVoidBelow() && !PlayerUtil.isSafe(mc.player.getY() + (double)mc.player.getEyeHeight());
            if (shouldBuffer) {
                this.scaffoldWasActive = Scaffold.INSTANCE.isEnabled();
                this.bufferingPackets = true;
                this.bufferedPackets.clear();
            }
        }
        if (!this.scaffoldWasActive && this.bufferingPackets && mc.player.fallDistance > this.fallDistanceSetting.getValue().floatValue()) {
            if (this.receivedPositionPacket && !Scaffold.INSTANCE.isEnabled()) {
                Scaffold.INSTANCE.setEnabled(true);
                this.reset();
            }
            PacketUtil.sendQueued(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            this.bufferedPackets.clear();
            this.bufferingPackets = false;
            this.sentFlyPacket = true;
        }
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        if (this.jumpBoostActive) {
            ++this.jumpBoostTick;
            if (this.jumpBoostTick > 10) {
                this.jumpBoostActive = false;
                this.jumpBoostTick = 0;
            }
        }
    }

    @EventTarget
    public void onStrafe(StrafeEvent strafeEvent) {
        if (this.jumpBoostActive) {
            mc.options.keyJump.setDown(true);
        }
    }

    @EventTarget
    public void onPacket(PacketEvent packetEvent) {
        if (mc.player == null) {
            return;
        }
        if (this.bufferingPackets && packetEvent.getPacket() instanceof ServerboundMovePlayerPacket) {
            packetEvent.setCancelled(true);
        }
    }

    @EventTarget
    public void onReceivePacket(ReceivePacketEvent receivePacketEvent) {
        if (mc.player == null) {
            return;
        }
        if (receivePacketEvent.getPacket() instanceof ClientboundPlayerPositionPacket && (this.sentFlyPacket || this.bufferingPackets)) {
            this.reset();
            this.jumpBoostActive = true;
            this.jumpBoostTick = 0;
            this.receivedPositionPacket = true;
        }
    }

    private void reset() {
        this.bufferingPackets = false;
        this.sentFlyPacket = false;
        this.bufferedPackets.clear();
    }

    private boolean isVoidBelow() {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int scanDepth = 30;
        for (int dy = 0; dy < scanDepth; ++dy) {
            cursor.set(mc.player.getX(), mc.player.getY() - (double)dy, mc.player.getZ());
            if (mc.level.getBlockState(cursor).getBlock() == Blocks.AIR) continue;
            return false;
        }
        return true;
    }
}