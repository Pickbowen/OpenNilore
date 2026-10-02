package client.nilore.modules.impl.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.TickEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.combat.antikb.NoXZMode;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;

public class Critical extends Module {
    public static Critical INSTANCE;

    public final ModeSetting mode = new ModeSetting("Mode", "Stuck", "1.9+");

    public final NumberSetting waitTicks = new NumberSetting("WaitTicks", 1.0, 1.0, 3.0, 1.0,
            () -> this.mode.is("Stuck"));

    public final BooleanSetting fallDistance = new BooleanSetting("FallDistance", false,
            () -> this.mode.is("Stuck"));

    public final NumberSetting targetTicks = new NumberSetting("TargetTicks", 2.0, 0.1, 3.0, 1.0,
            () -> this.mode.is("1.9+"));

    private float lastCritDamage;

    public Critical() {
        super("Critical", Category.COMBAT);
        INSTANCE = this;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (mc.player == null) {
            return;
        }
        if (this.isReleaseWindow()) {
            mc.options.keySprint.setDown(false);
            if (mc.player.isSprinting()) {
                mc.player.setSprinting(false);
            }
        }
    }

    public boolean isReleaseWindow() {
        if (NoXZMode.handlingVelocity) return false;
        if (mc.player == null) return false;
        Entity target = KillAura.target;
        if (!(target instanceof LivingEntity living)) {
            return false;
        }
        if (mc.player.onGround()) return false;
        if (mc.player.isInWater() || mc.player.isInLava()) return false;
        if (mc.player.isUsingItem()) return false;
        if (mc.player.isShiftKeyDown()) return false;
        if (mc.player.isFallFlying()) return false;
        if (mc.player.isPassenger()) return false;
        if (mc.player.onClimbable()) return false;
        if (mc.player.hasEffect(MobEffects.BLINDNESS)) return false;
        if (mc.player.hasEffect(MobEffects.SLOW_FALLING)) return false;
        if (mc.player.hasEffect(MobEffects.LEVITATION)) return false;
        int hurtTime = living.hurtTime;
        return hurtTime >= 7 || hurtTime <= 3;
    }

    public boolean shouldCritEntity(Entity entity) {
        if (!this.isEnabled() || !this.mode.is("1.9+") || mc.player == null) {
            return false;
        }
        if (mc.player.isSprinting() || mc.player.isPassenger() || mc.player.isInWater()
                || mc.player.onClimbable() || mc.player.isFallFlying()) {
            return false;
        }
        if (!(entity instanceof LivingEntity living)) {
            return false;
        }
        float damage = this.critDamage();
        if (living.hurtTime > 0 && damage <= this.lastCritDamage) {
            return false;
        }
        if (this.isCritBlocked(false)) {
            return false;
        }
        double velocityY = mc.player.getDeltaMovement().y;
        if (velocityY < -0.08) {
            this.lastCritDamage = damage;
            return false;
        }
        float chargeGap = Math.max(0.0f, (0.95f - mc.player.getAttackStrengthScale(0.5f))
                * mc.player.getCurrentItemAttackStrengthDelay());
        float window = Math.max(chargeGap, (float) (velocityY / 0.08));
        if (window > this.targetTicks.getValue().floatValue()) {
            return false;
        }
        return !this.blockedAhead((int) (window * 1.3f));
    }

    public boolean holdAttack(Entity entity) {
        if (!this.isEnabled() || !this.mode.is("1.9+") || mc.player == null) {
            return false;
        }
        if (NoXZMode.handlingVelocity) {
            return false;
        }
        if (mc.player.getAttackStrengthScale(0.0f) < 0.95f) {
            return true;
        }
        return this.shouldCritEntity(entity);
    }

    private float critDamage() {
        if (mc.player == null) {
            return -1.0f;
        }
        float base = (float) mc.player.getAttributeValue(Attributes.ATTACK_DAMAGE);
        float charge = mc.player.getAttackStrengthScale(0.5f);
        float damage = base * (0.2f + charge * charge * 0.8f);
        if (!this.isCritBlocked(false) && mc.player.getDeltaMovement().y < -0.08) {
            damage *= 1.5f;
        }
        return damage;
    }

    private boolean isCritBlocked(boolean allowAir) {
        if (mc.player == null) {
            return true;
        }
        if (mc.player.hasEffect(MobEffects.BLINDNESS)
                || mc.player.hasEffect(MobEffects.SLOW_FALLING)
                || mc.player.hasEffect(MobEffects.LEVITATION)) {
            return true;
        }
        if (mc.player.isUsingItem() || mc.player.isInWater() || mc.player.isShiftKeyDown()
                || mc.player.getAbilities().flying || mc.player.isFallFlying() || mc.player.isPassenger()) {
            return true;
        }
        if (!allowAir && mc.player.onGround()) {
            return true;
        }
        if (mc.player.onClimbable()) {
            return true;
        }
        return this.inCobweb();
    }

    private boolean inCobweb() {
        if (mc.player == null || mc.level == null) {
            return false;
        }
        AABB box = mc.player.getBoundingBox();
        for (BlockPos pos : BlockPos.betweenClosed(
                Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ),
                Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ))) {
            if (mc.level.getBlockState(pos).is(Blocks.COBWEB)) {
                return true;
            }
        }
        return false;
    }

    private boolean blockedAhead(int ticks) {
        if (mc.player == null || mc.level == null) {
            return false;
        }
        double originX = mc.player.getX();
        double originY = mc.player.getY();
        double originZ = mc.player.getZ();
        double x = originX;
        double y = originY;
        double z = originZ;
        Vec3 velocity = mc.player.getDeltaMovement();
        AABB box = mc.player.getBoundingBox();
        for (int i = 0; i < ticks; ++i) {
            x += velocity.x;
            y += velocity.y;
            z += velocity.z;
            velocity = new Vec3(velocity.x * 0.91, (velocity.y - 0.08) * 0.98, velocity.z * 0.91);
            if (!mc.level.noCollision(mc.player, box.move(x - originX, y - originY, z - originZ))) {
                return true;
            }
        }
        return false;
    }
}
