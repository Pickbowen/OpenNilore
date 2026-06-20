package shit.nilore.modules.impl.player;

import java.util.Comparator;
import java.util.Optional;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import shit.nilore.event.impl.TickEvent;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.modules.impl.combat.KillAura;
import shit.nilore.modules.impl.misc.AutoRod;
import shit.nilore.modules.impl.movement.FireballBlink;
import shit.nilore.modules.impl.render.Projectiles;
import shit.nilore.settings.impl.BooleanSetting;
import shit.nilore.settings.impl.ModeSetting;
import shit.nilore.utils.animation.Timer;
import shit.nilore.utils.game.ItemUtil;
import shit.nilore.utils.game.RotationUtil;
import shit.nilore.utils.misc.PacketUtil;
import shit.nilore.utils.rotation.Rotation;
import shit.nilore.event.EventTarget;

public class MidPearl extends Module {
    public static MidPearl INSTANCE;
    public static Rotation targetRotation;

    private final ModeSetting mainButtonSetting = new ModeSetting("Button", "Middle", "Mouse 4", "Mouse 5").withDefault("Middle");
    private final BooleanSetting interceptSetting = new BooleanSetting("Intercept", false);
    private final ModeSetting interceptButtonSetting = new ModeSetting("Intercept Button", "Mouse 4", "Mouse 5").withDefault("Mouse 4");
    private final BooleanSetting holdModeSetting = new BooleanSetting("On Release", false);
    private final BooleanSetting losCheckSetting = new BooleanSetting("Block Check", true);
    private final Timer throwTimer = new Timer();
    private boolean isHoldingIntercept = false;
    private boolean isHoldingButton = false;
    private boolean pearlSlotSelected = false;
    private int throwCountdown = 0;
    private int disableDelay = 0;
    private int pearlSavedSlot = -1;
    private boolean throwPhaseActive = false;
    private int preThrowTicks = 0;
    private int rodSavedSlot = -1;
    private int postThrowTicks = 0;

    public MidPearl() {
        super("MidPearl", Category.PLAYER);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.reset();
    }

    @Override
    public void onDisable() {
        if (this.pearlSavedSlot != -1 && mc.player != null) {
            mc.player.getInventory().selected = this.pearlSavedSlot;
        }
        if (this.rodSavedSlot != -1 && mc.player != null) {
            mc.player.getInventory().selected = this.rodSavedSlot;
        }
        this.reset();
    }

    private void reset() {
        this.pearlSlotSelected = false;
        this.throwCountdown = 0;
        this.disableDelay = 0;
        this.pearlSavedSlot = -1;
        this.throwPhaseActive = false;
        targetRotation = null;
        this.preThrowTicks = 0;
        this.isHoldingIntercept = false;
        this.isHoldingButton = false;
        this.rodSavedSlot = -1;
        this.postThrowTicks = 0;
    }

    private void cancelThrow() {
        this.throwPhaseActive = false;
        targetRotation = null;
        this.preThrowTicks = 0;
        this.isHoldingIntercept = false;
    }

    private int getMouseButton(ModeSetting setting) {
        return switch (setting.getValue()) {
            case "Mouse 4" -> 3;
            case "Mouse 5" -> 4;
            default -> 2;
        };
    }

    public boolean isInterceptButton(int button) {
        return this.interceptSetting.getValue()
                && this.getMouseButton(this.interceptButtonSetting) == button
                && this.hasTargetRotation();
    }

    private boolean isAutoRodActive() {
        AutoRod autoRod = AutoRod.INSTANCE;
        return autoRod != null && autoRod.isEnabled()
                && autoRod.shouldInterceptButton(this.getMouseButton(this.mainButtonSetting));
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (FireballBlink.INSTANCE != null && FireballBlink.INSTANCE.isEnabled()) return;
        if (mc.player == null || mc.level == null) {
            if (this.throwPhaseActive) this.reset();
            return;
        }
        if (this.postThrowTicks > 0) {
            --this.postThrowTicks;
            if (this.postThrowTicks == 0 && this.rodSavedSlot != -1) {
                mc.player.getInventory().selected = this.rodSavedSlot;
                this.rodSavedSlot = -1;
            }
        }
        if (this.disableDelay > 0) {
            --this.disableDelay;
            if (this.disableDelay == 0) {
                if (this.pearlSavedSlot != -1) {
                    mc.player.getInventory().selected = this.pearlSavedSlot;
                }
                this.reset();
            }
            return;
        }
        if (this.throwPhaseActive) {
            if (this.preThrowTicks > 0) --this.preThrowTicks;
            if (this.preThrowTicks == 0) this.throwPearl();
            return;
        }
        if (this.interceptSetting.getValue()) {
            boolean pressed = GLFW.glfwGetMouseButton(mc.getWindow().getWindow(),
                    this.getMouseButton(this.interceptButtonSetting)) == 1;
            if (pressed) {
                if (this.isHoldingButton) return;
            } else {
                this.isHoldingButton = false;
            }
            if (this.holdModeSetting.getValue()) {
                if (pressed) {
                    this.updateTargetRotation();
                    this.isHoldingIntercept = true;
                } else {
                    if (this.isHoldingIntercept) {
                        if (targetRotation != null) {
                            this.selectPearlSlot(ItemUtil.getSlot(Items.ENDER_PEARL));
                        } else {
                            this.cancelThrow();
                        }
                    }
                    this.isHoldingIntercept = false;
                }
            } else if (pressed) {
                this.updateTargetRotation();
                if (targetRotation != null) {
                    this.selectPearlSlot(ItemUtil.getSlot(Items.ENDER_PEARL));
                }
            } else {
                this.cancelThrow();
            }
            boolean sameButton = this.getMouseButton(this.interceptButtonSetting) == this.getMouseButton(this.mainButtonSetting);
            if (sameButton && (pressed || this.isHoldingIntercept)) return;
        }
        if (this.isAutoRodActive()) return;
        this.tickNormalMode();
    }

    private void updateTargetRotation() {
        targetRotation = this.calculateTargetRotation().orElse(null);
    }

    private boolean hasTargetRotation() {
        return this.calculateTargetRotation().isPresent();
    }

    private Optional<Rotation> calculateTargetRotation() {
        Optional<Projectiles.ProjectileEntry> projectile = this.findNearestEnemyPearl();
        if (projectile.isPresent()) {
            int slot = ItemUtil.getSlot(Items.ENDER_PEARL);
            if (slot != -1 && slot < 9) {
                Vec3 velocity = projectile.get().getVelocity();
                if (this.losCheckSetting.getValue()
                        && mc.level.clip(new ClipContext(
                                mc.player.getEyePosition(), velocity,
                                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                                mc.player)).getType() != HitResult.Type.MISS) {
                    return Optional.empty();
                }
                float[] angles = RotationUtil.getBallisticAngles(velocity);
                if (angles != null) {
                    return Optional.of(new Rotation(angles[0], angles[1]));
                }
            }
        }
        return Optional.empty();
    }

    private void selectPearlSlot(int slot) {
        if (slot == -1 || slot > 8) return;
        if (this.throwPhaseActive) return;
        this.throwPhaseActive = true;
        this.preThrowTicks = 2;
        this.rodSavedSlot = mc.player.getInventory().selected;
        mc.player.getInventory().selected = slot;
    }

    private void throwPearl() {
        if (mc.player.getMainHandItem().getItem() == Items.ENDER_PEARL) {
            PacketUtil.sendPredictive(n -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, n));
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
        this.postThrowTicks = 2;
        this.throwPhaseActive = false;
        this.preThrowTicks = -1;
        this.isHoldingButton = true;
    }

    private void tickNormalMode() {
        if (this.throwCountdown > 0) {
            --this.throwCountdown;
            if (this.throwCountdown == 0) {
                if (mc.player.getMainHandItem().getItem() == Items.ENDER_PEARL) {
                    PacketUtil.sendPredictive(n -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, n));
                    mc.player.swing(InteractionHand.MAIN_HAND);
                }
                this.disableDelay = 2;
            }
            return;
        }
        boolean pressed = GLFW.glfwGetMouseButton(mc.getWindow().getWindow(),
                this.getMouseButton(this.mainButtonSetting)) == 1;
        if (pressed) {
            if (!this.pearlSlotSelected) {
                int slot = ItemUtil.getSlot(Items.ENDER_PEARL);
                if (slot != -1 && slot < 9) {
                    this.pearlSlotSelected = true;
                    this.pearlSavedSlot = mc.player.getInventory().selected;
                    if (KillAura.INSTANCE != null && KillAura.INSTANCE.isEnabled()) {
                        KillAura.INSTANCE.setEnabled(false);
                    }
                    mc.player.getInventory().selected = slot;
                }
            }
        } else if (this.pearlSlotSelected) {
            this.pearlSlotSelected = false;
            this.throwCountdown = 1;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Optional<Projectiles.ProjectileEntry> findNearestEnemyPearl() {
        if (Projectiles.projectileMap.isEmpty()) return Optional.empty();
        return Projectiles.projectileMap.entrySet().stream()
                .filter(entry -> {
                    if (mc.level == null || mc.player == null) return false;
                    Entity entity = mc.level.getEntity(entry.getKey());
                    if (!(entity instanceof ThrownEnderpearl pearl)) return false;
                    Entity owner = pearl.getOwner();
                    return owner != null && !owner.equals(mc.player);
                })
                .map(e -> e.getValue())
                .min(Comparator.comparingDouble(
                        entry -> entry.getVelocity().distanceTo(mc.player.position())));
    }
}
