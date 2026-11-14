package client.nilore.modules.impl.combat.antikb;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import client.nilore.NiloreClient;
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
import client.nilore.modules.impl.combat.AntiKB;
import client.nilore.modules.impl.combat.KillAura;
import client.nilore.modules.impl.movement.NoSlow;
import client.nilore.modules.impl.movement.Scaffold;
import client.nilore.modules.impl.player.Stuck;
import client.nilore.utils.game.RotationUtil;
import client.nilore.utils.misc.ChatUtil;
import client.nilore.utils.render.RenderUtil;

public class NoXZMode
extends AntiKBMode {
    public static NoXZMode INSTANCE;
    public static boolean handlingVelocity;

    private final ConcurrentLinkedQueue<Packet<ClientGamePacketListener>> packetQueue = new ConcurrentLinkedQueue<>();
    private final Map<Entity, Vec3> entityPositions = new HashMap<>();

    public static boolean compensating;
    public static boolean inDelayWindow;
    public static int counterRemaining;

    private boolean positionCorrection;
    private int coolDownTicks;
    private int delayTicks;
    private Packet<ClientGamePacketListener> kbPacket;
    private long lastFireballTick = Long.MIN_VALUE;
    private int noAimTicks;
    private int noSprintTicks;
    private int jumpTicks;
    private boolean jumpKeyForced;
    private float barProgress;
    private float barAlpha;
    private long lastFrameNanos;
    private long compensateUntilMs = -1L;
    private Entity target;
    /**
     * 最近一次真正打到我的实体。
     *
     * <p>进入 delay window 的那一包（自己的 {@code ClientboundSetEntityMotionPacket}）只要求
     * {@link #shouldNotEngage()} 为 false，而它里面唯一的"软"条件就是 {@code target != null}。
     * 可 {@link #target} 每拍都被 {@code KillAura.target} 覆盖，而 KillAura 的
     * {@code isValidAttack} 会被 hurtTime / FoV / 距离过滤 —— 刚砍中的人 hurtTime 是 10，
     * 跳砍转身又会掉出 FoV，于是那一拍 {@code KillAura.target} 就是 null，"跳着砍人被打"
     * 永远进不了 delay window。受击来源本身是确定的，用它兜底。
     */
    private Entity lastAttacker;
    private int lastAttackerTick = Integer.MIN_VALUE / 2;

    private static final int WINDUP_TICKS = 2;

    @Override
    public boolean isActive() {
        return inDelayWindow || counterRemaining > 0;
    }

    public static boolean isCountering() {
        return counterRemaining > 0;
    }

    public static boolean isInAttackWindow() {
        return isCountering();
    }

    public static boolean isInDelayWindow() {
        return inDelayWindow;
    }

    public static Entity getLockedTarget() {
        NoXZMode mode = INSTANCE;
        if (mode == null || AntiKB.INSTANCE == null || !AntiKB.INSTANCE.isEnabled()) {
            return null;
        }
        if (!inDelayWindow && counterRemaining <= 0) {
            return null;
        }
        return mode.target != null && mode.target.isAlive() ? mode.target : null;
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
    public void onPreMotion(PreMotionEvent preMotionEvent) {
    }

    @Override
    public void onSprint(SprintEvent sprintEvent) {
    }

    @Override
    public void onAttack(EntityRemoveEvent entityRemoveEvent) {
    }

    @Override
    public void onEntityHurt(EntityHurtEvent event) {
        if (mc.player == null || event.entity() != mc.player) {
            return;
        }
        Entity attacker = event.damageSource().getDirectEntity();
        if (attacker == null) {
            attacker = event.damageSource().getEntity();
        }
        if (attacker != null && attacker != mc.player && attacker.isAlive()) {
            // 记下受击来源：onTick 每拍会把 target 改成 KillAura.target，可能为 null，
            // 这条记录是"被打了却没索敌目标"时唯一还能进 delay window 的依据。
            this.lastAttacker = attacker;
            this.lastAttackerTick = mc.player.tickCount;
            if (this.target == null) {
                this.target = attacker;
            }
        }
    }

    @Override
    public void onGameTick(GameTickEvent gameTickEvent) {
        if (mc.player == null) {
            return;
        }
        if (!AntiKB.INSTANCE.jumpReset.getValue() || this.jumpTicks <= 0) {
            this.restoreJumpKey();
            return;
        }
        if (Scaffold.INSTANCE != null && Scaffold.INSTANCE.isEnabled()) {
            return;
        }
        if (mc.player.isOnFire()) {
            this.jumpTicks = 0;
            this.restoreJumpKey();
            return;
        }
        // Same rule as JumpResetMode: only press the key on a tick where vanilla would accept it,
        // otherwise the pending jump is spent while airborne and nothing happens.
        if (!canJumpNow(mc.player)) {
            this.jumpTicks = 0;
            this.restoreJumpKey();
            return;
        }
        this.jumpKeyForced = true;
        mc.options.keyJump.setDown(true);
        --this.jumpTicks;
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

        if (packet instanceof ClientboundSetEntityMotionPacket motion
                && motion.getId() == mc.player.getId()) {
            if (!inDelayWindow) {
                if (this.shouldNotEngage()) {
                    return;
                }
                double horizontal = Math.hypot(motion.getXa(), motion.getZa()) / 8000.0;
                if (this.coolDownTicks > 0 || horizontal < 0.13) {
                    return;
                }
                inDelayWindow = true;
                this.delayTicks = 0;
                handlingVelocity = true;
            } else if (counterRemaining > 0) {
                counterRemaining = 0;
            }
            this.kbPacket = packet;
            receivePacketEvent.setCancelled(true);
            this.packetQueue.add(packet);
            return;
        }

        if (this.shouldNotEngage()) {
            return;
        }
        if (this.isAllowedPacket(packet)) {
            return;
        }
        if (!inDelayWindow) {
            return;
        }

        if (packet instanceof ClientboundMoveEntityPacket move) {
            Entity entity = move.getEntity(mc.level);
            if (entity != null) {
                Vec3 base = this.entityPositions.getOrDefault(entity,
                        new Vec3(entity.getX(), entity.getY(), entity.getZ()));
                if (move.hasPosition()) {
                    this.entityPositions.put(entity, base.add(
                            move.getXa() / 4096.0,
                            move.getYa() / 4096.0,
                            move.getZa() / 4096.0
                    ));
                }
            }
        }
        if (packet instanceof ClientboundTeleportEntityPacket teleport) {
            Entity entity = mc.level.getEntity(teleport.getId());
            if (entity != null) {
                this.entityPositions.put(entity,
                        new Vec3(teleport.getX(), teleport.getY(), teleport.getZ()));
            }
        }

        receivePacketEvent.setCancelled(true);
        this.packetQueue.add(packet);
    }

    @Override
    public void onTick(TickEvent tickEvent) {
        if (mc.player == null || mc.level == null) {
            return;
        }

        this.target = AntiKB.INSTANCE.requireKillAura.getValue()
                ? KillAura.target
                : this.crosshairTarget();
        if (this.target == null) {
            this.target = this.nearestPlayer(4.0);
        }
        if (this.target == null && AntiKB.INSTANCE.requireKillAura.getValue()
                && this.lastAttacker != null && this.lastAttacker.isAlive()
                && this.lastAttacker.distanceToSqr(mc.player) <= 64.0
                && mc.player.tickCount - this.lastAttackerTick <= 40) {
            this.target = this.lastAttacker;
        }

        if (this.compensateUntilMs != -1L
                && (System.currentTimeMillis() >= this.compensateUntilMs || mc.options.keyUp.isDown())) {
            compensating = false;
            this.compensateUntilMs = -1L;
        }

        if (this.coolDownTicks > 0) {
            --this.coolDownTicks;
        }

        if (this.shouldNotEngage() || this.noAimTicks >= 3) {
            this.resetAll();
            return;
        }

        if (inDelayWindow && counterRemaining <= 0) {
            compensating = true;
            if (this.delayTicks >= AntiKB.INSTANCE.maxDelayTicks.getValue().intValue()) {
                this.resetAll();
                return;
            }
            if (this.ready() && this.isWithinReleaseDistance() && this.isAimingAtTarget()) {
                this.releaseUntilKb();
                counterRemaining = Math.max(1, AntiKB.INSTANCE.attackAmount.getValue().intValue());
                this.noAimTicks = 0;
                this.noSprintTicks = 0;
                if (AntiKB.INSTANCE.jumpReset.getValue()) {
                    this.jumpTicks = 1;
                }
            } else {
                ++this.delayTicks;
            }
        }

        if (counterRemaining > 0) {
            if (!this.isAimingAtTarget()) {
                ++this.noAimTicks;
                this.debug("Failed (RayCast)");
                return;
            }
            this.noAimTicks = 0;
            if (!mc.player.isSprinting()) {
                ++this.noSprintTicks;
                this.debug("Failed (Sprint)");
                if (this.noSprintTicks >= 3) {
                    this.resetAll();
                }
                return;
            }
            this.noSprintTicks = 0;
            this.attackReduce(this.target);
            if (--counterRemaining <= 0) {
                this.flushQueue();
                inDelayWindow = false;
                handlingVelocity = false;
                this.kbPacket = null;
                this.delayTicks = 0;
            }
        } else if (compensating) {
            this.compensateUntilMs = System.currentTimeMillis() + 10L;
        }
    }

    @Override
    public void onStrafe(StrafeEvent strafeEvent) {
        if (mc.player == null) {
            return;
        }
        if (compensating
                && (!inDelayWindow || KillAura.shouldKeepSprintInDelayWindow())
                && AntiKB.INSTANCE.autoForwards.getValue()) {
            strafeEvent.setForward(1.0f);
        }
    }

    @Override
    public void onItemUseRayTrace(UseItemRayTraceEvent event) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (!mc.player.getMainHandItem().is(Items.FIRE_CHARGE)
                && !mc.player.getOffhandItem().is(Items.FIRE_CHARGE)) {
            return;
        }
        long tick = mc.level.getGameTime();
        if (tick == this.lastFireballTick) {
            return;
        }
        this.lastFireballTick = tick;
        this.coolDownTicks = 10;
    }

    @Override
    public void onDisconnect(DisconnectEvent disconnectEvent) {
        this.resetAll();
    }

    @Override
    public void onRender2D(Render2DEvent event) {
        if (!AntiKB.INSTANCE.renderBar.getValue() || !AntiKB.INSTANCE.isEnabled()) {
            return;
        }
        float maximum = Math.max(1, AntiKB.INSTANCE.maxDelayTicks.getValue().intValue());
        float target = inDelayWindow ? Math.min(1.0f, this.delayTicks / maximum) : 0.0f;
        float targetAlpha = inDelayWindow || this.barProgress > 0.01f ? 1.0f : 0.0f;
        long now = System.nanoTime();
        float dt = this.lastFrameNanos == 0L
                ? 0.016666668f
                : Math.min((now - this.lastFrameNanos) / 1.0E9f, 0.1f);
        this.lastFrameNanos = now;
        if (target >= 1.0f) {
            this.barProgress = 1.0f;
        } else {
            float speed = target > this.barProgress ? 9.75f : 21.4f;
            this.barProgress += (target - this.barProgress) * (1.0f - (float) Math.exp(-speed * dt));
        }
        this.barAlpha += (targetAlpha - this.barAlpha) * (1.0f - (float) Math.exp(-9.75f * dt));
        if (Math.abs(target - this.barProgress) < 0.001f) {
            this.barProgress = target;
        }
        if (this.barAlpha <= 0.01f || this.barProgress <= 0.001f) {
            return;
        }
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();

        float barWidth = 100.0f;
        float barHeight = 2.0f;
        float barX = width / 2.0f - barWidth / 2.0f;
        float barY = height / 2.0f + height * 0.10f;

        RenderUtil.drawFilledRect(event.poseStack(), barX, barY, barWidth, barHeight,
                new Color(30, 30, 36, (int) (180 * this.barAlpha)).getRGB());
        RenderUtil.drawFilledRect(event.poseStack(), barX, barY, barWidth * this.barProgress, barHeight,
                new Color(0, 180, 255, (int) (230 * this.barAlpha)).getRGB());
    }

    @Override
    public void onRender(RenderEvent renderEvent) {
        if (!AntiKB.INSTANCE.targetEsp.getValue() || this.target == null || !inDelayWindow) {
            return;
        }
        if (mc.gameRenderer == null) {
            return;
        }
        Vec3 position = this.entityPositions.get(this.target);
        if (position == null) {
            return;
        }
        double halfWidth = this.target.getBbWidth() / 2.0;
        double height = this.target.getBbHeight();
        PoseStack poseStack = renderEvent.poseStack();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        poseStack.pushPose();
        poseStack.translate(position.x - camera.x, position.y - camera.y, position.z - camera.z);
        AABB box = new AABB(-halfWidth, 0.0, -halfWidth, halfWidth, height, halfWidth);
        Color fill = new Color(255, 255, 255, 64);
        RenderUtil.drawFilledColoredBox(box, poseStack, fill, fill);
        Color outline = new Color(255, 255, 255, 204);
        RenderUtil.drawColoredBox(box, poseStack, outline, outline);
        poseStack.popPose();
    }

    private void attackReduce(Entity entity) {
        if (entity == null || mc.player == null || mc.getConnection() == null) {
            return;
        }
        if (!KillAura.attackAndLock()) {
            return;
        }
        if (NiloreClient.isReady()) {
            NiloreClient.getInstance().getEventBus().call(new EntityRemoveEvent(false, entity));
        }
        mc.getConnection().send(ServerboundInteractPacket.createAttackPacket(entity, mc.player.isShiftKeyDown()));
        mc.player.swing(InteractionHand.MAIN_HAND);
        Vec3 movement = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(movement.x * 0.6, movement.y, movement.z * 0.6);
    }

    private Entity crosshairTarget() {
        return mc.hitResult instanceof EntityHitResult hit ? hit.getEntity() : null;
    }

    private Entity nearestPlayer(double range) {
        if (mc.player == null || mc.level == null) {
            return null;
        }
        Entity best = null;
        double bestDistance = range * range;
        for (Player player : mc.level.players()) {
            if (player == mc.player || !player.isAlive() || player.isSpectator()) {
                continue;
            }
            double distance = player.distanceToSqr(mc.player);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    private boolean isAimingAtTarget() {
        if (this.target == null || mc.player == null) {
            return false;
        }
        if (!AntiKB.INSTANCE.requireKillAura.getValue()) {
            return mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == this.target;
        }
        double range = Math.max(3.0, AntiKB.INSTANCE.releaseDistance.getValue().doubleValue() + 1.0);
        return RotationUtil.isLookingAt(this.target, range, 0.3f);
    }

    private boolean isWithinReleaseDistance() {
        if (this.target == null || mc.player == null) {
            return false;
        }
        Vec3 eye = mc.player.getEyePosition();
        double limit = AntiKB.INSTANCE.releaseDistance.getValue().doubleValue();
        return RotationUtil.closestPoint(eye, this.target.getBoundingBox()).distanceTo(eye) <= limit;
    }

    private boolean shouldNotEngage() {
        if (mc.player == null || mc.getConnection() == null) {
            return true;
        }
        if (mc.player.isInLava() || mc.player.isInWater()) {
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

    private boolean isAllowedPacket(Packet<?> packet) {
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

    private boolean ready() {
        return mc.player != null
                && !mc.player.isUsingItem()
                && mc.player.isSprinting();
    }

    private void releaseUntilKb() {
        Packet<ClientGamePacketListener> marker = this.kbPacket;
        this.kbPacket = null;
        if (marker == null || mc.getConnection() == null) {
            this.flushQueue();
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
            if (packet == marker) {
                break;
            }
        }
    }

    private void flushQueue() {
        mc.execute(() -> {
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
        });
    }

    private void restoreJumpKey() {
        if (!this.jumpKeyForced) {
            return;
        }
        this.jumpKeyForced = false;
        if (mc.player == null || Scaffold.INSTANCE != null && Scaffold.INSTANCE.isEnabled()) {
            return;
        }
        boolean down = InputConstants.isKeyDown(mc.getWindow().getWindow(), mc.options.keyJump.getKey().getValue());
        mc.options.keyJump.setDown(down);
    }

    private void resetAll() {
        this.flushQueue();
        inDelayWindow = false;
        compensating = false;
        this.positionCorrection = false;
        this.kbPacket = null;
        this.delayTicks = 0;
        counterRemaining = 0;
        this.noAimTicks = 0;
        this.noSprintTicks = 0;
        this.compensateUntilMs = -1L;
        this.jumpTicks = 0;
        this.restoreJumpKey();
        this.entityPositions.clear();
        this.target = null;
        handlingVelocity = false;
    }

    private void debug(String message) {
        if (AntiKB.INSTANCE.debugLog.getValue()) {
            ChatUtil.print(message);
        }
    }

    static {
        handlingVelocity = false;
        compensating = false;
        inDelayWindow = false;
        counterRemaining = 0;
    }
}
