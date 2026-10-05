package client.nilore.modules.impl.combat.antikb;

import java.util.concurrent.LinkedBlockingDeque;

import java.awt.Color;

import com.mojang.blaze3d.platform.InputConstants;

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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
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
import client.nilore.modules.impl.movement.Scaffold;
import client.nilore.modules.impl.player.Stuck;
import client.nilore.utils.game.RotationUtil;
import client.nilore.utils.misc.ChatUtil;
import client.nilore.utils.render.RenderUtil;

/**
 * Port of EdNaven Velocity's Reduce + Delay execution path:
 * choke own knockback packet, release on aim+sprint (Delay = airborne allowed),
 * then attack-reduce with the x0.6 self-deceleration the vanilla client never applies.
 */
public class NoXZMode
        extends AntiKBMode {
    public static NoXZMode INSTANCE;
    public static boolean handlingVelocity;

    private final LinkedBlockingDeque<Packet<ClientGamePacketListener>> packetQueue = new LinkedBlockingDeque<>();

    public boolean delaying;
    private boolean positionCorrection;
    private boolean compensating;
    private long compensateUntilMs = -1L;
    private int delayTicks;
    private int counterRemaining;
    private int noAimTicks;
    private int jumpTicks;
    private boolean jumpKeyForced;
    private float barProgress;
    private float barAlpha;
    private long lastFrameNanos;
    private Entity target;

    @Override
    public boolean isActive() {
        return this.delaying || this.counterRemaining > 0;
    }

    public static boolean isCountering() {
        return INSTANCE != null && INSTANCE.counterRemaining > 0;
    }

    public static boolean isInDelayWindow() {
        return INSTANCE != null && INSTANCE.delaying;
    }

    public static Entity getLockedTarget() {
        NoXZMode m = INSTANCE;
        if (m == null || AntiKB.INSTANCE == null || !AntiKB.INSTANCE.isEnabled()) {
            return null;
        }
        if (!m.delaying && m.counterRemaining <= 0) {
            return null;
        }
        return m.target != null && m.target.isAlive() ? m.target : null;
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
        if (!motionEvent.isPost()) {
            return;
        }
        // Second release check, at the end of the tick - see tryOpenWindow(). This is the only phase
        // that can see this tick's landing.
        this.tryOpenWindow();
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
        if (attacker != null && attacker != mc.player && attacker.isAlive()
                && this.target == null) {
            this.target = attacker;
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
        this.jumpKeyForced = true;
        mc.options.keyJump.setDown(true);
        --this.jumpTicks;
    }

    @Override
    public void onRender2D(Render2DEvent event) {
        if (this.compensateUntilMs != -1L
                && (System.currentTimeMillis() >= this.compensateUntilMs || mc.options.keyUp.isDown())) {
            this.compensating = false;
            this.compensateUntilMs = -1L;
        }
        if (!AntiKB.INSTANCE.renderBar.getValue() || !AntiKB.INSTANCE.isEnabled()) {
            return;
        }
        float maximum = Math.max(1, AntiKB.INSTANCE.maxDelayTicks.getValue().intValue());
        float target = this.delaying ? Math.min(1.0f, this.delayTicks / maximum) : 0.0f;
        float targetAlpha = this.delaying || this.barProgress > 0.01f ? 1.0f : 0.0f;
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
    public void onReceivePacket(ReceivePacketEvent receivePacketEvent) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        Packet<ClientGamePacketListener> packet = receivePacketEvent.getPacket();

        if (packet instanceof ClientboundPlayerPositionPacket) {
            this.positionCorrection = true;
            return;
        }

        if (this.shouldNotEngage()) {
            return;
        }

        if (packet instanceof ClientboundSetEntityMotionPacket motion
                && motion.getId() == mc.player.getId()) {
            if (!this.delaying) {
                this.delaying = true;
                this.delayTicks = 0;
                handlingVelocity = true;
            }
            receivePacketEvent.setCancelled(true);
            this.packetQueue.add(packet);
            return;
        }

        if (this.isAllowedPacket(packet)) {
            return;
        }
        if (!this.delaying) {
            return;
        }
        // Entity position sync must never be choked: holding the opponent's move packets
        // back makes our client see an outdated hitbox while the server judges against the
        // current one -> Grim Hitboxes / Reach. The reference keeps a tracked copy of those
        // positions for its Target ESP only; its aim and gates use the entity's own box, so
        // the aura has to keep seeing the live one.
        if (packet instanceof ClientboundMoveEntityPacket
                || packet instanceof ClientboundTeleportEntityPacket) {
            return;
        }
        receivePacketEvent.setCancelled(true);
        this.packetQueue.add(packet);
    }

    @Override
    public void onDisconnect(DisconnectEvent disconnectEvent) {
        this.resetAll();
    }

    @Override
    public void onStrafe(StrafeEvent strafeEvent) {
        if (mc.player == null) {
            return;
        }
        // Reference Reduce branch: Auto Forwards only forces the input forward while the
        // knockback window is live - strafe is left alone, zeroing it belongs to the
        // Jump Reset / Both path which we do not have.
        if (this.compensating && AntiKB.INSTANCE.autoForwards.getValue()
                && (!this.delaying || KillAura.shouldKeepSprintInDelayWindow())) {
            strafeEvent.setForward(1.0f);
        }
    }

    @Override
    public void onTick(TickEvent tickEvent) {
        if (mc.player == null || mc.level == null) {
            return;
        }

        this.target = KillAura.target;

        if (this.compensateUntilMs != -1L
                && (System.currentTimeMillis() >= this.compensateUntilMs || mc.options.keyUp.isDown())) {
            this.compensating = false;
            this.compensateUntilMs = -1L;
        }

        if (mc.player.isDeadOrDying() || this.shouldNotEngage() || this.noAimTicks >= 3) {
            this.resetAll();
            return;
        }

        if (this.delaying) {
            this.compensating = true;
            this.counterRemaining = 0;
            ++this.delayTicks;
            if (this.delayTicks >= AntiKB.INSTANCE.maxDelayTicks.getValue().intValue()) {
                this.resetAll();
                return;
            }
            // Delay semantics (what our config is): the window only opens on the ground, aiming
            // at the target and sprinting - same as the reference's Reduce + Delay path.
            this.tryOpenWindow();
        }

        if (this.counterRemaining > 0) {
            if (!this.isAimingAtTarget()) {
                ++this.noAimTicks;
                return;
            }
            // Sprinting is required, as in the reference. It has to be actually present, which is
            // what the Sprint module's forced sprint (including the eat-and-move case) is for -
            // when the player really is not sprinting the counter waits, exactly like the res one.
            if (!mc.player.isSprinting()) {
                this.debug("not sprinting");
                return;
            }
            // The reference leans on vanilla's crosshair for this, which caps the attack at
            // 3.0 against the un-inflated box. isAimingAtTarget() goes through a raycast, so
            // without this gate the counter reaches past 3.0 and Grim Reach fires.
            if (!this.canReachTarget()) {
                this.debug("raycast fail");
                // Count every stalled tick towards the same >= 3 give-up as a missed aim, so no
                // branch can keep counterRemaining > 0 and hold KillAura's attack window hostage.
                ++this.noAimTicks;
                return;
            }
            this.attackReduce(this.target);
            --this.counterRemaining;
        } else if (this.compensating) {
            this.compensateUntilMs = System.currentTimeMillis() + 10L;
        }
    }

    /**
     * Opens the window once the release condition holds. Called twice per tick - from {@link #onTick}
     * and again from the post {@link #onMotion} - because of where the two phases sit:
     * {@code onTick} runs from {@code TickEvent}, which is injected at the head of
     * {@code Minecraft.tick} and therefore reads the previous tick's {@code onGround}. With fast
     * bunny-hopping a landing lasts only a tick or two, so that stale read skips whole landings.
     * {@code MotionEvent.isPost()} fires from {@code sendPosition}, after {@code aiStep}'s move, so
     * it sees the landing that happened in this tick. {@code delayTicks} is only advanced in
     * {@code onTick}, so the window length is unchanged.
     */
    private void tryOpenWindow() {
        if (mc.player == null || mc.level == null || !this.delaying) {
            return;
        }
        // Delay semantics (what our config is): the window only opens on the ground, aiming at the
        // target and sprinting - same as the reference's Reduce + Delay path.
        if (!mc.player.onGround() || !this.isAimingAtTarget() || !mc.player.isSprinting()) {
            return;
        }
        this.counterRemaining = this.getAttackCount();
        this.noAimTicks = 0;
        this.delaying = false;
        handlingVelocity = false;
        this.flushQueue();
        if (AntiKB.INSTANCE.jumpReset.getValue() && mc.player.onGround()) {
            this.jumpTicks = 1;
        }
    }

    private void attackReduce(Entity entity) {
        if (entity == null || mc.player == null || mc.getConnection() == null) {
            return;
        }
        // Same hand-rolled send the aura uses (KillAura.attackEntity). gameMode.attack would first
        // push an ensureHasSentCarriedItem slot packet ahead of the interact, and it sidesteps the
        // aura's per-tick lock, so the counter and the aura could each land a hit in the same tick.
        if (!KillAura.attackAndLock()) {
            return;
        }
        boolean wasSprinting = mc.player.isSprinting();
        if (wasSprinting) {
            mc.player.setSprinting(false);
        }
        mc.getConnection().send(ServerboundInteractPacket.createAttackPacket(entity, mc.player.isShiftKeyDown()));
        mc.player.attack(entity);
        mc.player.resetAttackStrengthTicker();
        mc.player.swing(InteractionHand.MAIN_HAND);
        // Vanilla never applies the post-attack self-deceleration for us, so simulate it.
        if (wasSprinting) {
            Vec3 movement = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(movement.x * 0.6, movement.y, movement.z * 0.6);
            // Every counter tick re-checks isSprinting(), and vanilla only re-derives that from
            // the movement input in aiStep (forwardImpulse >= 0.8), which the silent rotation's
            // input remap can starve. Put the flag back here so the remaining attacks do not
            // stall on it - a stall leaves the knockback unreduced and the queued velocity
            // gets dumped mid-air when the delay window times out.
            mc.player.setSprinting(true);
        }
    }

    private int getAttackCount() {
        return AntiKB.INSTANCE.attackAmount.getValue().intValue();
    }

    private boolean isAimingAtTarget() {
        if (this.target == null || mc.player == null) {
            return false;
        }
        // Silent-rotation client: mc.hitResult reflects the local view which the aura
        // never moves, so aim must be judged against the rotation actually sent.
        return RotationUtil.isLookingAt(this.target, 3.0, 0.1f);
    }

    private boolean canReachTarget() {
        if (this.target == null) {
            return false;
        }
        // Hard cap at vanilla's 3.0; isWithinReach carries the margin and both checks judge the
        // server-side box while we are the one holding its packets.
        double cap = Math.min(RotationUtil.getReachLimit(), 3.0);
        return RotationUtil.isWithinReach(this.target)
                && RotationUtil.isLookingAt(this.target, cap, 0.1f);
    }

    private boolean shouldNotEngage() {
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

    private void flushQueue() {
        if (mc.getConnection() == null) {
            this.packetQueue.clear();
            return;
        }
        // MnExecute-style replay: the queued packets (velocity included) must be applied
        // outside the current tick's movement pass, otherwise the replayed knockback
        // fights the already-computed movement for this tick and the server prediction
        // drifts by a few hundredths.
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
        this.delaying = false;
        this.compensating = false;
        this.positionCorrection = false;
        this.delayTicks = 0;
        this.counterRemaining = 0;
        this.noAimTicks = 0;
        this.compensateUntilMs = -1L;
        this.jumpTicks = 0;
        this.restoreJumpKey();
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
    }
}
