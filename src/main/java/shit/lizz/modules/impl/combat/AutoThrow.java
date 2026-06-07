package shit.lizz.modules.impl.combat;

import java.util.Comparator;
import java.util.Optional;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import shit.lizz.event.impl.SprintEvent;
import shit.lizz.event.impl.TickEvent;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.modules.impl.movement.Scaffold;
import shit.lizz.modules.impl.player.Stuck;
import shit.lizz.settings.impl.NumberSetting;
import shit.lizz.utils.animation.Timer;
import shit.lizz.utils.game.PlayerUtil;
import shit.lizz.utils.game.RotationUtil;
import shit.lizz.utils.rotation.Rotation;
import shit.lizz.utils.rotation.RotationHandler;
import shit.lizz.event.EventTarget;

public class AutoThrow
extends Module {
    public static AutoThrow INSTANCE;
    private final NumberSetting minDistance = new NumberSetting("Min Distance", 5, 3, 30, 1);
    private final NumberSetting maxDistance = new NumberSetting("Max Distance", 10, 3, 30, 1);
    private final NumberSetting throwDelay = new NumberSetting("Delay", 500, 50, 2000, 50);
    private final Timer throwTimer = new Timer();
    public Rotation targetRotation;
    public int ticksUntilThrow;
    private int savedSlot = -1;

    public AutoThrow() {
        super("AutoThrow", Category.COMBAT);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.targetRotation = null;
        this.ticksUntilThrow = 0;
        this.savedSlot = -1;
        this.throwTimer.reset();
    }

    @Override
    public void onDisable() {
        this.targetRotation = null;
        this.ticksUntilThrow = 0;
        this.savedSlot = -1;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     * Enabled force condition propagation
     * Lifted jumps to return sites
     */
    @EventTarget
    public void onSprint(SprintEvent sprintEvent) {
        int slot;
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.getConnection() == null) {
            return;
        }
        if (Scaffold.INSTANCE != null && Scaffold.INSTANCE.isEnabled() || Stuck.INSTANCE != null && Stuck.INSTANCE.isEnabled() || mc.player.isUsingItem() || mc.screen != null) {
            this.ticksUntilThrow = 0;
            this.targetRotation = null;
            return;
        }
        if (this.ticksUntilThrow <= 0) {
            this.targetRotation = null;
        }
        int projectileSlot = -1;
        for (slot = 0; slot < 9; ++slot) {
            ItemStack itemStack = mc.player.getInventory().getItem(slot);
            if (itemStack.isEmpty() || !(itemStack.getItem() instanceof EggItem) && !(itemStack.getItem() instanceof SnowballItem)) continue;
            projectileSlot = slot;
            break;
        }
        if (mc.player.isUsingItem() || mc.player.getMainHandItem().getItem() instanceof BowItem || mc.player.getMainHandItem().getItem() instanceof CrossbowItem) {
            return;
        }
        if (projectileSlot == -1) return;
        if (--this.ticksUntilThrow == 0) {
            slot = mc.player.getInventory().selected;
            boolean shouldSwap = slot != projectileSlot;
            if (shouldSwap) {
                mc.player.getInventory().selected = projectileSlot;
                PlayerUtil.sendCarriedItem();
                this.savedSlot = slot;
            }
            float prevYaw = mc.player.getYRot();
            float prevPitch = mc.player.getXRot();
            if (RotationHandler.targetRotation != null && RotationHandler.isRotating) {
                mc.player.setYRot(RotationHandler.targetRotation.getYaw());
                mc.player.setXRot(RotationHandler.targetRotation.getPitch());
            }
            try {
                if (!(mc.player.getMainHandItem().getItem() instanceof EggItem) && !(mc.player.getMainHandItem().getItem() instanceof SnowballItem)) return;
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            } finally {
                mc.player.setYRot(prevYaw);
                mc.player.setXRot(prevPitch);
            }
        } else {
            if (!this.findTarget().isPresent() || !this.throwTimer.hasPassed((float)((long) this.throwDelay.getValue().doubleValue())) || Stuck.INSTANCE != null && Stuck.INSTANCE.isEnabled()) return;
            this.targetRotation = this.calculateThrowRotation(this.findTarget().get());
            if (this.targetRotation != null) {
                RotationHandler.setTargetRotation(this.targetRotation);
                RotationHandler.isRotating = true;
                this.ticksUntilThrow = 2;
            }
            this.throwTimer.reset();
        }
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        if (mc.player == null) {
            return;
        }
        if (this.savedSlot != -1) {
            mc.player.getInventory().selected = this.savedSlot;
            this.savedSlot = -1;
            RotationHandler.isRotating = false;
        }
    }

    private Rotation calculateThrowRotation(Entity entity) {
        float pitch;
        float projectileSpeed = 1.5f;
        float gravity = 0.03f;
        double predictX = entity.getX();
        double predictY = entity.getY() + (double)entity.getBbHeight() * 0.8;
        double predictZ = entity.getZ();
        double velX = entity.getX() - entity.xOld;
        double velY = entity.getY() - entity.yOld;
        double velZ = entity.getZ() - entity.zOld;
        for (int i = 0; i < 3; ++i) {
            double dx = predictX - mc.player.getX();
            double dy = predictY - (mc.player.getY() + (double)mc.player.getEyeHeight(mc.player.getPose()));
            double dz = predictZ - mc.player.getZ();
            double horizDist = Math.sqrt(dx * dx + dz * dz);
            float travelTicks = (float)(horizDist / (double)(projectileSpeed * 0.4f));
            predictX = entity.getX() + velX * (double)travelTicks;
            predictY = entity.getY() + (double)entity.getBbHeight() * 0.8 + velY * (double)travelTicks;
            predictZ = entity.getZ() + velZ * (double)travelTicks;
        }
        double dx = predictX - mc.player.getX();
        double dy = predictY - (mc.player.getY() + (double)mc.player.getEyeHeight(mc.player.getPose()));
        double dz = predictZ - mc.player.getZ();
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float)(Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        pitch = -RotationUtil.ballisticPitch((float)horizDist, (float)dy, projectileSpeed, gravity);
        return new Rotation(yaw, pitch);
    }

    private Optional<? extends Player> findTarget() {
        if (mc.player == null || mc.level == null) {
            return Optional.empty();
        }
        return mc.level.players().stream().filter(player -> player != mc.player).filter(player -> KillAura.INSTANCE.isValidTarget(player)).filter(player -> {
            double dist = this.getDistanceTo(player);
            return dist >= this.minDistance.getValue().doubleValue() && dist <= this.maxDistance.getValue().doubleValue();
        }).filter(this::hasLineOfSight).filter(player -> !this.isInvisibleAlly(player)).min(Comparator.comparingDouble(player -> mc.player.distanceTo(player)));
    }

    private double getDistanceTo(Entity entity) {
        double dx = mc.player.getX() - entity.getX();
        double dz = mc.player.getZ() - entity.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private boolean hasLineOfSight(Entity entity) {
        Vec3 targetPos;
        if (mc.player == null || mc.level == null) {
            return false;
        }
        Vec3 eyePos = new Vec3(mc.player.getX(), mc.player.getY() + (double)mc.player.getEyeHeight(), mc.player.getZ());
        BlockHitResult hit = mc.level.clip(new ClipContext(eyePos, targetPos = new Vec3(entity.getX(), entity.getY() + (double)entity.getEyeHeight(), entity.getZ()), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    private boolean isInvisibleAlly(Entity entity) {
        if (!entity.isInvisible()) {
            return false;
        }
        if (mc.player.isSpectator()) {
            return false;
        }
        Team team = entity.getTeam();
        return team == null || mc.player.getTeam() != team || !team.isAlliedTo(mc.player.getTeam());
    }
}