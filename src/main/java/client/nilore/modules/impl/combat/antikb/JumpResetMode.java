package client.nilore.modules.impl.combat.antikb;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import client.nilore.event.impl.DisconnectEvent;
import client.nilore.event.impl.EntityRemoveEvent;
import client.nilore.event.impl.GameTickEvent;
import client.nilore.event.impl.MotionEvent;
import client.nilore.event.impl.PreMotionEvent;
import client.nilore.event.impl.ReceivePacketEvent;
import client.nilore.event.impl.RotationEvent;
import client.nilore.event.impl.SprintEvent;
import client.nilore.event.impl.StrafeEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.modules.impl.combat.AntiKB;
import client.nilore.modules.impl.combat.Backtrack;
import client.nilore.modules.impl.combat.KillAura;
import client.nilore.modules.impl.movement.Scaffold;
import client.nilore.modules.impl.player.NoFall;
import client.nilore.utils.rotation.Rotation;
import client.nilore.utils.rotation.RotationHandler;

public class JumpResetMode extends AntiKBMode {
    public static volatile boolean isJumping = false;

    /** An opponent's reach is 3.0; eye to eye adds a little, so 3.2 is the honest ceiling. */
    private static final double PREDICT_MAX_DISTANCE = 3.2;
    /** cos(30 degrees): the opponent's horizontal facing must point at us within this cone. */
    private static final double PREDICT_MIN_FACING_DOT = 0.866;
    /** How long after a knockback packet the late fallback may still fire. */
    private static final int HURT_FALLBACK_TICKS = 2;

    private ClientboundSetEntityMotionPacket knockbackPacket;
    private int rotationHeldTicks = 0;
    private int jumpTicks = 0;
    private int lastKnockbackTick = -100;

    public JumpResetMode() {
        super("Jump Reset");
    }

    @Override
    public String getName() {
        return "Jump Reset";
    }

    @Override
    public void onEnable() {
        this.knockbackPacket = null;
        AntiKB.rotation = null;
        this.rotationHeldTicks = 0;
        this.lastKnockbackTick = -100;
        this.resetState();
        isJumping = false;
    }

    @Override
    public void onDisable() {
        this.onEnable();
    }

    private void resetState() {
        isJumping = false;
        this.jumpTicks = 0;
    }

    private boolean isNoFallEnabled() {
        return NoFall.INSTANCE != null && NoFall.INSTANCE.isEnabled()
                && (NoFall.INSTANCE.jumpLandingBoost || NoFall.INSTANCE.boostActive);
    }

    private boolean isBacktracking() {
        return Backtrack.INSTANCE != null && Backtrack.INSTANCE.isEnabled()
                && (Backtrack.INSTANCE.isActive() || Backtrack.INSTANCE.isBacktracking());
    }

    private boolean isSuspended() {
        return this.isNoFallEnabled() || this.isBacktracking();
    }

    private void cancelJumpReset() {
        AntiKB.rotation = null;
        this.rotationHeldTicks = 0;
        this.resetState();
    }

    @Override
    public void onRotation(RotationEvent event) {
    }

    @Override
    public void onMotion(MotionEvent event) {
    }

    @Override
    public void onAttack(EntityRemoveEvent event) {
    }

    @Override
    public void onEntityHurt(client.nilore.event.impl.EntityHurtEvent event) {
    }

    @Override
    public void onSprint(SprintEvent event) {
    }

    @Override
    public void onPreMotion(PreMotionEvent event) {
    }

    @Override
    public void onStrafe(StrafeEvent event) {
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (this.isSuspended()) {
            this.cancelJumpReset();
            return;
        }
        if (AntiKB.mode.is("Jump Reset")
                && AntiKB.INSTANCE.followDirection.getValue()
                && AntiKB.rotation != null) {
            event.setForward(1.0f);
            event.setStrafe(0.0f);
        }
    }

    @Override
    public void onReceivePacket(ReceivePacketEvent event) {
        LocalPlayer player = mc.player;
        if (player == null || !AntiKB.mode.is("Jump Reset")) return;
        if (this.isSuspended()) {
            this.cancelJumpReset();
            return;
        }
        if (!(event.getPacket() instanceof ClientboundSetEntityMotionPacket motion)) return;
        if (motion.getId() != player.getId()) return;
        this.knockbackPacket = motion;
        this.lastKnockbackTick = player.tickCount;

        boolean wantRotate = (AntiKB.INSTANCE.rotate.getValue() || AntiKB.INSTANCE.followDirection.getValue())
                && KillAura.target == null;
        float xMotion = (float) (motion.getXa() / 8000.0);
        float zMotion = (float) (motion.getZa() / 8000.0);
        if (wantRotate && (Math.abs(xMotion) > 1.0E-6 || Math.abs(zMotion) > 1.0E-6)) {
            float yaw = (float) Math.toDegrees(Math.atan2(xMotion, -zMotion));
            Rotation kbRotation = new Rotation(yaw, player.getXRot());
            AntiKB.rotation = kbRotation;
            this.rotationHeldTicks = 0;
            try {
                RotationHandler.setTargetRotation(kbRotation);
                RotationHandler.isRotating = true;
            } catch (Throwable ignored) {
            }
        }

        isJumping = true;
        if (canJumpNow(player)) {
            this.jumpTicks = 1;
        }
        logger.info("[AntiKB] JumpReset kb=({},{},{}) onGround={} rotating={}",
                motion.getXa(), motion.getYa(), motion.getZa(), player.onGround(), AntiKB.rotation != null);
    }

    @Override
    public void onDisconnect(DisconnectEvent event) {
        AntiKB.rotation = null;
        this.knockbackPacket = null;
        this.rotationHeldTicks = 0;
        this.lastKnockbackTick = -100;
        this.resetState();
    }

    @Override
    public void onGameTick(GameTickEvent event) {
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (!AntiKB.mode.is("Jump Reset")) return;
        if (this.isSuspended()) {
            this.cancelJumpReset();
            return;
        }
        if (this.shouldPredictJump(player)) {
            isJumping = true;
            this.jumpTicks = 1;
            logger.info("[AntiKB] JumpReset predict jump tick={}", player.tickCount);
        }
        if (this.jumpTicks > 0 && !Scaffold.INSTANCE.isEnabled()) {
            if (player.isOnFire()) {
                this.resetState();
                return;
            }
            // The packet handler runs off the tick thread, so "on the ground when the knockback
            // arrived" is not the same as "on the ground when the key goes down". Re-check the
            // vanilla jump preconditions here, on the tick that actually presses it - otherwise the
            // jump is pressed mid-air (walked off an edge, already lifted by the knockback), where
            // vanilla ignores it and the pending tick is simply burned.
            if (!canJumpNow(player)) {
                this.jumpTicks = 0;
                return;
            }
            mc.options.keyJump.setDown(true);
            this.jumpTicks--;
            logger.info("[AntiKB] JumpReset jump tick={} onGround={}", player.tickCount, player.onGround());
            return;
        }
        if (!Scaffold.INSTANCE.isEnabled()) {
            boolean down = InputConstants.isKeyDown(mc.getWindow().getWindow(), mc.options.keyJump.getKey().getValue());
            mc.options.keyJump.setDown(down);
        }
    }

    /**
     * Pre-emptive jump reset: the opponent's swing animation reaches us roughly one tick before the
     * knockback packet does, so a fresh swing that is actually aimed at us is treated as a hit that
     * lands next tick.
     *
     * <p>A swing alone is a weak signal - the animation also fires when the opponent mines, hits
     * someone else or whiffs at the air. Three cheap filters cut most of the false positives: the
     * animation must be in its first two ticks, the opponent must be within hitting distance, and
     * its horizontal facing must point at us within 30 degrees before the box clip is even tried.
     */
    private boolean shouldPredictJump(LocalPlayer player) {
        if (!AntiKB.INSTANCE.predict.getValue()) return false;
        if (this.jumpTicks > 0 || Scaffold.INSTANCE.isEnabled()) return false;
        if (!canJumpNow(player)) return false;
        if (player.hurtTime > 0) {
            // Damage already landed. Only top the window up when a knockback packet actually
            // arrived and the jump has not been spent yet: hurtTime also rises for fall, fire and
            // poison damage, none of which send a motion packet, and the old check jumped on all
            // of them.
            if (isJumping) return false;
            // The knockback packet is the only reliable "an entity actually hit us" signal on the
            // client: LocalPlayer never runs LivingEntity.hurt, and hurtTime also goes up for fall,
            // fire and poison damage, none of which send a motion packet.
            int sinceKnockback = player.tickCount - this.lastKnockbackTick;
            return sinceKnockback >= 0 && sinceKnockback <= HURT_FALLBACK_TICKS;
        }

        KillAura aura = KillAura.INSTANCE;
        Entity target = aura != null && aura.isEnabled() ? KillAura.target : null;
        if (!(target instanceof LivingEntity living) || !living.isAlive()) return false;
        // swingTime resets to 0 on a fresh swing; only the opening two ticks count.
        if (!living.swinging || living.swingTime > 1) return false;

        Vec3 eyePos = living.getEyePosition(1.0f);
        Vec3 myEye = player.getEyePosition(1.0f);
        double dist = eyePos.distanceTo(myEye);
        if (dist > PREDICT_MAX_DISTANCE) return false;
        // Facing filter on the horizontal plane: pitch is irrelevant (a player aiming at our feet
        // still hits us) and would only shrink the cone.
        double dx = player.getX() - living.getX();
        double dz = player.getZ() - living.getZ();
        double horizontal = Math.hypot(dx, dz);
        if (horizontal > 1.0E-4) {
            Vec3 look = living.getLookAngle();
            double lookLen = Math.hypot(look.x, look.z);
            if (lookLen > 1.0E-4) {
                double dot = (look.x * dx + look.z * dz) / (lookLen * horizontal);
                if (dot < PREDICT_MIN_FACING_DOT) return false;
            }
        }
        Vec3 reachEnd = eyePos.add(living.getLookAngle().scale(dist + 0.5));
        return player.getBoundingBox().inflate(0.1).clip(eyePos, reachEnd).isPresent();
    }

    @Override
    public void onTick(TickEvent event) {
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (!AntiKB.mode.is("Jump Reset")) return;
        if (this.isSuspended()) {
            this.cancelJumpReset();
            return;
        }
        if (AntiKB.rotation != null) {
            this.rotationHeldTicks++;
        }
        if (player.hurtTime == 0) {
            isJumping = false;
        }
        boolean shouldClear = player.hurtTime == 0
                || this.rotationHeldTicks > AntiKB.INSTANCE.rotateTicks.getValue().intValue()
                || (!AntiKB.INSTANCE.rotate.getValue() && !AntiKB.INSTANCE.followDirection.getValue());
        if (shouldClear) {
            AntiKB.rotation = null;
            this.knockbackPacket = null;
            this.rotationHeldTicks = 0;
        }
    }
}
