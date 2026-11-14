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
import client.nilore.event.impl.SprintEvent;
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

    /** 未开 Rage 时的硬上限, 与上游一致。 */
    private static final double MAX_REACH = 3.0;

    /**
     * Rage 下允许的最大攻击距离, 与 Reach 滑条上限对齐。
     *
     * <p>上游把 {@code MAX_REACH = 3.0} 当硬天花板: {@code onTick} 里
     * {@code reach = Math.min(reach, MAX_REACH)} 会把滑条拉到 6 也钳回 3 ——
     * 也就是说 Reach 这个设置项在 3.0 以上的部分从来没生效过。</p>
     */
    private static final double MAX_REACH_RAGE = 6.0;

    /** 本模块当前允许的最大攻击距离。 */
    private double maxReach() {
        return this.rage.getValue() ? MAX_REACH_RAGE : MAX_REACH;
    }

    /**
     * 实际可打距离: Rage + Through Walls 时吃满 Through Walls Range, 否则就是 Reach。
     *
     * <p>{@code throughWallsRange} 在上游只声明、从未被读取, 这里把它接上。</p>
     */
    private double attackRange() {
        double range = Mth.clamp(this.reach.getValue().doubleValue(),
                this.reach.getMin().doubleValue(),
                this.reach.getMax().doubleValue());
        if (this.rage.getValue() && this.throughWalls.getValue()) {
            range = Math.max(range, this.throughWallsRange.getValue().doubleValue());
        }
        return Math.min(range, this.maxReach());
    }

    /**
     * Rage 的 APS 爬坡系数。
     *
     * <p>一开光环就满 APS 是服务端滑动窗口最容易抓的形态。前 {@code Rage Ramp} 拍
     * 按 55% → 100% 线性放大, 爬完恒为 1.0。</p>
     */
    private double apsRampScale() {
        int ramp = this.apsRampTicks.getValue().intValue();
        if (ramp <= 0 || this.rampTicks >= ramp) {
            return 1.0;
        }
        return 0.55 + 0.45 * (this.rampTicks / (double) ramp);
    }

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

    public final BooleanSetting keepSprint = new BooleanSetting("Keep Sprint", true);
    public final BooleanSetting fallOnly = new BooleanSetting("Only Falling Attack", true);
    public final BooleanSetting throughWalls    = new BooleanSetting("Through Walls", false);
    public final NumberSetting throughWallsRange = new NumberSetting("Through Walls Range", 3.0, 1.0, 8.0, 0.1,
            () -> (Boolean) this.throughWalls.getValue());
    public final BooleanSetting ignoreSkipTicks = new BooleanSetting("Ignore skip ticks", false);
    public final BooleanSetting fakeAutoBlock   = new BooleanSetting("Fake AutoBlock", false);

    public final NumberSetting reach       = new NumberSetting("Reach", 3.0, 1.0, 6.0, 0.1);
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

    /**
     * 暴力模式。开启后四处"会让光环等待"的门一起放开:
     * <ul>
     *   <li>keepSprint 门 —— 疾跑时照打, 不再等 Sprint 模块先掉疾跑换暴击</li>
     *   <li>1.9 蓄力门 —— 不等 getAttackStrengthScale 到 0.95</li>
     *   <li>fallOnly 门 —— 空中不必处于下落段</li>
     *   <li>目标数上限 —— 锁住 reach 内全部目标, 同拍逐个点名</li>
     * </ul>
     * <p>代价是反作弊面直接放大: 疾跑中的高 APS 命中是公开的 KS/aim 特征,
     * 同拍多目标在服务端看是"一秒内打了好几个人"。只在自己服或明确不查的环境开。</p>
     */
    public final BooleanSetting rage = new BooleanSetting("Rage Mode", false);

    /** Rage 模式下每拍最多打几个目标 (每个目标有独立的 hurtTime, 不会互相吞伤害)。 */
    public final NumberSetting rageTargets = new NumberSetting("Rage Targets", 3.0, 1.0, 8.0, 1.0,
            () -> this.rage.getValue());

    /** 启用后前 N 拍把 APS 从 55% 爬到 100%; 0 = 不爬坡。 */
    public final NumberSetting apsRampTicks = new NumberSetting("Rage Ramp", 60.0, 0.0, 200.0, 20.0,
            () -> this.rage.getValue());

    /** Rage 下只打 hurtTime 归零的目标 —— 打无敌帧等于白发一个 ATTACK 包。 */
    public final BooleanSetting rageHurtSync = new BooleanSetting("Rage Hurt Sync", true,
            () -> this.rage.getValue());

    /** Rage 下把 SWING 提到 ATTACK 之前 (只换顺序, 不额外加包)。 */
    public final BooleanSetting swingFirst = new BooleanSetting("Swing First", true,
            () -> this.rage.getValue());

    /** 启用后经过的拍数, 供 APS 爬坡使用 (onEnable 归零)。 */
    private int rampTicks;

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
        clearPause();
        this.rampTicks = 0;
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
        clearPause();
        setAttackBudget(1);
        super.onDisable();
    }

    // ===== Naven 引擎: 目标与朝向 (onMotion PRE) =====

    @EventTarget
    public void onTick(TickEvent event) {
        if (!NiloreClient.isReady()) {
            return;
        }
        // Rage + Through Walls 时上限抬到 Through Walls Range, 其余情况与上游一致。
        RotationUtil.setReachLimit(this.attackRange());
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
        // 停摆窗口内不向 RotationHandler 供朝向: 静默转向会把珍珠甩向目标而不是准心方向。
        this.rotation = this.aimingTarget != null && !isPaused()
                ? this.aimRotationTo(this.aimingTarget)
                : null;

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
        // Rage: 前 Rage Ramp 拍按爬坡系数压低 APS, 避免一开就满速的瞬时峰值。
        double apsScale = this.rage.getValue() ? this.apsRampScale() : 1.0;
        this.attacks = Math.min(this.attacks + (float) (MathUtil.randomDouble(
                this.minAps.getValue().doubleValue() * apsScale,
                this.maxAps.getValue().doubleValue() * apsScale) / 20.0), 2.0f);
        ++this.rampTicks;
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
        // 投掷停摆: 只吞掉这一拍的出刀, 模块保持启用, 蓄力照常累积, 窗口一过立刻继续打。
        if (isPaused()) {
            return;
        }
        // 出刀预算: 普通模式每拍一刀 (与上游一致); Rage 模式每拍最多 Rage Targets 刀,
        // 多出来的额度给 doAttack 的多目标点名用。
        setAttackBudget(this.rage.getValue()
                ? Math.max(1, this.rageTargets.getValue().intValue())
                : 1);
        // Naven onClick 闸门: 用物品为空 + 无界面 + 无 lag + 1.9 冷却; 外加 NoXZ 互斥。
        if (mc.player.getUseItem().isEmpty()
                && mc.screen == null
                && (this.ignoreSkipTicks.getValue() || ClientBase.delayPackets.isEmpty())
                && !NoXZMode.isCountering()) {
            // 基线的 keepSprint 门: 疾跑时 aura 不出手, 由 Sprint 模块先掉疾跑, 下一拍再打暴击。
            if (!this.rage.getValue() && this.keepSprintGate()) {
                this.attacks = 0.0f;
                return;
            }
            if (!this.rage.getValue() && this.delayMode.is("1.9")
                    && mc.player.getAttackStrengthScale(0.0f) < 0.95f) {
                return;
            }
            if (!this.rage.getValue() && this.holdAttackForFall()) {
                return;
            }
            while (this.attacks >= 1.0f) {
                this.doAttack();
                this.attacks -= 1.0f;
            }
        }
    }

    /**
     * 基线 {@code canAttackNow()} 里的 keepSprint 三道门: 命中任何一条, aura 这一拍就不出手。
     *
     * <p>{@code isInDelayWindow() && shouldKeepSprintInDelayWindow()} 是 keepsprint 与 antikb 的
     * 契约: 窗口还攥着输入时 aura 让开, 窗口自己的 {@code isSprinting()} 才不会被打破。
     * 最后一条是"疾跑时先不打" —— Sprint 模块每拍问一次 {@code canCritUnderVelocity()},
     * 成立就掉疾跑, 下一拍这里自然放行, 于是那一刀是暴击。
     */
    private boolean keepSprintGate() {
        if (NoXZMode.isInDelayWindow() && shouldKeepSprintInDelayWindow()) {
            return true;
        }
        return this.keepSprint.getValue()
                && mc.player != null
                && mc.player.isSprinting()
                && !NoXZMode.handlingVelocity;
    }

    /** Naven doAttack: 瞄准射线打到谁就打谁; 多目标时射线打得到的都打 (上限 2)。 */
    public void doAttack() {
        if (targetList.isEmpty() || mc.player == null || mc.level == null) {
            return;
        }
        // Rage: 不做视线判定, reach 内的目标逐个点名, 每拍最多 Rage Targets 个。
        // 多目标各自有独立的 hurtTime, 同一拍打不同人不会互相吞伤害。
        if (this.rage.getValue()) {
            Vec3 eye = mc.player.getEyePosition();
            int limit = Math.max(1, this.rageTargets.getValue().intValue());
            int attacked = 0;
            for (Entity entity : targetList) {
                if (mc.player == null || attacked >= limit) {
                    break;
                }
                if (AntiBots.isBot(entity)) {
                    continue;
                }
                // Hurt Sync: 目标还在无敌帧里, 这一刀发出去也不掉血, 只多一个 ATTACK 包。
                if (this.rageHurtSync.getValue()
                        && entity instanceof LivingEntity living
                        && living.hurtTime > 0) {
                    continue;
                }
                if (RotationUtil.closestPoint(eye, entity.getBoundingBox()).distanceTo(eye)
                        > this.attackRange()) {
                    continue;
                }
                if (this.attackEntity(entity)) {
                    ++attacked;
                }
            }
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
                if (!RotationUtil.isLookingAt(entity, this.maxReach(), 0.3f, aim)
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
        HitResult result = RotationUtil.rayTraceForAim(aim, this.maxReach());
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
            HitResult hit = RotationUtil.rayTraceForAim(brute, this.maxReach());
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
        // 停摆窗口也挡住外部调用者 (NoXZ 反击 / Critical): 它们同样会改朝向+发攻击包。
        if (isPaused()) {
            return false;
        }
        if (NoXZMode.isCountering()) {
            return false;
        }
        if (!attackAndLock()) {
            return false;
        }
        markAutomatedAttack();

        ++this.attackTimes;
        this.bypassKS = false;
        float currentYaw = mc.player.getYRot();
        float currentPitch = mc.player.getXRot();
        Rotation aim = this.rotation != null ? this.rotation : this.attackRotation();
        mc.player.setYRot(aim.getYaw());
        mc.player.setXRot(aim.getPitch());
        try {
            if (this.rage.getValue() && this.swingFirst.getValue()) {
                // 包序反转: SWING 先于 ATTACK。原版是 ATTACK → SWING, 统计型 check 会看到
                // "挥动永远落后于命中"。只换顺序, 不额外加包。
                mc.player.swing(InteractionHand.MAIN_HAND);
                mc.gameMode.attack(mc.player, entity);
            } else {
                mc.gameMode.attack(mc.player, entity);
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
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
                > this.attackRange()) {
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
        int limit = this.rage.getValue()
                ? Math.min(possibleTargets.size(), Math.max(1, this.rageTargets.getValue().intValue()))
                : (int) Math.min(possibleTargets.size(), this.switchSize.getValue().intValue());
        return new ArrayList<>(possibleTargets.subList(0, limit));
    }

    private boolean isWebPlacing() {
        return AutoWebPlace.INSTANCE != null && AutoWebPlace.INSTANCE.isEnabled() && AutoWebPlace.targetRotation != null;
    }

    // ===== 外部 API (NoXZMode / Sprint / RotationHandler 依赖) =====

    /**
     * 每拍出刀预算, 由 aura 每 tick 按模式写入: 普通模式 1, Rage 模式 = Rage Targets。
     *
     * <p>原来的实现是"每拍只放一次"的布尔锁, 于是 Rage 的多目标点名只有第一个目标能
     * 真正发出 ATTACK 包, 后面全被这把锁吞掉 —— 白扣 APS 却一个都不打, 用户看到的就是
     * "开了 Rage 反而不如单目标"。改成计数器后, 预算内每次出刀各扣一格。</p>
     *
     * <p>预算只在 tick 边界重置, 所以即使 {@code attackTick} 的 {@code while} 循环一拍
     * 跑了两轮, 总出刀数也不会超过预算 —— 这正是"每拍最多打几个人"的位置。</p>
     */
    private static long attackLockTick = -1L;
    private static int attackLockCount = 0;
    private static int attackLockBudget = 1;

    /** 设置每拍出刀预算; 1 表示恢复原版"每拍一刀"。 */
    public static void setAttackBudget(int budget) {
        attackLockBudget = Math.max(1, budget);
    }

    /** One attack per client tick by default, shared by the aura and NoXZMode's counter. */
    public static boolean attackAndLock() {
        long tick = currentAttackTick();
        if (tick != attackLockTick) {
            attackLockTick = tick;
            attackLockCount = 0;
        }
        if (attackLockCount >= attackLockBudget) {
            return false;
        }
        ++attackLockCount;
        return true;
    }

    // ===== 投掷瞬间停摆 (MidPearl 联动) =====

    /** 停摆到哪一拍为止 (gameTime 时间戳)。默认值保证未触发时永远返回 false。 */
    private static long pauseUntilTick = Long.MIN_VALUE;

    /**
     * 让光环停摆 {@code ticks} 拍。
     *
     * <p>MidPearl 原本在选中珍珠槽时直接 {@code setEnabled(false)}: 目标状态、APS 蓄力、
     * 朝向全部归零, 扔完还得手动开回来, 中间被人贴脸就是白挨一刀。现在只跳过投掷窗口
     * 的出刀与朝向接管, 模块本身保持启用, 窗口一过立刻继续打; 停摆期间它也不会把
     * MidPearl 刚选好的珍珠槽抢回方块。</p>
     *
     * <p>用 gameTime 打时间戳而不是递减计数器: {@code MidPearl.onTick} 与
     * {@code RotationHandler.onTickHigh} 跑在同一个 TickEvent 里, 递减法会依赖监听器
     * 注册顺序 (谁先跑谁的计数就少一次), 时间戳法在整拍内恒定, 顺序无关。</p>
     */
    public static void pauseFor(int ticks) {
        if (ticks <= 0) {
            return;
        }
        pauseUntilTick = Math.max(pauseUntilTick, currentTick() + ticks);
    }

    public static void clearPause() {
        pauseUntilTick = Long.MIN_VALUE;
    }

    /** 当前是否处在投掷停摆窗口。 */
    public static boolean isPaused() {
        return currentTick() < pauseUntilTick;
    }

    private static long currentTick() {
        if (mc.level != null) {
            return mc.level.getGameTime();
        }
        return mc.player != null ? mc.player.tickCount : 0L;
    }

    /**
     * 本 tick 由自动化(aura / NoXZ 反打)发起的那次 {@code Player.attack}。
     *
     * <p>{@code Player.attack} 是手动点击与 {@code gameMode.attack} 的公共出口, 两边的包
     * 一模一样 —— 没有这个标记时, patch 在入口无法区分"自动化刚占过锁"和"玩家又补了一次
     * 点击", 只能一律拒, 会把 aura 自己的出刀也吞掉。
     */
    private static long automatedAttackTick = -1L;
    /** 本拍还剩几张自动化通行证; Rage 一拍出多刀时按刀数发放。 */
    private static int automatedAttackMarks = 0;

    /** 自动化出刀前调用: 登记这一次 {@code Player.attack} 是自己发起的。 */
    public static void markAutomatedAttack() {
        long tick = currentAttackTick();
        if (tick != automatedAttackTick) {
            automatedAttackTick = tick;
            automatedAttackMarks = 0;
        }
        ++automatedAttackMarks;
    }

    /** 每张只消费一次; true 表示这次 {@code Player.attack} 来自自动化, 应当放行。 */
    public static boolean consumeAutomatedAttackMark() {
        if (automatedAttackTick == currentAttackTick() && automatedAttackMarks > 0) {
            --automatedAttackMarks;
            return true;
        }
        return false;
    }

    private static long currentAttackTick() {
        return mc.level != null ? mc.level.getGameTime()
                : (mc.player != null ? mc.player.tickCount : 0);
    }

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

    /**
     * 基线的暴击门: 成立时由 {@link #onSprint} 在这一拍把疾跑按掉, 下一拍 aura 才出刀。
     *
     * <p>之所以叫 "under velocity": antikb 正在处理击退(窗口/反打)时它一律不成立 ——
     * 那段时间是 NoXZ 在控制疾跑状态, 这里再动就会两个模块互相拆台。
     */
    public boolean canCritUnderVelocity() {
        if (mc.player == null || mc.level == null || target == null) {
            return false;
        }
        if (AntiKB.INSTANCE != null && AntiKB.INSTANCE.isEnabled()) {
            if (NoXZMode.isInDelayWindow() && shouldKeepSprintInDelayWindow()) {
                return false;
            }
            if (NoXZMode.isCountering()) {
                return false;
            }
            if (AntiKB.mode.is("Jump Reset") && JumpResetMode.isJumping) {
                return false;
            }
        }
        if (target.distanceToSqr(mc.player) > 12.25) {
            return false;
        }
        if (mc.player.isDeadOrDying() || !this.keepSprint.getValue()) {
            return false;
        }
        return !mc.player.isUsingItem() || !mc.options.keyUse.isDown();
    }

    /**
     * SprintEvent 紧挨在 {@code LocalPlayer.tick()} 调用 {@code super.tick()} 之前, 是唯一
     * 还能让 STOP_SPRINTING 排在攻击包前面的窗口。再晚一拍, 客户端已经用 +30% 的疾跑属性
     * 走完这一 tick 了。
     */
    @EventTarget
    public void onSprint(SprintEvent sprintEvent) {
        if (!this.canCritUnderVelocity()) {
            return;
        }
        mc.options.keySprint.setDown(false);
        if (mc.player != null && mc.player.isSprinting()) {
            mc.player.setSprinting(false);
        }
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
    public void onWorldChange(WorldChangeEvent event) {
        target = null;
        aimingTarget = null;
        this.attacks = 0.0f;
        // gameTime 换世界会归零, 不清掉时间戳可能让光环在新世界永久停摆。
        clearPause();
        setAttackBudget(1);
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
