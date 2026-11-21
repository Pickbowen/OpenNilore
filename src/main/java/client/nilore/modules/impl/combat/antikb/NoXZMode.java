package client.nilore.modules.impl.combat.antikb;

import java.awt.Color;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
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
import client.nilore.event.impl.RotationEvent;
import client.nilore.event.impl.SprintEvent;
import client.nilore.event.impl.StrafeEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.modules.impl.combat.AntiKB;
import client.nilore.modules.impl.combat.KillAura;
import client.nilore.modules.impl.movement.NoSlow;
import client.nilore.utils.game.RotationUtil;
import client.nilore.utils.misc.ChatUtil;
import client.nilore.utils.render.RenderUtil;
import client.nilore.utils.rotation.Rotation;
import client.nilore.utils.rotation.RotationHandler;

public class NoXZMode extends AntiKBMode {
    public static NoXZMode INSTANCE;
    public static volatile boolean handlingVelocity;
    public static volatile boolean compensating;
    public static volatile boolean inDelayWindow;
    public static volatile int counterRemaining;

    private static final int MAX_DELAY_TICKS = 40;
    private final ConcurrentLinkedQueue<Packet<?>> packetQueue = new ConcurrentLinkedQueue<>();
    private ClientPacketListener connection;
    private Object level;
    private boolean delaying;
    private boolean sprintKeyForced;
    private int delayTicks;
    private int coolDownTicks;
    private int aimSkipTicks;
    private Entity target;
    private float barProgress;
    private float barAlpha;
    private long lastFrameNanos;

    public NoXZMode() {
        super("NoXZ");
        INSTANCE = this;
    }

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
        if (mode == null || AntiKB.INSTANCE == null || !AntiKB.INSTANCE.isEnabled() || !mode.isActive()) {
            return null;
        }
        return mode.currentTarget();
    }

    private Entity currentTarget() {
        if (mc.player == null || mc.level == null || KillAura.INSTANCE == null) {
            return null;
        }
        if (AntiKB.INSTANCE.requireAura.getValue()) {
            Entity auraTarget = KillAura.target;
            return KillAura.INSTANCE.isEnabled() && auraTarget != null && auraTarget.isAlive()
                    && !auraTarget.isRemoved() ? auraTarget : null;
        }
        HitResult hit = RotationUtil.rayTraceForAim(
                new Rotation(mc.player.getYRot(), mc.player.getXRot()), 3.0);
        return hit instanceof EntityHitResult entityHit
                && !entityHit.getEntity().isRemoved()
                && KillAura.INSTANCE.isValidTarget(entityHit.getEntity()) ? entityHit.getEntity() : null;
    }

    @Override
    public synchronized void onEnable() {
        this.resetAll(false);
        this.connection = mc.getConnection();
        this.level = mc.level;
        this.coolDownTicks = 0;
    }

    @Override
    public synchronized void onDisable() {
        this.resetAll(true);
        this.coolDownTicks = 0;
    }

    @Override
    public String getName() {
        return "";
    }

    @Override
    public synchronized void onReceivePacket(ReceivePacketEvent event) {
        if (!this.checkContext()) {
            return;
        }
        Packet<ClientGamePacketListener> packet = event.getPacket();
        if (packet instanceof ClientboundPlayerPositionPacket && inDelayWindow) {
            this.coolDownTicks = 2;
        }
        if (packet instanceof ClientboundSetEntityMotionPacket motion
                && motion.getId() == mc.player.getId()
                && this.coolDownTicks <= 0 && this.shouldReduce(motion)) {
            inDelayWindow = true;
            handlingVelocity = true;
            compensating = true;
            this.delaying = true;
        }
        if (this.delaying && !this.isAllowedPacket(packet)) {
            event.setCancelled(true);
            this.packetQueue.add(packet);
        }
    }

    private boolean shouldReduce(ClientboundSetEntityMotionPacket packet) {
        return (AntiKB.INSTANCE.requireAura.getValue()
                ? KillAura.INSTANCE != null && KillAura.INSTANCE.isEnabled() : this.currentTarget() != null)
                && !NoSlow.isHandling()
                && Math.hypot(packet.getXa(), packet.getZa()) / 8000.0
                        >= AntiKB.INSTANCE.targetMotion.getValue().doubleValue();
    }

    @Override
    public synchronized void onTick(TickEvent event) {
        if (!this.checkContext()) {
            return;
        }
        if (this.coolDownTicks > 0) {
            --this.coolDownTicks;
            this.resetAll(true);
            return;
        }
        if (this.isActive() && (AntiKB.INSTANCE.requireAura.getValue()
                && (KillAura.INSTANCE == null || !KillAura.INSTANCE.isEnabled()) || NoSlow.isHandling())) {
            this.resetAll(true);
            return;
        }
        if (this.delaying) {
            ++this.delayTicks;
        }
        if (inDelayWindow && (this.delayTicks >= MAX_DELAY_TICKS || this.currentTarget() == null)) {
            this.resetAll(true);
            return;
        }
    }

    @Override
    public synchronized void onPreMotion(PreMotionEvent event) {
        if (!this.checkContext() || this.coolDownTicks > 0) {
            return;
        }
        try {
            Entity releaseTarget = this.currentTarget();
            if (inDelayWindow && this.ready() && releaseTarget != null && mc.screen == null
                    && this.inReleaseDistance(releaseTarget)
                    && (!AntiKB.INSTANCE.requireAura.getValue()
                        || this.isAimingAt(releaseTarget, RotationUtil.getReachLimit()))) {
                this.releaseForCounter();
            }
            if (counterRemaining > 0) {
                this.counterAttack();
            }
        } catch (Exception exception) {
            this.debug("Failed (" + exception.getClass().getSimpleName() + ")");
            this.resetAll(true);
        }
    }

    private void releaseForCounter() {
        this.stopDelay();
        if (!inDelayWindow || this.connection != mc.getConnection() || this.level != mc.level) {
            this.resetAll(false);
            return;
        }
        inDelayWindow = false;
        handlingVelocity = false;
        this.aimSkipTicks = 0;
        counterRemaining = calcAttacks(mc.player.getDeltaMovement().horizontalDistance(),
                AntiKB.INSTANCE.targetMotion.getValue().doubleValue());
        compensating = counterRemaining > 0;
        if (counterRemaining == 0) {
            this.resetAll(true);
        }
    }

    static int calcAttacks(double strength, double threshold) {
        if (strength < threshold) {
            return 0;
        }
        for (int i = 0; i < 8; ++i) {
            strength *= 0.6;
            if (strength < threshold) {
                return i + 1;
            }
        }
        return 9;
    }

    private void counterAttack() {
        boolean requireAura = AntiKB.INSTANCE.requireAura.getValue();
        if (!requireAura) {
            this.target = mc.screen == null ? this.currentTarget() : null;
        } else if (this.target == null) {
            Entity candidate = this.currentTarget();
            this.target = candidate != null && mc.screen == null
                    && this.isAimingAt(candidate, RotationUtil.getReachLimit()) ? candidate : null;
        }
        double aimRange = Math.max(3.0, AntiKB.INSTANCE.releaseDistance.getValue().doubleValue() + 1.0);
        if (this.target == null || !this.target.isAlive()
                || requireAura && !this.isAimingAt(this.target, aimRange)) {
            if (++this.aimSkipTicks > 5) {
                this.aimSkipTicks = 0;
                --counterRemaining;
                this.finishCounterIfDone();
            }
            return;
        }
        this.aimSkipTicks = 0;
        if (!mc.player.isSprinting()) {
            --counterRemaining;
            this.target = null;
            this.finishCounterIfDone();
            return;
        }
        if (!KillAura.attackAndLock()) {
            return;
        }
        --counterRemaining;
        Entity attacked = this.target;
        if (mc.player == null || this.connection != mc.getConnection() || this.level != mc.level) {
            this.resetAll(false);
            return;
        }
        if (NiloreClient.isReady()) {
            NiloreClient.getInstance().getEventBus().call(new EntityRemoveEvent(false, attacked));
        }
        this.connection.send(ServerboundInteractPacket.createAttackPacket(attacked,
                mc.player.isShiftKeyDown()));
        mc.player.setDeltaMovement(mc.player.getDeltaMovement().multiply(0.6, 1.0, 0.6));
        mc.player.swing(InteractionHand.MAIN_HAND);
        if (KillAura.INSTANCE != null && KillAura.INSTANCE.isEnabled()) {
            KillAura.INSTANCE.onVelocityAttack();
        }
        this.target = null;
        this.finishCounterIfDone();
    }

    private void finishCounterIfDone() {
        if (counterRemaining <= 0 && !inDelayWindow) {
            this.resetAll(true);
        }
    }

    private boolean ready() {
        return !mc.player.isUsingItem() && mc.player.isSprinting()
                && (mc.player.onGround() || !AntiKB.INSTANCE.delayTillGround.getValue());
    }

    private boolean inReleaseDistance(Entity entity) {
        Vec3 eye = mc.player.getEyePosition();
        return RotationUtil.closestPoint(eye, entity.getBoundingBox()).distanceTo(eye)
                <= AntiKB.INSTANCE.releaseDistance.getValue().doubleValue();
    }

    private boolean isAimingAt(Entity entity, double range) {
        Rotation aim = RotationHandler.isRotating ? RotationHandler.targetRotation : RotationHandler.sentRotation;
        if (aim == null) {
            aim = new Rotation(mc.player.getYRot(), mc.player.getXRot());
        }
        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.add(RotationUtil.directionFromRotation(aim).scale(range));
        AABB box = entity.getBoundingBox();
        Vec3 hit = box.contains(eye) ? eye : box.clip(eye, end).orElse(null);
        if (hit == null) {
            return false;
        }
        HitResult block = mc.level.clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return block.getType() == HitResult.Type.MISS
                || eye.distanceToSqr(hit) <= eye.distanceToSqr(block.getLocation());
    }

    @Override
    public synchronized void onStrafe(StrafeEvent event) {
        if (mc.player == null || !this.isActive()) {
            return;
        }
        if (mc.screen == null) {
            event.setForward(1.0f);
            event.setStrafe(0.0f);
            this.forceSprintKey();
        }
        if (counterRemaining > 0) {
            event.setJumping(false);
        }
    }

    @Override
    public synchronized void onSprint(SprintEvent event) {
        if (mc.player != null && mc.screen == null && this.isActive()) {
            this.forceSprintKey();
        }
    }

    private void forceSprintKey() {
        this.sprintKeyForced = true;
        mc.options.keySprint.setDown(true);
    }

    private void restoreSprintKey() {
        if (this.sprintKeyForced) {
            this.sprintKeyForced = false;
            mc.options.keySprint.setDown(InputConstants.isKeyDown(mc.getWindow().getWindow(),
                    mc.options.keySprint.getKey().getValue()));
        }
    }

    private boolean checkContext() {
        ClientPacketListener current = mc.getConnection();
        if (mc.player == null || mc.level == null || current == null
                || mc.screen instanceof ConnectScreen || mc.screen instanceof ReceivingLevelScreen) {
            this.resetAll(false);
            return false;
        }
        if (this.connection != current || this.level != mc.level) {
            this.resetAll(false);
            this.connection = current;
            this.level = mc.level;
            this.coolDownTicks = 0;
        }
        return true;
    }

    private void releaseQueue() {
        Packet<?> packet;
        while ((packet = this.packetQueue.poll()) != null) {
            try {
                this.handleDelayed(packet);
            } catch (Exception exception) {
                this.debug("Packet failed (" + exception.getClass().getSimpleName() + ")");
            }
        }
        this.delayTicks = 0;
    }

    private void stopDelay() {
        this.delaying = false;
        this.releaseQueue();
        this.delayTicks = 0;
    }

    private synchronized void resetAll(boolean releaseQueued) {
        this.delaying = false;
        inDelayWindow = false;
        handlingVelocity = false;
        compensating = false;
        counterRemaining = 0;
        this.target = null;
        this.aimSkipTicks = 0;
        this.delayTicks = 0;
        this.restoreSprintKey();
        if (releaseQueued && this.connection != null && this.connection == mc.getConnection()
                && this.level == mc.level && mc.level != null) {
            // 网络回调触发清理时，把旧队列独立移交主线程，不能误取下一轮的新包。
            ConcurrentLinkedQueue<Packet<?>> pending = new ConcurrentLinkedQueue<>(this.packetQueue);
            this.packetQueue.clear();
            ClientPacketListener listener = this.connection;
            Object pendingLevel = this.level;
            mc.execute(() -> {
                if (listener != mc.getConnection() || pendingLevel != mc.level) {
                    return;
                }
                for (Packet<?> packet : pending) {
                    if (listener != mc.getConnection() || pendingLevel != mc.level) {
                        return;
                    }
                    try {
                        applyPacket(packet, listener);
                    } catch (Exception exception) {
                        this.debug("Packet failed (" + exception.getClass().getSimpleName() + ")");
                    }
                }
            });
        } else {
            this.packetQueue.clear();
        }
    }

    private void handleDelayed(Packet<?> packet) {
        if (this.connection == mc.getConnection() && this.level == mc.level) {
            applyPacket(packet, this.connection);
        }
    }

    @SuppressWarnings("unchecked")
    private static void applyPacket(Packet<?> packet, ClientPacketListener listener) {
        ((Packet<ClientGamePacketListener>) packet).handle(listener);
    }

    private boolean isAllowedPacket(Packet<?> packet) {
        return packet instanceof ClientboundSystemChatPacket
                || packet instanceof ClientboundSetDisplayObjectivePacket
                || packet instanceof ClientboundSetEquipmentPacket
                || packet instanceof ClientboundClearTitlesPacket
                || packet instanceof ClientboundSetTitleTextPacket
                || packet instanceof ClientboundBossEventPacket
                || packet instanceof ClientboundRemoveEntitiesPacket
                || packet instanceof ClientboundSetHealthPacket
                || packet instanceof ClientboundSetObjectivePacket
                || packet instanceof ClientboundSetScorePacket
                || packet instanceof ClientboundSetSubtitleTextPacket
                || packet instanceof ClientboundSetTitlesAnimationPacket
                || packet instanceof ClientboundSoundPacket
                || packet instanceof ClientboundSoundEntityPacket
                || packet instanceof ClientboundRotateHeadPacket
                || packet instanceof ClientboundMoveEntityPacket
                || packet instanceof ClientboundTeleportEntityPacket
                || packet instanceof ClientboundSetEntityDataPacket data && data.id() != mc.player.getId()
                || packet instanceof ClientboundKeepAlivePacket
                || packet instanceof ClientboundDamageEventPacket
                || packet instanceof ClientboundSetEntityMotionPacket motion && motion.getId() != mc.player.getId()
                || packet instanceof ClientboundPlayerPositionPacket
                || packet instanceof ClientboundDisconnectPacket
                || packet instanceof ClientboundPlayerChatPacket
                || packet instanceof ClientboundPlayerCombatKillPacket
                || packet instanceof ClientboundSetPlayerTeamPacket
                || packet instanceof ClientboundContainerClosePacket
                || packet instanceof ClientboundHurtAnimationPacket;
    }

    @Override
    public synchronized void onDisconnect(DisconnectEvent event) {
        this.resetAll(false);
        this.connection = null;
        this.level = null;
        this.coolDownTicks = 0;
    }

    @Override
    public synchronized void onRender2D(Render2DEvent event) {
        if (!AntiKB.INSTANCE.renderBar.getValue() || !AntiKB.INSTANCE.isEnabled()) {
            return;
        }
        float maximum = MAX_DELAY_TICKS;
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

    private void debug(String message) {
        if (AntiKB.INSTANCE.debugLog.getValue()) {
            ChatUtil.print(message);
        }
    }

    @Override
    public void onRotation(RotationEvent event) {
    }

    @Override
    public void onMotion(MotionEvent event) {
    }

    @Override
    public void onGameTick(GameTickEvent event) {
    }

    @Override
    public void onAttack(EntityRemoveEvent event) {
    }

    @Override
    public void onEntityHurt(EntityHurtEvent event) {
    }
}
