package client.nilore.modules.impl.combat;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.AbstractGolem;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import client.nilore.ClientBase;
import client.nilore.NiloreClient;
import client.nilore.event.impl.EventAttackSlowdown;
import client.nilore.event.impl.PreMotionEvent;
import client.nilore.event.impl.RenderEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.event.impl.WorldChangeEvent;
import client.nilore.hud.ModuleListHud;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.combat.antikb.JumpResetMode;
import client.nilore.modules.impl.combat.antikb.NoXZMode;
import client.nilore.modules.impl.movement.Scaffold;
import client.nilore.modules.impl.player.AntiTNT;
import client.nilore.modules.impl.player.AntiWeb;
import client.nilore.modules.impl.player.AutoWebPlace;
import client.nilore.modules.impl.player.Helper;
import client.nilore.modules.impl.player.MidPearl;
import client.nilore.modules.impl.player.Stuck;
import client.nilore.modules.impl.world.Teams;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.game.EntityUtil;
import client.nilore.utils.game.ItemUtil;
import client.nilore.utils.game.MotionSimulator;
import client.nilore.utils.game.RotationUtil;
import client.nilore.utils.math.MathUtil;
import client.nilore.utils.misc.Assets;
import client.nilore.utils.render.RenderUtil;
import client.nilore.utils.rotation.Rotation;
import client.nilore.utils.rotation.RotationHandler;
import client.nilore.event.EventTarget;

/**
 * KillAura — Naven Aura 引擎移植 (tech.naven.modules.impl.combat.Aura)。
 *
 * <p>Nilore 原版的攻击引擎 (有机抖动 / 旋转平滑器 / 旧朝向双闸门 / 慢扫描) 已整体废弃。
 * 现在的流程与 Naven 一致: onTick(≈EventRunTicks PRE) 更新目标 + 直取瞄准朝向 + 蓄力 APS;
 * onPreMotion(≈onClick) 蓄满即出刀; doAttack 瞄准射线打到谁就打谁; attackEntity 出刀瞬间
 * 临时转向 + gameMode.attack。目标 HUD 未移植 (按要求排除)。
 */
public class KillAura extends Module {
    public static KillAura INSTANCE;
    public static Entity target;
    public static Entity aimingTarget;
    public static List<Entity> targetList = new ArrayList<>();

    private static final double MAX_REACH = 3.0;

    private static final ResourceLocation NURIK_CAPTURE_TEXTURE = ResourceLocation.tryParse("nilore:nurik/capture");
    private static final String NURIK_CAPTURE_ASSET = "/assets/nilore/nurik/capture.png";
    private static boolean nurikTextureLoaded;
    private static boolean nurikTextureLoadFailed;

    public final BooleanSetting attackPlayer    = new BooleanSetting("Attack Player", true);
    public final BooleanSetting attackInvisible = new BooleanSetting("Attack Invisible", true);
    public final BooleanSetting attackAnimals   = new BooleanSetting("Attack Animals", false);
    public final BooleanSetting attackMobs      = new BooleanSetting("Attack Mobs", false);
    public final BooleanSetting multiAttack     = new BooleanSetting("Multi Attack", false);
    public final BooleanSetting infSwitch       = new BooleanSetting("Infinity Switch", false);
    public final BooleanSetting preferBaby      = new BooleanSetting("Prefer Baby", false);
    public final BooleanSetting morePart        = new BooleanSetting("More Particles", false);
    public final BooleanSetting circle         = new BooleanSetting("Circle", false);

    public final BooleanSetting keepSprint = new BooleanSetting("Keep Sprint", true);
    public final BooleanSetting fallOnly = new BooleanSetting("Only Falling Attack", true);
    public final BooleanSetting throughWalls    = new BooleanSetting("Through Walls", false);
    public final NumberSetting throughWallsRange = new NumberSetting("Through Walls Range", 3.0, 1.0, 6.0, 0.1,
            () -> (Boolean) this.throughWalls.getValue());
    public final BooleanSetting ignoreSkipTicks = new BooleanSetting("Ignore skip ticks", false);
    public final BooleanSetting fakeAutoBlock   = new BooleanSetting("Fake AutoBlock", false);

    public final NumberSetting reach       = new NumberSetting("Reach", 3.0, 1.0, 4.0, 0.1);
    public final NumberSetting rotationSpeed = new NumberSetting("Rotation Speed", 180.0, 1.0, 180.0, 1.0);
    public final NumberSetting maxAps      = new NumberSetting("Max APS", 9, 1.0, 20.0, 1.0);
    public final NumberSetting minAps      = new NumberSetting("Min APS", 8, 1.0, 20.0, 1.0);
    public final NumberSetting switchSize  = new NumberSetting("Switch Size", 1.0, 1.0, 5.0, 1.0,
            () -> !(Boolean) this.infSwitch.getValue());
    public final NumberSetting switchDelay = new NumberSetting("Switch Delay (Attack Times)", 1.0, 1.0, 10.0, 1.0);
    public final NumberSetting fov         = new NumberSetting("FoV", 360.0, 10.0, 360.0, 1.0);
    public final NumberSetting hurtTime    = new NumberSetting("Hurt Time", 10.0, 0.0, 10.0, 1.0);
    public final ModeSetting delayMode    = new ModeSetting("Delay Mode", "1.8", "1.9").withDefault("1.8");

    public final ModeSetting targetMode   = new ModeSetting("Target Mode", "Single", "Switch", "Multiple").withDefault("Single");
    public final ModeSetting priorityMode = new ModeSetting("Priority", "Distance", "FoV", "Health", "LivingTime", "Armor", "None").withDefault("FoV");
    public final ModeSetting targetEsp    = new ModeSetting("Target ESP", "None", "Spiral", "Box", "Tab", "NurikZapen").withDefault("Spiral");

    private int attackTimes;
    private float attacks;
    private int targetIndex;
    private boolean bypassKS;
    public int sprintTickCounter;
    /** RotationHandler 每 tick 拾取的瞄准朝向 (Naven: RotationManager.rotations)。 */
    public Rotation rotation;

    public KillAura() {
        super("KillAura", Category.COMBAT);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.rotation = null;
        this.targetIndex = 0;
        this.attackTimes = 0;
        this.attacks = 0.0f;
        target = null;
        aimingTarget = null;
        targetList.clear();
        this.bypassKS = true;
        super.onEnable();
    }

    @Override
    public void onDisable() {
        this.attacks = 0.0f;
        target = null;
        aimingTarget = null;
        this.sprintTickCounter = 0;
        this.attackTimes = 0;
        this.rotation = null;
        this.bypassKS = true;
        super.onDisable();
    }

    // ===== Naven 引擎: 目标与朝向 (onMotion PRE) =====

    @EventTarget
    public void onTick(TickEvent event) {
        if (!NiloreClient.isReady()) {
            return;
        }
        double reach = Mth.clamp(this.reach.getValue().doubleValue(),
                this.reach.getMin().doubleValue(),
                this.reach.getMax().doubleValue());
        reach = Math.min(reach, MAX_REACH);
        RotationUtil.setReachLimit(reach);
        if (mc.screen instanceof AbstractContainerScreen
                || ItemUtil.hasServerItem()
                || (Scaffold.INSTANCE != null && Scaffold.INSTANCE.isEnabled())
                || (Stuck.INSTANCE != null && Stuck.INSTANCE.isEnabled())
                || (Helper.INSTANCE != null && Helper.INSTANCE.isEnabled() && Helper.targetRotation != null)
                || AntiWeb.targetRotation != null
                || AntiTNT.targetRotation != null
                || MidPearl.targetRotation != null
                || this.isWebPlacing()) {
            target = null;
            aimingTarget = null;
            this.rotation = null;
            targetList.clear();
            this.sprintTickCounter = 0;
            this.attacks = 0.0f;
            this.bypassKS = true;
            return;
        }

        boolean isSwitch = this.switchSize.getValue().intValue() > 1
                || this.infSwitch.getValue()
                || this.multiAttack.getValue()
                || !this.targetMode.is("Single");

        // Naven: updateAttackTargets() → targets = getTargets()
        this.updateAttackTargets();
        // Naven: aimingTarget = shouldPreAim()
        aimingTarget = this.shouldPreAim();

        // Naven: rotationData = getRotationDataToEntity(aimingTarget); rotation = data.getRotation()
        this.rotation = this.aimingTarget != null ? this.aimRotationTo(this.aimingTarget) : null;

        if (targetList.isEmpty()) {
            target = null;
            this.bypassKS = true;
            return;
        }

        // Naven: 目标切换 (attackTimes 到 switchDelay 或超出 3.0 距离时换目标)
        if (this.targetIndex > targetList.size() - 1) {
            this.targetIndex = 0;
        }
        if (targetList.size() > 1
                && this.targetMode.is("Switch")
                && (this.attackTimes >= this.switchDelay.getValue().intValue()
                || (this.attackTimes >= 1 && !RotationUtil.isWithinReach(aimingTarget)))) {
            this.attackTimes = 0;
            for (int i = 0; i < targetList.size(); ++i) {
                ++this.targetIndex;
                if (this.targetIndex > targetList.size() - 1) {
                    this.targetIndex = 0;
                }
                if (RotationUtil.isWithinReach(targetList.get(this.targetIndex))) {
                    break;
                }
            }
        }
        if (this.targetIndex > targetList.size() - 1 || !isSwitch) {
            this.targetIndex = 0;
        }
        target = targetList.get(this.targetIndex);

        // Naven: attacks += aps / 20 (蓄力)
        this.attacks = Math.min(this.attacks + (float) (MathUtil.randomDouble(
                this.minAps.getValue().doubleValue(), this.maxAps.getValue().doubleValue()) / 20.0), 2.0f);
    }

    // ===== Naven 引擎: 出刀 (onClick) =====

    @EventTarget
    public void onPreMotion(PreMotionEvent event) {
        if (!NiloreClient.isReady()) {
            return;
        }
        this.attackTick();
    }

    private void attackTick() {
        if (mc.player == null || this.isWebPlacing()) {
            if (mc.player != null) {
                this.attacks = 0.0f;
            }
            return;
        }
        // Naven onClick 闸门: 用物品为空 + 无界面 + 无 lag + 1.9 冷却; 外加 NoXZ 互斥。
        if (mc.player.getUseItem().isEmpty()
                && mc.screen == null
                && (this.ignoreSkipTicks.getValue() || ClientBase.delayPackets.isEmpty())
                && !NoXZMode.isCountering()) {
            if (this.delayMode.is("1.9") && mc.player.getAttackStrengthScale(0.0f) < 0.95f) {
                return;
            }
            if (this.holdAttackForFall()) {
                return;
            }
            while (this.attacks >= 1.0f) {
                if (this.shouldDropSprintForCrit()) {
                    this.stopSprint();
                    return;
                }
                this.doAttack();
                this.attacks -= 1.0f;
            }
        }
    }

    /** Naven doAttack: 瞄准射线打到谁就打谁; 多目标时射线打得到的都打 (上限 2)。 */
    public void doAttack() {
        if (targetList.isEmpty() || mc.player == null || mc.level == null) {
            return;
        }
        Rotation aim = this.rotation != null ? this.rotation : this.attackRotation();
        Entity hit = this.aimRayTarget(aim);

        if (hit != null && AntiBots.isBot(hit)) {
            return;
        }

        if (this.multiAttack.getValue() || this.targetMode.is("Multiple")) {
            int limit = this.targetMode.is("Multiple") ? Integer.MAX_VALUE : 2;
            int attacked = 0;
            for (Entity entity : targetList) {
                if (mc.player == null) {
                    break;
                }
                if (!RotationUtil.isLookingAt(entity, MAX_REACH, 0.3f, aim)
                        && !(this.throughWalls.getValue() && RotationUtil.isWithinReach(entity))) {
                    continue;
                }
                this.attackEntity(entity);
                if (++attacked >= limit) {
                    break;
                }
            }
        } else if (hit != null) {
            this.attackEntity(hit);
        }
    }

    /** Naven 的 hitResult.getEntity() 在 silent aim 下的等价: 沿瞄准朝向做射线取命中实体。 */
    private Entity aimRayTarget(Rotation aim) {
        if (mc.player == null || mc.level == null) {
            return null;
        }
        HitResult result = RotationUtil.rayTraceForAim(aim, MAX_REACH);
        if (result instanceof EntityHitResult entityHit) {
            Entity hit = entityHit.getEntity();
            return hit != null && this.isValidTarget(hit) ? hit : null;
        }
        return null;
    }

    /**
     * Naven getRotationDataToEntity: stable aim-point pick (LinkedHashSet dedup: center first,
     * then closest point, then a 0.1 grid over the six faces). The first point whose rotation
     * ray hits the target wins, so the aim point does not hop around between ticks.
     */
    private Rotation aimRotationTo(Entity target) {
        if (mc.player == null) {
            return null;
        }
        Vec3 eye = mc.player.getEyePosition();
        AABB box = target.getBoundingBox();
        java.util.LinkedHashSet<Vec3> points = new java.util.LinkedHashSet<>();
        points.add(new Vec3((box.minX + box.maxX) / 2.0, (box.minY + box.maxY) / 2.0, (box.minZ + box.maxZ) / 2.0));
        points.add(RotationUtil.closestPoint(eye, box));

        double spacing = 0.1;
        for (double x = box.minX; x <= box.maxX; x += spacing) {
            for (double y = box.minY; y <= box.maxY; y += spacing) {
                points.add(new Vec3(x, y, box.minZ));
                points.add(new Vec3(x, y, box.maxZ));
            }
        }
        for (double x = box.minX; x <= box.maxX; x += spacing) {
            for (double z = box.minZ; z <= box.maxZ; z += spacing) {
                points.add(new Vec3(x, box.minY, z));
                points.add(new Vec3(x, box.maxY, z));
            }
        }
        for (double y = box.minY; y <= box.maxY; y += spacing) {
            for (double z = box.minZ; z <= box.maxZ; z += spacing) {
                points.add(new Vec3(box.minX, y, z));
                points.add(new Vec3(box.maxX, y, z));
            }
        }

        for (Vec3 point : points) {
            Rotation brute = RotationUtil.rotationToPoint(eye, point);
            HitResult hit = RotationUtil.rayTraceForAim(brute, MAX_REACH);
            if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() == target) {
                return this.fixRotation(brute);
            }
        }
        return null;
    }

    /**
     * Naven getFixedRotation: snap the delta from the last SENT rotation onto the mouse
     * sensitivity GCD lattice. Real mouse input lands on this grid; that quantisation, not
     * interpolation, is what makes Naven's aim smooth instead of twitchy.
     */
    private Rotation fixRotation(Rotation raw) {
        Rotation last = RotationHandler.sentRotation != null
                ? RotationHandler.sentRotation
                : new Rotation(mc.player.getYRot(), mc.player.getXRot());
        float f = (float) (mc.options.sensitivity().get().doubleValue() * 0.6 + 0.2);
        float gcd = f * f * f * 1.2f;
        float deltaYaw = Mth.wrapDegrees(raw.getYaw() - last.getYaw());
        float deltaPitch = raw.getPitch() - last.getPitch();
        double maxStep = this.rotationSpeed.getValue().doubleValue();
        double dist = Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);
        if (maxStep > 0.0 && dist > maxStep) {
            float k = (float) (maxStep / dist);
            deltaYaw *= k;
            deltaPitch *= k;
        }
        return new Rotation(last.getYaw() + (deltaYaw - deltaYaw % gcd),
                last.getPitch() + (deltaPitch - deltaPitch % gcd));
    }

    /** Naven attackEntity: 出刀瞬间临时面向瞄准方向, gameMode.attack + 挥手 + 粒子。 */
    public boolean attackEntity(Entity entity) {
        if (mc.player == null || mc.getConnection() == null || mc.gameMode == null) {
            return false;
        }
        if (NoXZMode.isCountering()) {
            return false;
        }
        if (!attackAndLock()) {
            return false;
        }

        ++this.attackTimes;
        this.bypassKS = false;
        float currentYaw = mc.player.getYRot();
        float currentPitch = mc.player.getXRot();
        Rotation aim = this.rotation != null ? this.rotation : this.attackRotation();
        mc.player.setYRot(aim.getYaw());
        mc.player.setXRot(aim.getPitch());
        try {
            mc.gameMode.attack(mc.player, entity);
            mc.player.swing(InteractionHand.MAIN_HAND);
            if (this.morePart.getValue()) {
                mc.player.magicCrit(entity);
                mc.player.crit(entity);
            }
        } finally {
            mc.player.setYRot(currentYaw);
            mc.player.setXRot(currentPitch);
        }
        return true;
    }

    /** 服务端此刻持有的朝向 (兜底用)。 */
    private Rotation attackRotation() {
        if (RotationHandler.sentRotation != null) {
            return RotationHandler.sentRotation;
        }
        return new Rotation(mc.player.getYRot(), mc.player.getXRot());
    }

    // ===== Naven 引擎: 目标筛选 =====

    /** Naven shouldPreAim: 已有目标优先, 否则列表头。 */
    private Entity shouldPreAim() {
        Entity t = target;
        if (t == null && !targetList.isEmpty()) {
            t = targetList.get(0);
        }
        return t;
    }

    private void updateAttackTargets() {
        targetList = this.getTargets();
    }

    public boolean isValidTarget(Entity entity) {
        if (!NiloreClient.isReady()) return false;
        if (entity == mc.player) return false;
        if (entity instanceof LivingEntity livingEntity) {
            AntiBots antiBots = AntiBots.INSTANCE;
            if (antiBots != null && antiBots.isEnabled() && (AntiBots.isBot(entity) || AntiBots.isBedWarsBot(entity))) {
                return false;
            }
            if (livingEntity.isDeadOrDying() || livingEntity.getHealth() <= 0.0f) return false;
            if (entity instanceof ArmorStand) return false;
            if (entity.isInvisible() && !(Boolean) this.attackInvisible.getValue()) return false;
            if (Teams.isSameTeam(entity)) return false;
            if (entity instanceof Player && !(Boolean) this.attackPlayer.getValue()) return false;
            if (entity instanceof Player && (entity.getBbWidth() < 0.5 || livingEntity.isSleeping())) return false;
            if ((entity instanceof Mob || entity instanceof Slime || entity instanceof Bat || entity instanceof AbstractGolem)
                    && !(Boolean) this.attackMobs.getValue()) {
                return false;
            }
            if ((entity instanceof Animal || entity instanceof Squid) && !(Boolean) this.attackAnimals.getValue()) {
                return false;
            }
            if (entity instanceof Villager && !(Boolean) this.attackAnimals.getValue()) return false;
            return !(entity instanceof Player) || !entity.isSpectator();
        }
        return false;
    }

    /** Naven isValidAttack: isValidTarget + hurtTime 让刀 + 最近点距离 ≤ 范围 + FoV。 */
    public boolean isValidAttack(Entity entity) {
        if (mc.player == null || !this.isValidTarget(entity)) {
            return false;
        }
        if (entity instanceof LivingEntity living
                && (float) living.hurtTime > this.hurtTime.getValue().floatValue()) {
            return false;
        }
        Vec3 eye = mc.player.getEyePosition();
        if (RotationUtil.closestPoint(eye, entity.getBoundingBox()).distanceTo(eye)
                > this.reach.getValue().doubleValue()) {
            return false;
        }
        return RotationUtil.isEntityInFov(entity, this.fov.getValue().doubleValue());
    }

    /** Naven getTargets: 并发扫描 + 过滤 + 优先级排序 + Baby 偏好 + 末影水晶优先 + 切换上限。 */
    private List<Entity> getTargets() {
        if (mc.player == null || mc.level == null) {
            return new ArrayList<>();
        }
        List<Entity> possibleTargets = StreamSupport.stream(mc.level.entitiesForRendering().spliterator(), true)
                .filter(this::isValidAttack)
                .collect(Collectors.toList());

        if (possibleTargets.isEmpty()) {
            return possibleTargets;
        }
        if (possibleTargets.size() > 1) {
            if (this.priorityMode.is("Distance")) {
                possibleTargets.sort(BY_DISTANCE);
            } else if (this.priorityMode.is("FoV")) {
                possibleTargets.sort(BY_ANGLE);
            } else if (this.priorityMode.is("Health")) {
                possibleTargets.sort(BY_HEALTH);
            } else if (this.priorityMode.is("LivingTime")) {
                possibleTargets.sort(BY_LIVING_TIME);
            } else if (this.priorityMode.is("Armor")) {
                possibleTargets.sort(BY_ARMOR);
            }
            if (this.preferBaby.getValue() && KillAura.hasBaby(possibleTargets)) {
                possibleTargets.removeIf(KillAura::isNotBaby);
            }
            possibleTargets.sort(BY_CRYSTAL_PRIORITY);
        }
        if (this.infSwitch.getValue() || this.targetMode.is("Multiple")) {
            return possibleTargets;
        }
        int limit = (int) Math.min(possibleTargets.size(), this.switchSize.getValue().intValue());
        return new ArrayList<>(possibleTargets.subList(0, limit));
    }

    private boolean isWebPlacing() {
        return AutoWebPlace.INSTANCE != null && AutoWebPlace.INSTANCE.isEnabled() && AutoWebPlace.targetRotation != null;
    }

    // ===== 外部 API (NoXZMode / Sprint / RotationHandler 依赖) =====

    /** One attack per client tick, shared by the aura and NoXZMode's counter. */
    public static boolean attackAndLock() {
        long tick = mc.level != null ? mc.level.getGameTime()
                : (mc.player != null ? mc.player.tickCount : 0);
        if (tick == attackLockTick) {
            return false;
        }
        attackLockTick = tick;
        return true;
    }

    private static long attackLockTick = -1L;

    public static boolean shouldKeepSprintInDelayWindow() {
        if (mc.player == null || mc.level == null) return false;
        if (mc.player.onGround() || NoXZMode.isCountering()) return true;
        if (mc.player.getDeltaMovement().y > 0 || mc.player.fallDistance <= 0.0f) return false;
        return new MotionSimulator(mc.player).findLandingBlock(1) != null;
    }

    private boolean canAttackTarget() {
        return target != null && mc.player != null
                && target.getBoundingBox().distanceToSqr(mc.player.position()) <= 12.25
                && (this.throughWalls.getValue() || mc.player.hasLineOfSight(target));
    }

    private boolean shouldDropSprintForCrit() {
        if (!this.keepSprint.getValue() || this.bypassKS || mc.player == null || !mc.player.isSprinting()) {
            return false;
        }
        if (NoXZMode.isInDelayWindow()) {
            return false;
        }
        if (AntiKB.INSTANCE != null && AntiKB.INSTANCE.isEnabled()
                && AntiKB.mode.is("Jump Reset") && JumpResetMode.isJumping) {
            return false;
        }
        if (mc.player.onGround() && mc.options.keyJump.isDown()) {
            return false;
        }
        Rotation aim = this.rotation != null ? this.rotation : this.attackRotation();
        return this.aimRayTarget(aim) != null;
    }

    private void stopSprint() {
        if (mc.player == null) {
            return;
        }
        mc.player.setSprinting(false);
        mc.options.keySprint.setDown(false);
    }

    private boolean isFallingForCrit() {
        if (mc.player == null) {
            return false;
        }
        if (mc.player.onGround() || mc.player.onClimbable() || mc.player.isInWater()
                || mc.player.isInLava() || mc.player.isPassenger()) {
            return false;
        }
        return mc.player.fallDistance > 0.0f && mc.player.getDeltaMovement().y < 0.0;
    }

    private boolean holdAttackForFall() {
        if (mc.player == null) {
            return false;
        }
        if (!this.fallOnly.getValue() || mc.player.onGround() || this.target == null) {
            return false;
        }
        return !this.isFallingForCrit();
    }

    @EventTarget
    public void onAttackSlowdown(EventAttackSlowdown event) {
        if (this.keepSprint.getValue()) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onWorldChange(WorldChangeEvent event) {
        target = null;
        aimingTarget = null;
        this.attacks = 0.0f;
        this.setEnabled(false);
    }

    // ===== Target ESP (保留, 与引擎无关) =====

    @EventTarget
    public void onRender(RenderEvent event) {
        if (this.targetEsp.is("None")) return;
        Entity entity = aimingTarget;
        if (entity == null || mc.gameRenderer == null) return;
        PoseStack poseStack = event.poseStack();
        poseStack.pushPose();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        poseStack.translate(-cameraPos.x(), -cameraPos.y(), -cameraPos.z());

        double dx = entity.getX() - entity.xOld;
        double dy = entity.getY() - entity.yOld;
        double dz = entity.getZ() - entity.zOld;
        Vec3 playerDelta = mc.player.getDeltaMovement();
        Vec3 offset = new Vec3(
                dx + playerDelta.x + 0.005,
                dy + playerDelta.y - 0.002,
                dz + playerDelta.z + 0.005);

        String mode = this.targetEsp.getValue();
        switch (mode) {
            case "Spiral" -> RenderUtil.drawSpiralEffect(poseStack, entity, event.partialTick());
            case "Box" -> {
                int hurtTime = entity instanceof LivingEntity le ? le.hurtTime : 0;
                Color color;
                if (hurtTime == 0) {
                    color = new Color(0, 0, 0, 130);
                } else if (hurtTime >= 9 && hurtTime <= 10) {
                    color = new Color(0, 255, 255, 200);
                } else {
                    color = new Color(255, 0, 0, 200);
                }
                AABB base = EntityUtil.getInterpolatedAABB(entity, event.partialTick()).move(offset);
                AABB padded = new AABB(
                        base.minX - 0.175, base.minY - 0.125, base.minZ - 0.175,
                        base.maxX + 0.175, base.maxY + 0.225, base.maxZ + 0.175);
                RenderUtil.drawFilledColoredBox(padded, poseStack, color, color);
            }
            case "Tab" -> {
                int hurtTime = entity instanceof LivingEntity le ? le.hurtTime : 0;
                Color color;
                if (hurtTime == 0) {
                    color = new Color(0, 0, 0, 130);
                } else if (hurtTime == 3) {
                    color = new Color(255, 255, 255, 200);
                } else {
                    color = new Color(255, 0, 0, 200);
                }
                AABB base = EntityUtil.getInterpolatedAABB(entity, event.partialTick()).move(offset);
                AABB band = new AABB(
                        base.minX, base.minY + entity.getEyeHeight() + 0.11, base.minZ,
                        base.maxX, base.maxY - 0.13, base.maxZ);
                RenderUtil.drawFilledColoredBox(band, poseStack, color, color);
            }
            case "NurikZapen" -> this.renderNurikZapen(poseStack, entity, event.partialTick());
            default -> {
            }
        }
        poseStack.popPose();
    }

    private static void ensureNurikCaptureTexture() {
        if (nurikTextureLoaded || nurikTextureLoadFailed) {
            return;
        }
        try (InputStream inputStream = Assets.open(NURIK_CAPTURE_ASSET)) {
            if (inputStream == null) {
                nurikTextureLoadFailed = true;
                System.out.println("KillAura: NurikZapen texture not found - " + NURIK_CAPTURE_ASSET);
                return;
            }
            mc.getTextureManager().register(NURIK_CAPTURE_TEXTURE, new DynamicTexture(NativeImage.read(inputStream)));
            nurikTextureLoaded = true;
        } catch (IOException exception) {
            nurikTextureLoadFailed = true;
            System.out.println("KillAura: failed to load NurikZapen texture - " + exception.getMessage());
        }
    }

    private void renderNurikZapen(PoseStack poseStack, Entity entity, float partialTick) {
        ensureNurikCaptureTexture();
        if (!nurikTextureLoaded) {
            return;
        }

        double x = Mth.lerp(partialTick, entity.xOld, entity.getX());
        double y = Mth.lerp(partialTick, entity.yOld, entity.getY()) + entity.getEyeHeight() * 0.5f;
        double z = Mth.lerp(partialTick, entity.zOld, entity.getZ());
        Camera camera = mc.gameRenderer.getMainCamera();

        poseStack.pushPose();
        try {
            poseStack.translate(x, y, z);
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0f - camera.getYRot()));
            poseStack.mulPose(Axis.XP.rotationDegrees(-camera.getXRot()));
            poseStack.mulPose(Axis.ZP.rotationDegrees((float) ((System.currentTimeMillis() / 5.0) % 360.0)));

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, NURIK_CAPTURE_TEXTURE);

            ModuleListHud moduleList = NiloreClient.getInstance().getHudManager().getHudElement(ModuleListHud.class);
            int[] colors = moduleList == null
                    ? new int[]{0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF}
                    : new int[]{
                            moduleList.getThemeColor(0, 0.0f, 3),
                            moduleList.getThemeColor(1, 0.33f, 3),
                            moduleList.getThemeColor(2, 0.67f, 3),
                            moduleList.getThemeColor(3, 1.0f, 3)
                    };
            float size = 0.75f;
            float[][] corners = {
                    {-size, size, 0.0f, 0.0f},
                    {size, size, 1.0f, 0.0f},
                    {size, -size, 1.0f, 1.0f},
                    {-size, -size, 0.0f, 1.0f}
            };
            Matrix4f matrix = poseStack.last().pose();
            BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
            bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (int i = 0; i < corners.length; i++) {
                int color = colors[i];
                bufferBuilder.vertex(matrix, corners[i][0], corners[i][1], 0.0f)
                        .uv(corners[i][2], corners[i][3])
                        .color((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, 200)
                        .endVertex();
            }
            BufferUploader.drawWithShader(bufferBuilder.end());
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            poseStack.popPose();
        }
    }

    // ===== 排序器 =====

    private static final Comparator<Entity> BY_DISTANCE = Comparator.comparingDouble(KillAura::getDistanceToPlayer);
    private static final Comparator<Entity> BY_ANGLE = Comparator.comparingDouble(KillAura::getAngleDiffToTarget);
    private static final Comparator<Entity> BY_HEALTH = Comparator.comparingDouble(KillAura::getEntityHealth);
    private static final Comparator<Entity> BY_LIVING_TIME = Comparator.comparingInt(KillAura::getEntityLivingTime).reversed();
    private static final Comparator<Entity> BY_ARMOR = Comparator.comparingDouble(KillAura::getEntityArmor);
    private static final Comparator<Entity> BY_CRYSTAL_PRIORITY = Comparator.comparing(KillAura::getCrystalPriority);

    private static Integer getCrystalPriority(Entity entity) {
        return entity instanceof EndCrystal ? 0 : 1;
    }

    private static boolean isNotBaby(Entity entity) {
        return !(entity instanceof LivingEntity) || !((LivingEntity) entity).isBaby();
    }

    private static boolean hasBaby(List<Entity> entities) {
        for (Entity entity : entities) {
            if (entity instanceof LivingEntity living && living.isBaby()) {
                return true;
            }
        }
        return false;
    }

    private static double getEntityHealth(Entity entity) {
        if (entity instanceof LivingEntity le) {
            return le.getHealth() + le.getAbsorptionAmount();
        }
        return 0.0;
    }

    private static int getEntityLivingTime(Entity entity) {
        return entity.tickCount;
    }

    private static double getEntityArmor(Entity entity) {
        if (entity instanceof LivingEntity le) {
            return le.getArmorValue();
        }
        return 0.0;
    }

    private static double getAngleDiffToTarget(Entity entity) {
        return RotationUtil.angleDiff(mc.player.getYRot(), RotationUtil.entityRotation(entity).getYaw());
    }

    private static double getDistanceToPlayer(Entity entity) {
        return entity.distanceTo(mc.player);
    }
}
