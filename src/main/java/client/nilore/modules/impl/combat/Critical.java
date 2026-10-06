package client.nilore.modules.impl.combat;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.EntityRemoveEvent;
import client.nilore.event.impl.MotionEvent;
import client.nilore.event.impl.PlayerTickEvent;
import client.nilore.event.impl.PreMotionEvent;
import client.nilore.event.impl.StrafeEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.game.RotationUtil;
import client.nilore.utils.misc.ChatUtil;
import client.nilore.utils.rotation.Rotation;
import client.nilore.utils.rotation.RotationHandler;

public class Critical extends Module {
    public static Critical INSTANCE;

    public static volatile boolean stopAttack = false;

    public final ModeSetting mode = new ModeSetting("Mode", "Grim", "SkipTicks", "Optimize").withDefault("Grim");

    public final NumberSetting skipTicksRange = new NumberSetting("SkipTicks Range", 3.0, 1.0, 6.0, 0.1,
            () -> this.mode.is("SkipTicks"));

    public final BooleanSetting autoJump = new BooleanSetting("Auto Jump", false);

    public final BooleanSetting debug = new BooleanSetting("Debug", false);

    private LivingEntity enemy;
    private boolean isAttack;
    private boolean isSprint;
    private boolean again;
    private boolean balance;
    private int skipTicks;

    public Critical() {
        super("Critical", Category.COMBAT);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.clearState();
    }

    @Override
    public void onDisable() {
        this.clearState();
    }

    private void clearState() {
        this.enemy = null;
        this.isAttack = false;
        this.isSprint = false;
        this.again = false;
        this.balance = false;
        this.skipTicks = 0;
        stopAttack = false;
    }

    @EventTarget
    public void onPreMotion(PreMotionEvent event) {
        if (mc.player == null) {
            return;
        }
        if (this.mode.is("SkipTicks")) {
            this.updateSkipTicks();
        } else if (this.mode.is("Optimize")) {
            this.optimizeExtraAttack();
        }
    }

    private void updateSkipTicks() {
        Entity target = KillAura.target;
        if (target == null) {
            return;
        }
        if (this.cantCrit(target)) {
            this.skipTicks = 0;
            return;
        }
        boolean auraActive = KillAura.INSTANCE != null && KillAura.INSTANCE.isEnabled();
        if (mc.player.getDeltaMovement().y < 0.0
                && !mc.player.onGround()
                && auraActive
                && mc.player.distanceTo(target) <= this.skipTicksRange.getValue().floatValue()) {
            if (this.skipTicks <= 0) {
                this.skipTicks++;
            }
        } else if (!auraActive) {
            this.skipTicks = 0;
        }
    }

    private void optimizeExtraAttack() {
        if (!this.isAttack || !this.balance || !this.isFalling() || mc.player.isSprinting()) {
            return;
        }
        this.again = false;
        stopAttack = false;
        LivingEntity current = this.enemy;
        if (current != null && this.isLookingAt(current, 4.0)) {
            if (KillAura.attackAndLock()) {
                mc.gameMode.attack(mc.player, current);
                mc.player.resetAttackStrengthTicker();
                mc.player.swing(InteractionHand.MAIN_HAND);
            } else {
                this.again = true;
            }
        }
        if (!this.again) {
            this.isSprint = true;
            this.balance = false;
            this.debugLog("Crit.");
        }
    }

    /**
     * The state machine and the sprint release live in the post phase, not on TickEvent.
     *
     * <p>TickEvent fires at the head of Minecraft.tick, a whole LocalPlayer.tick before aiStep, so a
     * {@code setSprinting(false)} there was re-derived back to true from the still-pressed sprint key
     * inside the same tick's aiStep, {@code wasSprinting} never flipped and the server never received
     * a STOP_SPRINTING - the release was dead code. The post MotionEvent is the hop that lands after
     * aiStep's derivation and before sendPosition's sendIsSprintingIfNeeded.
     */
    @EventTarget
    public void onPostMotion(MotionEvent event) {
        if (mc.player == null || !event.isPost()) {
            return;
        }
        if (this.enemy == null) {
            return;
        }
        if (this.mode.is("Grim")) {
            if (this.enemy.hurtTime < 2 || this.enemy.distanceTo(mc.player) > 4.0) {
                this.enemy = null;
                this.isAttack = false;
                return;
            }
            if (this.isAttack && !mc.player.onGround() && mc.player.fallDistance > 0.0f) {
                this.releaseSprint();
                this.isSprint = true;
            }
        } else if (this.mode.is("Optimize")) {
            if (this.enemy.hurtTime < 2 || this.enemy.distanceTo(mc.player) > 4.0) {
                this.enemy = null;
                this.isAttack = false;
                stopAttack = false;
                return;
            }
            double velocityY = mc.player.getDeltaMovement().y;
            stopAttack = !mc.player.onGround() && velocityY > 0.0 && velocityY < 0.16;
            if (this.isAttack && this.isFalling()) {
                this.releaseSprint();
                this.debugLog("Balance.");
                this.balance = true;
            }
        }
    }

    @EventTarget
    public void onStrafe(StrafeEvent event) {
        if (mc.player == null || !this.autoJump.getValue()) {
            return;
        }
        if (mc.player.onGround() && mc.player.isSprinting() && this.isAttack) {
            event.setSprinting(true);
        }
    }

    @EventTarget
    public void onAttack(EntityRemoveEvent event) {
        if (mc.player == null || event.dead()) {
            return;
        }
        if (this.mode.is("SkipTicks")) {
            if (mc.player.fallDistance > 0.0f && !mc.player.isSprinting() && mc.player.hurtTime < 2) {
                this.debugLog("Crit.");
            }
            return;
        }
        if (!(event.entity() instanceof LivingEntity living)) {
            this.enemy = null;
            this.isAttack = false;
            this.isSprint = false;
            return;
        }
        if (mc.player.isSprinting()) {
            this.enemy = living;
            this.isAttack = true;
        }
        if (mc.player.fallDistance > 0.0f && !mc.player.isSprinting() && this.isAttack && this.isSprint) {
            this.isAttack = false;
            this.isSprint = false;
            if (this.mode.is("Optimize")) {
                stopAttack = false;
            }
            this.debugLog("Crit.");
        }
    }

    @EventTarget
    public void onPlayerTick(PlayerTickEvent event) {
        if (!this.consumeSkipTick()) {
            return;
        }
        event.setCancelled(true);
    }

    public boolean consumeSkipTick() {
        if (!this.isEnabled() || !this.mode.is("SkipTicks") || this.skipTicks <= 0) {
            return false;
        }
        this.skipTicks--;
        return true;
    }

    private void releaseSprint() {
        mc.player.setSprinting(false);
        mc.options.keyShift.setDown(false);
    }

    private boolean canPrepareCritical() {
        return !mc.player.onGround()
                && !mc.player.onClimbable()
                && !mc.player.isInWater()
                && !mc.player.isInLava()
                && !mc.player.isPassenger()
                && !mc.player.getAbilities().flying;
    }

    private boolean isFalling() {
        return this.canPrepareCritical() && mc.player.fallDistance > 0.0f;
    }

    /**
     * Vanilla's crit preconditions, minus the sprint flag and the attack charge - the caller owns
     * those. Anything false here means a crit is off the table entirely, so the swing has to go
     * through untouched: the ground, water, a ladder, an elytra, riding or creative flight must not
     * turn the aura's gates into a stall.
     */
    private boolean critConditionsMet() {
        if (mc.player == null) {
            return false;
        }
        return !mc.player.onGround() && !mc.player.onClimbable() && !mc.player.isInWater()
                && !mc.player.isInLava() && !mc.player.getAbilities().flying
                && !mc.player.isPassenger() && !mc.player.isFallFlying()
                && !mc.player.hasEffect(MobEffects.BLINDNESS);
    }

    /**
     * True while a crit is reachable but not yet available: still on the way up, where
     * {@code fallDistance} is still 0 and no swing can crit.
     */
    public boolean canCritSoon() {
        return this.critConditionsMet() && mc.player.fallDistance <= 0.0f;
    }

    private boolean cantCrit(Entity target) {
        if (mc.player == null) {
            return true;
        }
        if (!(target instanceof LivingEntity living)) {
            return true;
        }
        return mc.player.onClimbable()
                || mc.player.isInWater()
                || mc.player.isInLava()
                || mc.player.isPassenger()
                || living.hurtTime > 10
                || living.getHealth() <= 0.0f;
    }

    private boolean isLookingAt(Entity entity, double range) {
        Rotation rotation = RotationHandler.targetRotation != null
                ? RotationHandler.targetRotation
                : new Rotation(mc.player.getYRot(), mc.player.getXRot());
        return RotationUtil.rayTraceForAim(rotation, range) instanceof EntityHitResult hit
                && hit.getEntity() == entity;
    }

    private void debugLog(String message) {
        if (this.debug.getValue() && mc.player != null && mc.level != null) {
            ChatUtil.print("[Critical] " + message);
        }
    }
}
