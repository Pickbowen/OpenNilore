package client.nilore.modules.impl.player;

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
import client.nilore.event.impl.TickEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.combat.KillAura;
import client.nilore.modules.impl.misc.AutoRod;
import client.nilore.modules.impl.movement.FireballBlink;
import client.nilore.modules.impl.render.Projectiles;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.utils.animation.Timer;
import client.nilore.utils.game.ItemUtil;
import client.nilore.utils.game.RotationUtil;
import client.nilore.utils.misc.PacketUtil;
import client.nilore.utils.rotation.Rotation;
import client.nilore.event.EventTarget;

public class MidPearl extends Module {
    /** 投掷窗口的光环停摆拍数: 从选槽到出手约 2-3 拍。 */
    private static final int AURA_PAUSE_TICKS = 3;

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
        // Intercept 路径也走这里: 出刀与朝向都得让这一拍, 否则珍珠会被光环甩偏。
        KillAura.pauseFor(2);
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
                // 刚出手: 再压 3 拍, 让槽位恢复和光环接管错开。
                KillAura.pauseFor(AURA_PAUSE_TICKS);
            }
            return;
        }
        boolean pressed = GLFW.glfwGetMouseButton(mc.getWindow().getWindow(),
                this.getMouseButton(this.mainButtonSetting)) == 1;
        if (pressed) {
            // 首次实现是直接 {@code KillAura.INSTANCE.setEnabled(false)}: 目标、APS 蓄力、
            // 朝向全部归零, 扔完还得手动开回来。改成每拍续期的停摆: 模块保持启用,
            // 只跳过投掷窗口的出刀与朝向接管, 松手出手后 3 拍自动恢复。
            KillAura.pauseFor(AURA_PAUSE_TICKS);
            if (!this.pearlSlotSelected) {
                int slot = ItemUtil.getSlot(Items.ENDER_PEARL);
                if (slot != -1 && slot < 9) {
                    this.pearlSlotSelected = true;
                    this.pearlSavedSlot = mc.player.getInventory().selected;
                    mc.player.getInventory().selected = slot;
                }
            }
        } else if (this.pearlSlotSelected) {
            this.pearlSlotSelected = false;
            this.throwCountdown = 1;
            KillAura.pauseFor(AURA_PAUSE_TICKS);
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
