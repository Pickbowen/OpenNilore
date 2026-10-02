package client.nilore.modules.impl.combat.antikb;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.Vec3;
import client.nilore.event.impl.DisconnectEvent;
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
import client.nilore.modules.impl.player.Stuck;
import client.nilore.utils.game.RotationUtil;
import client.nilore.utils.misc.ChatUtil;
import client.nilore.utils.render.RenderUtil;

public class NoXZMode
        extends AntiKBMode {
    public static NoXZMode INSTANCE;
    public static boolean handlingVelocity;
    public static boolean velocityHandled;
    private Entity attackTarget = null;
    private int attacksRemaining = 0;
    private boolean isSuspending = false;
    private boolean seenTeleport = false;
    private int noAimTicks = 0;
    private int delayTicks = 0;
    private ClientboundSetEntityMotionPacket knockbackPacket = null;
    private final LinkedBlockingDeque<Packet<ClientGamePacketListener>> packetQueue = new LinkedBlockingDeque();
    private final Map<Entity, Vec3> positions = new HashMap<>();

    @Override
    public boolean isActive() {
        return this.velocityHandled;
    }

    public static boolean isInAttackWindow() {
        return isCountering();
    }

    public static boolean isInDelayWindow() {
        return INSTANCE != null && INSTANCE.isSuspending;
    }

    public static boolean isCountering() {
        return INSTANCE != null && INSTANCE.attacksRemaining > 0;
    }

    public NoXZMode() {
        super("NoXZ");
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.resetAll();
    }

    @Override
    public void onDisable() {
        this.resetAll();
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
    public void onReceivePacket(ReceivePacketEvent receivePacketEvent) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        Packet<ClientGamePacketListener> packet = receivePacketEvent.getPacket();
        if (packet instanceof ClientboundRespawnPacket
                || packet instanceof ClientboundLoginPacket) {
            this.resetAll();
            return;
        }
        if (packet instanceof ClientboundPlayerPositionPacket) {
            this.seenTeleport = true;
            return;
        }
        if (packet instanceof ClientboundSetEntityMotionPacket motionPacket) {
            if (motionPacket.getId() != mc.player.getId()) {
                return;
            }
            if (mc.player.isUsingItem() || NoSlow.isHandling()
                    || mc.getConnection() == null || this.seenTeleport || KillAura.target == null) {
                return;
            }
            this.enterSuspension(motionPacket);
            receivePacketEvent.setCancelled(true);
            return;
        }
        if (this.isSuspending) {
            if (packet instanceof ClientboundMoveEntityPacket move && move.getEntity(mc.level) == mc.player) {
                return;
            }
            if (packet instanceof ClientboundMoveEntityPacket move) {
                Entity moved = move.getEntity(mc.level);
                if (moved != null) {
                    Vec3 base = this.positions.getOrDefault(moved,
                            new Vec3(moved.getX(), moved.getY(), moved.getZ()));
                    if (move.hasPosition()) {
                        this.positions.put(moved, base.add((double)move.getXa() / 4096.0,
                                (double)move.getYa() / 4096.0, (double)move.getZa() / 4096.0));
                    }
                }
                this.packetQueue.add(packet);
                receivePacketEvent.setCancelled(true);
            } else if (packet instanceof ClientboundTeleportEntityPacket teleport) {
                Entity moved = mc.level.getEntity(teleport.getId());
                if (moved != null) {
                    this.positions.put(moved, new Vec3(teleport.getX(), teleport.getY(), teleport.getZ()));
                }
                this.packetQueue.add(packet);
                receivePacketEvent.setCancelled(true);
            } else if (packet instanceof ClientboundPingPacket || !this.isAllowedPacket(packet)) {
                this.packetQueue.add(packet);
                receivePacketEvent.setCancelled(true);
            }
            return;
        }
    }

    @Override
    public void onDisconnect(DisconnectEvent disconnectEvent) {
        this.resetAll();
    }

    @Override
    public void onTick(TickEvent tickEvent) {
        if (mc.player == null) {
            return;
        }
        if (this.shouldNotEngage() || this.noAimTicks >= 3) {
            this.resetAll();
            return;
        }
        if (mc.player.isDeadOrDying() || !mc.player.isAlive() || this.shouldIgnore()) {
            this.clearTarget();
            if (this.isSuspending) {
                this.release();
            }
            return;
        }
        if (this.isSuspending) {
            if (this.delayTicks >= AntiKB.INSTANCE.maxDelayTicks.getValue().intValue()) {
                this.resetAll();
                return;
            }
            ++this.delayTicks;
            this.attacksRemaining = 0;
            Entity target = this.getAttackTarget();
            boolean canAttack = target != null
                    && mc.hitResult instanceof EntityHitResult hit
                    && hit.getEntity() == target;
            if (canAttack && mc.player.isSprinting()) {
                this.flushQueue();
                this.noAimTicks = 0;
                this.attackTarget = target;
                this.attacksRemaining = this.getAttackCount(this.knockbackPacket);
                this.doAttackSequence(tickEvent);
                this.isSuspending = false;
                handlingVelocity = false;
            }
            return;
        }
        if (this.attacksRemaining > 0 && this.attackTarget != null) {
            boolean aiming = mc.hitResult instanceof EntityHitResult hit
                    && hit.getEntity() == this.attackTarget;
            if (!aiming) {
                ++this.noAimTicks;
                if (AntiKB.INSTANCE.debugLog.getValue()) {
                    ChatUtil.print("Failed (RayCast)");
                }
                return;
            }
            if (!mc.player.isSprinting()) {
                if (AntiKB.INSTANCE.debugLog.getValue()) {
                    ChatUtil.print("Failed (Sprint)");
                }
                return;
            }
            this.doAttackSequence(tickEvent);
        }
    }

    @Override
    public void onStrafe(StrafeEvent strafeEvent) {
    }

    @Override
    public void onRender2D(Render2DEvent event) {
        if (!AntiKB.INSTANCE.renderBar.getValue()
                || !AntiKB.INSTANCE.isEnabled()
                || (!handlingVelocity && !velocityHandled)) {
            return;
        }
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();

        float barWidth = 100.0f;
        float barHeight = 2.0f;
        float barX = width / 2.0f - barWidth / 2.0f;
        float barY = height / 2.0f + height * 0.10f;

        RenderUtil.drawFilledRect(event.poseStack(), barX, barY, barWidth, barHeight,
                new Color(30, 30, 36, 180).getRGB());
        float progress = Math.min(1.0f,
                (float) this.delayTicks / Math.max(1, AntiKB.INSTANCE.maxDelayTicks.getValue().intValue()));
        if (progress > 0.0f) {
            RenderUtil.drawFilledRect(event.poseStack(), barX, barY, barWidth * progress, barHeight,
                    new Color(0, 180, 255, 230).getRGB());
        }
    }

    private void enterSuspension(ClientboundSetEntityMotionPacket packet) {
        if (!this.isSuspending) {
            this.delayTicks = 0;
        }
        this.isSuspending = true;
        handlingVelocity = true;
        velocityHandled = true;
        this.knockbackPacket = packet;
        this.packetQueue.add(packet);
    }

    private boolean shouldNotEngage() {
        return mc.player == null || mc.player.isUsingItem() || NoSlow.isHandling()
                || mc.getConnection() == null || this.seenTeleport || KillAura.target == null;
    }

    private void resetAll() {
        this.flushQueue();
        this.clearTarget();
        this.positions.clear();
        this.resetSuspension();
        this.seenTeleport = false;
        this.noAimTicks = 0;
    }

    private void clearTarget() {
        this.attackTarget = null;
        this.attacksRemaining = 0;
    }

    private void resetSuspension() {
        this.isSuspending = false;
        handlingVelocity = false;
        velocityHandled = false;
        this.delayTicks = 0;
        this.knockbackPacket = null;
    }

    private void release() {
        this.flushQueue();
        this.resetSuspension();
    }

    private boolean shouldIgnore() {
        if (mc.player == null || mc.level == null) {
            return true;
        }
        if (mc.player.isDeadOrDying() || !mc.player.isAlive() || mc.player.getHealth() <= 0.0f) {
            return true;
        }
        if (mc.player.isSpectator() || mc.player.getAbilities().flying) {
            return true;
        }
        if (mc.player.isInLava() || mc.player.isOnFire() || mc.player.isInWater() || mc.player.onClimbable() || mc.player.isSleeping()) {
            return true;
        }
        if (mc.level.getBlockState(mc.player.blockPosition()).is(Blocks.COBWEB)) {
            return true;
        }
        Stuck stuck = Stuck.INSTANCE;
        return stuck != null && stuck.isEnabled();
    }

    private int getAttackCount(ClientboundSetEntityMotionPacket motionPacket) {
        if (!AntiKB.INSTANCE.autoAttackCount.getValue() || motionPacket == null) {
            return AntiKB.INSTANCE.attackAmount.getValue().intValue();
        }
        double velocity = Math.sqrt((double) motionPacket.getXa() * motionPacket.getXa()
                + (double) motionPacket.getYa() * motionPacket.getYa());
        if (velocity < 1000.0) {
            return 0;
        }
        if (velocity < 2000.0) {
            return 3;
        }
        if (velocity < 10000.0) {
            return 4;
        }
        return 5;
    }

    private Entity getAttackTarget() {
        return KillAura.target;
    }

    private boolean isValidTarget(Entity entity) {
        LivingEntity livingEntity;
        if (entity == null || !entity.isAlive()) {
            return false;
        }
        if (entity instanceof LivingEntity && ((livingEntity = (LivingEntity)entity).isDeadOrDying() || livingEntity.getHealth() <= 0.0f)) {
            return false;
        }
        return RotationUtil.isWithinReach(entity);
    }

    private void doAttackSequence(TickEvent tickEvent) {
        if (this.attackTarget == null || !this.attackTarget.isAlive()) {
            this.clearTarget();
            return;
        }
        if (!RotationUtil.isWithinReach(this.attackTarget)) {
            this.clearTarget();
            return;
        }
        this.attacksRemaining--;
        this.doAttack(this.attackTarget);
        if (this.attacksRemaining <= 0) {
            this.clearTarget();
        }
    }

    private boolean doAttack(Entity entity) {
        if (mc.player == null || mc.gameMode == null) {
            return false;
        }
        mc.gameMode.attack(mc.player, entity);
        mc.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private void flushQueue() {
        if (mc.getConnection() == null) {
            this.packetQueue.clear();
            return;
        }
        mc.execute(() -> {
            Packet<ClientGamePacketListener> packet;
            while ((packet = this.packetQueue.poll()) != null) {
                try {
                    packet.handle(mc.getConnection());
                } catch (Exception exception) {
                    this.packetQueue.clear();
                    break;
                }
            }
        });
    }

    private boolean isAllowedPacket(Packet<?> packet) {
        return packet instanceof ClientboundSetEntityMotionPacket || packet instanceof ClientboundSetHealthPacket || packet instanceof ClientboundPlayerPositionPacket || packet instanceof ClientboundRespawnPacket || packet instanceof ClientboundLoginPacket || packet instanceof ClientboundSoundPacket || packet instanceof ClientboundPlayerChatPacket || packet instanceof ClientboundPlayerCombatKillPacket || packet instanceof ClientboundContainerClosePacket || packet instanceof ClientboundHurtAnimationPacket || packet instanceof ClientboundSetTitleTextPacket || packet instanceof ClientboundSetPlayerTeamPacket || packet instanceof ClientboundSystemChatPacket || packet instanceof ClientboundDisconnectPacket || packet instanceof ClientboundAnimatePacket && ((ClientboundAnimatePacket)packet).getId() != mc.player.getId();
    }

    static {
        handlingVelocity = false;
        velocityHandled = false;
    }
}