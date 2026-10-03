package client.nilore.modules.impl.combat.antikb;

import java.util.concurrent.LinkedBlockingDeque;

import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import client.nilore.event.impl.DisconnectEvent;
import client.nilore.event.impl.EntityRemoveEvent;
import client.nilore.event.impl.GameTickEvent;
import client.nilore.event.impl.MotionEvent;
import client.nilore.event.impl.PreMotionEvent;
import client.nilore.event.impl.ReceivePacketEvent;
import client.nilore.event.impl.Render2DEvent;
import client.nilore.event.impl.RotationEvent;
import client.nilore.event.impl.SprintEvent;
import client.nilore.event.impl.StrafeEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.modules.impl.combat.AntiKB;
import client.nilore.modules.impl.combat.KillAura;
import client.nilore.modules.impl.movement.NoSlow;
import client.nilore.modules.impl.movement.Scaffold;
import client.nilore.modules.impl.player.Stuck;
import client.nilore.utils.game.FightManager;
import client.nilore.utils.game.RotationUtil;
import client.nilore.utils.misc.ChatUtil;

public class NoXZMode
        extends AntiKBMode {
    public static NoXZMode INSTANCE;
    public static boolean handlingVelocity;

    private final LinkedBlockingDeque<Packet<ClientGamePacketListener>> packetQueue = new LinkedBlockingDeque<>();

    public boolean delaying;
    private boolean velocityPending;
    private boolean positionCorrection;
    private int delayTicks;
    private int velocityPendingTicks;
    private int attacksRemaining;
    private Entity target;
    private float jumpResetYaw;
    private float jumpResetDifference;
    private boolean forceForward;

    @Override
    public boolean isActive() {
        return this.delaying || this.attacksRemaining > 0;
    }

    public static boolean isCountering() {
        return INSTANCE != null && INSTANCE.attacksRemaining > 0;
    }

    public static boolean isInAttackWindow() {
        return isCountering();
    }

    public static boolean isInDelayWindow() {
        return INSTANCE != null && INSTANCE.delaying;
    }

    public NoXZMode() {
        super("NoXZ");
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.clearGrimState();
    }

    @Override
    public void onDisable() {
        this.releaseQueue();
        this.clearGrimState();
    }

    @Override
    public String getName() {
        return "";
    }

    @Override
    public void onRotation(RotationEvent rotationEvent) {
    }

    @Override
    public void onMotion(MotionEvent motionEvent) {
    }

    @Override
    public void onGameTick(GameTickEvent gameTickEvent) {
    }

    @Override
    public void onPreMotion(PreMotionEvent preMotionEvent) {
    }

    @Override
    public void onSprint(SprintEvent sprintEvent) {
    }

    @Override
    public void onAttack(EntityRemoveEvent entityRemoveEvent) {
    }

    @Override
    public void onRender2D(Render2DEvent render2DEvent) {
    }

    @Override
    public void onReceivePacket(ReceivePacketEvent receivePacketEvent) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        Packet<ClientGamePacketListener> packet = receivePacketEvent.getPacket();

        if (packet instanceof ClientboundPlayerPositionPacket) {
            this.positionCorrection = true;
            return;
        }

        if (this.shouldAbortGrim()) {
            return;
        }

        if (packet instanceof ClientboundSetEntityMotionPacket motion
                && motion.getId() == mc.player.getId()) {
            this.updateJumpReset(motion);
            if (!this.delaying) {
                this.delaying = true;
                this.delayTicks = 0;
                handlingVelocity = true;
                this.debug("Delay started");
            }
            receivePacketEvent.setCancelled(true);
            this.packetQueue.add(packet);
            return;
        }

        if (!this.delaying || this.isImmediatePacket(packet)) {
            return;
        }
        receivePacketEvent.setCancelled(true);
        this.packetQueue.add(packet);
    }

    @Override
    public void onDisconnect(DisconnectEvent disconnectEvent) {
        this.releaseQueue();
        this.clearGrimState();
    }

    @Override
    public void onStrafe(StrafeEvent strafeEvent) {
        if (mc.player == null) {
            return;
        }

        if (this.forceForward) {
            strafeEvent.setForward(1.0f);
            strafeEvent.setStrafe(0.0f);
            if (!mc.player.isSprinting()) {
                mc.player.setSprinting(true);
            }
        }

        if (this.velocityPending && mc.player.isSprinting()) {
            strafeEvent.setSprinting(true);

            if (Math.abs(this.jumpResetDifference) <= 45.0f
                    && AntiKB.INSTANCE.sideStrafe.getValue() && this.velocityPendingTicks < 4) {
                float[] movement = this.jumpResetMovement();
                if (movement[0] != 0.0f) {
                    strafeEvent.setForward(movement[0]);
                }
                if (movement[1] != 0.0f) {
                    strafeEvent.setStrafe(movement[1]);
                }
            } else {
                this.velocityPending = false;
            }
        }
    }

    @Override
    public void onTick(TickEvent tickEvent) {
        if (mc.player == null || mc.level == null) {
            return;
        }

        this.target = KillAura.target;

        if (this.velocityPending) {
            ++this.velocityPendingTicks;
            if (this.velocityPendingTicks >= 4) {
                this.velocityPending = false;
                this.velocityPendingTicks = 0;
            }
        }

        if (this.shouldAbortGrim()) {
            if (this.delaying) {
                this.debug("Delay force released");
            }
            this.resetGrimState();
            return;
        }

        if (this.delaying) {
            this.forceForward = true;
            this.attacksRemaining = 0;
            ++this.delayTicks;
            if (this.delayTicks >= AntiKB.INSTANCE.maxDelayTicks.getValue().intValue()) {
                this.debug("Delay timeout");
                this.resetGrimState();
                return;
            }
            boolean insideBufferRange = this.target != null
                    && this.target.distanceTo(mc.player) <= AntiKB.INSTANCE.bufferRange.getValue().doubleValue();
            if (this.isLookingAtTarget() && insideBufferRange && mc.player.isSprinting()
                    && (mc.player.onGround() || AntiKB.INSTANCE.reduceMode.is("Sprint"))) {
                this.attacksRemaining = AntiKB.INSTANCE.attackAmount.getValue().intValue();
                this.delaying = false;
                handlingVelocity = false;
                this.debug("Release x" + this.attacksRemaining);
                this.releaseLastMotionOnly();
                this.velocityPending = true;
                this.velocityPendingTicks = 0;
            }
        }

        if (this.attacksRemaining > 0) {
            if (!this.isLookingAtTarget() || !mc.player.isSprinting()) {
                return;
            }
            this.performReduceAttack(this.target);
            --this.attacksRemaining;
            if (this.attacksRemaining == 0) {
                this.forceForward = false;
            }
        }
    }

    private void performReduceAttack(Entity entity) {
        if (entity == null || mc.player == null || mc.getConnection() == null) {
            return;
        }
        if (!FightManager.attackAndLock()) {
            return;
        }
        FightManager.attackByPacket(entity, false);
        mc.player.swing(InteractionHand.MAIN_HAND);
        mc.player.setDeltaMovement(mc.player.getDeltaMovement().multiply(0.6, 1.0, 0.6));
        mc.player.setSprinting(false);
    }

    private boolean isLookingAtTarget() {
        if (this.target == null) {
            return false;
        }
        return RotationUtil.isLookingAt(this.target,
                AntiKB.INSTANCE.attackRange.getValue().doubleValue(), 0.1f);
    }

    private boolean shouldAbortGrim() {
        if (mc.player == null || mc.getConnection() == null) {
            return true;
        }
        if (mc.player.isUsingItem() || mc.player.isInLava() || mc.player.isInWater()) {
            return true;
        }
        if (mc.level != null && mc.level.getBlockState(mc.player.blockPosition()).is(Blocks.COBWEB)) {
            return true;
        }
        if (this.positionCorrection) {
            return true;
        }
        if (mc.screen instanceof InventoryScreen) {
            return true;
        }
        if (this.target == null || !this.target.isAlive()) {
            return true;
        }
        if (mc.player.isDeadOrDying() || mc.player.isSpectator()) {
            return true;
        }
        if (NoSlow.isHandling()) {
            return true;
        }
        if (Scaffold.INSTANCE != null && Scaffold.INSTANCE.isEnabled()) {
            return true;
        }
        Stuck stuck = Stuck.INSTANCE;
        return stuck != null && stuck.isEnabled();
    }

    private boolean isImmediatePacket(Packet<?> packet) {
        return packet instanceof ClientboundSetEntityMotionPacket
                || packet instanceof ClientboundSetHealthPacket
                || packet instanceof ClientboundPlayerPositionPacket
                || packet instanceof ClientboundSoundPacket
                || packet instanceof ClientboundPlayerChatPacket
                || packet instanceof ClientboundPlayerCombatKillPacket
                || packet instanceof ClientboundContainerClosePacket
                || packet instanceof ClientboundHurtAnimationPacket
                || packet instanceof ClientboundSetTitleTextPacket
                || packet instanceof ClientboundSetPlayerTeamPacket
                || packet instanceof ClientboundSystemChatPacket
                || packet instanceof ClientboundDisconnectPacket
                || packet instanceof ClientboundAnimatePacket animation
                && animation.getId() != mc.player.getId();
    }

    private void releaseQueue() {
        if (mc.getConnection() == null) {
            this.packetQueue.clear();
            return;
        }
        Packet<ClientGamePacketListener> packet;
        while ((packet = this.packetQueue.poll()) != null) {
            try {
                packet.handle(mc.getConnection());
            } catch (Exception exception) {
                this.packetQueue.clear();
                break;
            }
        }
    }

    private void releaseLastMotionOnly() {
        if (mc.getConnection() == null) {
            this.packetQueue.clear();
            return;
        }
        ClientboundSetEntityMotionPacket lastMotion = null;
        for (Packet<ClientGamePacketListener> queued : this.packetQueue) {
            if (queued instanceof ClientboundSetEntityMotionPacket motion) {
                lastMotion = motion;
            }
        }
        Packet<ClientGamePacketListener> packet;
        while ((packet = this.packetQueue.poll()) != null) {
            if (packet instanceof ClientboundSetEntityMotionPacket && packet != lastMotion) {
                continue;
            }
            try {
                packet.handle(mc.getConnection());
            } catch (Exception exception) {
                this.packetQueue.clear();
                break;
            }
        }
    }

    private void resetGrimState() {
        this.releaseQueue();
        this.clearGrimState();
    }

    private void clearGrimState() {
        this.delaying = false;
        this.forceForward = false;
        this.velocityPending = false;
        this.positionCorrection = false;
        this.delayTicks = 0;
        this.velocityPendingTicks = 0;
        this.attacksRemaining = 0;
        this.target = null;
        handlingVelocity = false;
    }

    private void updateJumpReset(ClientboundSetEntityMotionPacket motion) {
        if (mc.player == null) {
            return;
        }
        double x = -motion.getXa();
        double z = -motion.getZa();
        if (Math.abs(x) < 1.0E-6 && Math.abs(z) < 1.0E-6) {
            this.jumpResetYaw = mc.player.getYRot();
            this.jumpResetDifference = 0.0f;
            return;
        }
        this.jumpResetYaw = (float) Math.toDegrees(Math.atan2(-x, z));
        this.jumpResetDifference = Mth.wrapDegrees(this.jumpResetYaw - mc.player.getYRot());
    }

    private float[] jumpResetMovement() {
        float deltaYaw = Mth.wrapDegrees(this.jumpResetYaw - mc.player.getYRot());
        if (this.jumpResetDifference > 22.5f) {
            deltaYaw -= 45.0f;
        } else if (this.jumpResetDifference < -22.5f) {
            deltaYaw += 45.0f;
        }
        double radians = Math.toRadians(deltaYaw);
        double x = Math.sin(radians);
        double z = Math.cos(radians);
        float forward = z > 0.707 ? 1.0f : z < -0.707 ? -1.0f : 0.0f;
        float strafe = x > 0.707 ? -1.0f : x < -0.707 ? 1.0f : 0.0f;
        return new float[]{forward, strafe};
    }

    private void debug(String message) {
        if (AntiKB.INSTANCE.debugLog.getValue()) {
            ChatUtil.print("[NoXZ] " + message);
        }
    }

    static {
        handlingVelocity = false;
    }
}
