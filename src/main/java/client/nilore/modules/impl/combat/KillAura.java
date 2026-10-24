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
import java.util.Random;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
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
import client.nilore.event.impl.PreMotionEvent;
import client.nilore.event.impl.RenderEvent;
import client.nilore.event.impl.SprintEvent;
import client.nilore.event.impl.StrafeEvent;
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
import client.nilore.utils.rotation.RotationSmoother;
import client.nilore.event.EventTarget;

public class KillAura extends Module {
    public static KillAura INSTANCE;
    public static Entity target;
    public static Entity aimingTarget;
    public static List<Entity> targetList = new ArrayList<>();

    /** Hard ceiling on everything reach related: the aura must never validate beyond 3.0 blocks. */
    private static final double MAX_REACH = 3.0;
    /** Per-tick pull of the organic aim offsets back toward the aim point (AR(1) decay). */
    private static final float ORGANIC_DECAY = 0.85f;

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
    public final BooleanSetting throughWalls    = new BooleanSetting("Through Walls", false);
    public final NumberSetting throughWallsRange = new NumberSetting("Through Walls Range", 3.0, 1.0, 6.0, 0.1,
            () -> (Boolean) this.throughWalls.getValue());
    public final BooleanSetting ignoreSkipTicks = new BooleanSetting("Ignore skip ticks", false);
    public final BooleanSetting fakeAutoBlock   = new BooleanSetting("Fake AutoBlock", false);

    public final NumberSetting reach       = new NumberSetting("Reach", 3.0, 1.0, 4.0, 0.1);
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

    public final NumberSetting rotationSpeed = new NumberSetting("Rotation Speed", 110, 1, 180, 1);

    public final NumberSetting rotationDrift = new NumberSetting("Drift", 0.3, 0, 3, 0.1);

    public final NumberSetting rotationJitter = new NumberSetting("Jitter", 1.3, 0, 3, 0.1);

    private int attackTimes;
    private float attacks;
    private int targetIndex;
    public int sprintTickCounter;
    public Rotation rotation;
    /** 本 tick 攻击使用的服务端朝向快照, 见 {@link #authoritativeAttackRotation()}。 */
    private Rotation authoritativeRotation;

    private Random organicRandom;
    private final RotationSmoother rotSmoother = new RotationSmoother(new Random());

    private float organicYawOffset;
    private float organicPitchOffset;

    public KillAura() {
        super("KillAura", Category.COMBAT);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.rotation = null;
        this.reinitOrganicModel();
        this.rotSmoother.reset();
        this.targetIndex = 0;
        this.attacks = 0.0f;
        target = null;
        aimingTarget = null;
        targetList.clear();
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
        super.onDisable();
    }

    private void reinitOrganicModel() {
        this.organicRandom = new Random(System.nanoTime());
        this.organicYawOffset = 0.0f;
        this.organicPitchOffset = 0.0f;
    }

    private Rotation computeRotation(Entity target, Rotation from, double speed, double range) {
        // The old eye-to-eye hasLineOfSight gate was stricter than the attack raycast: a target
        // whose eyes were hidden behind a slab or fence but whose body was exposed was dropped
        // entirely (no turn, no swing). Let findAimPoint's per-point raycast decide instead;
        // Through Walls keeps the occluded fallback.
        Vec3 aimPoint = RotationUtil.findAimPoint(target, range, 1.0f,
                this.throughWalls.getValue());
        if (aimPoint == null) {
            return null;
        }
        Rotation base = RotationUtil.rotationToPoint(mc.player.getEyePosition(), aimPoint);

        Rotation jittered = this.applyOrganicJitter(target, base, range);

        Rotation smoothed = this.rotSmoother.tick(from, jittered, speed);
        smoothed = RotationSmoother.patchConstantRotation(smoothed, from);
        // Grim DuplicateRotPlace / AimDuplicateLook 的唯一判据是"相邻两 tick 的增量相同"。
        // 平滑器收敛后每 tick 的输出会稳定在同一个量化值, 增量恒为 0 或同一个数; 这里
        // 沿格点挪一格: 网格与幅度都不变, 只是不再重复。
        smoothed = this.rotSmoother.breakDuplicateDelta(smoothed, from);
        if (smoothed == null
                || Float.isNaN(smoothed.getYaw()) || Float.isNaN(smoothed.getPitch())
                || Float.isInfinite(smoothed.getYaw()) || Float.isInfinite(smoothed.getPitch())) {
            return jittered;
        }
        return smoothed;
    }

    /**
     * 有机抖动: 两条轴都是零均值 AR(1) 过程, 衰减 {@link #ORGANIC_DECAY} 负责把偏移拉回
     * 瞄准点, 幅度由 Drift(yaw) / Jitter(pitch) 两个设置控制。
     *
     * <p>旧实现有两个毛病: pitch 的白噪声乘了 2 再被 clamp 到 1 倍幅度, 于是实际分布是
     * "一半时间贴着钳位边界"的偏置噪声; yaw 的钳位又是幅度的两倍, 长期停在极限值上 ——
     * 两条轴的统计特征完全不一致, 而且都不是正态的。现在两轴同一套形状, 只在幅度上不同。
     *
     * <p>抖动后必须仍然打得中: 打不中就先把偏移减半再试一次, 还不行才回落到无偏移的
     * 瞄准点。直接清零会让"靠判定盒边缘时抖动突然消失"变成一个可统计的开关。
     */
    private Rotation applyOrganicJitter(Entity target, Rotation base, double range) {
        double drift = this.rotationDrift.getValue().doubleValue();
        double jitter = this.rotationJitter.getValue().doubleValue();
        this.organicYawOffset = this.organicYawOffset * ORGANIC_DECAY
                + (float) ((this.organicRandom.nextDouble() - 0.5) * drift);
        this.organicPitchOffset = this.organicPitchOffset * ORGANIC_DECAY
                + (float) ((this.organicRandom.nextDouble() - 0.5) * jitter);
        float yawLimit = (float) drift;
        float pitchLimit = (float) jitter;
        this.organicYawOffset = Mth.clamp(this.organicYawOffset, -yawLimit, yawLimit);
        this.organicPitchOffset = Mth.clamp(this.organicPitchOffset, -pitchLimit, pitchLimit);

        Rotation full = this.offsetRotation(base, 1.0f);
        if (this.jitterHits(target, full, range)) {
            return full;
        }
        Rotation half = this.offsetRotation(base, 0.5f);
        if (this.jitterHits(target, half, range)) {
            // 偏移已被削减, 状态同步跟上, 免得下一 tick 又从满幅开始。
            this.organicYawOffset *= 0.5f;
            this.organicPitchOffset *= 0.5f;
            return half;
        }
        this.organicYawOffset = 0.0f;
        this.organicPitchOffset = 0.0f;
        return base;
    }

    private Rotation offsetRotation(Rotation base, float scale) {
        return new Rotation(base.getYaw() + this.organicYawOffset * scale,
                Mth.clamp(base.getPitch() + this.organicPitchOffset * scale, -90.0f, 90.0f));
    }

    private boolean jitterHits(Entity target, Rotation rotation, double range) {
        HitResult probe = RotationUtil.rayTraceForAim(rotation, range);
        return probe instanceof EntityHitResult hit && hit.getEntity() == target;
    }

    @EventTarget
    public void onWorldChange(WorldChangeEvent event) {
        target = null;
        aimingTarget = null;
        this.attacks = 0.0f;
        this.setEnabled(false);
    }

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
            return;
        }

        boolean isSwitch = this.switchSize.getValue().intValue() > 1
                || this.infSwitch.getValue()
                || this.multiAttack.getValue()
                || !this.targetMode.is("Single");
        this.updateTargets();
        // getTarget() falls back to getTargets() whenever the remembered target is null, so the
        // old `aimingTarget = getTarget()` right here re-ran the whole entity scan + four sorts
        // a second time every tick. Decide on the list we already have instead.
        if (targetList.isEmpty()) {
            target = null;
            aimingTarget = null;
            this.rotation = null;
            return;
        }
        // The switch decision below needs something to measure against before the index is
        // settled; use the list head rather than asking getTarget() for another scan.
        aimingTarget = targetList.get(Math.min(this.targetIndex, targetList.size() - 1));

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
        aimingTarget = this.getTarget();

        if (this.aimingTarget != null) {
            double speed = Mth.clamp(this.rotationSpeed.getValue().doubleValue(),
                    this.rotationSpeed.getMin().doubleValue(),
                    this.rotationSpeed.getMax().doubleValue());
            if (speed <= 0.0) {
                this.rotation = null;
            } else {
                Rotation from = RotationHandler.prevRotation != null
                        ? RotationHandler.prevRotation
                        : new Rotation(mc.player.getYRot(), mc.player.getXRot());
                this.rotation = this.computeRotation(this.aimingTarget, from, speed, MAX_REACH);
            }
        } else {
            this.rotation = null;
        }

        // The gate runs once per tick, inside attackTick at the tail of Minecraft.tick - after the
        // move packet, which is what the server validates against. Checking it here as well only
        // paid for a second block clip plus entity query per tick to get the same answer.
        float apsValue = this.maxAps.getValue().floatValue();
        float minApsValue = this.minAps.getValue().floatValue();
        this.attacks = Math.min(this.attacks + (float)(MathUtil.randomDouble(minApsValue, apsValue) / 20.0), 2.0f);
    }

    /**
     * 挥击挂在 {@code handleKeybinds} 里触发, 也就是**移动包之前** —— 和 vanilla 攻击同一个
     * 位置 ({@code Minecraft.handleKeybinds -> startAttack}, 之后才跑 {@code player.tick()}
     * 发 flying 包)。
     *
     * <p>之前挂在 {@code PostMotionEvent}({@code Minecraft.tick} 末尾)时, interact 包恒定排在
     * 移动包之后。Grim 的 {@code Post} 检查正是按这个顺序判定的: 收到移动包后把
     * {@code sentFlying} 置位, 之后收到的每个动作包(ATTACK / INTERACT_ENTITY / ...)都进
     * {@code post} 队列, 下一个 transaction 到达时若队列非空就记一条 —— 于是每打一次人记一次。
     *
     * <p>代价是判定用的朝向: 这个位置服务端还没收到本 tick 的朝向, 它持有的仍是上一 tick
     * 发出去的那个。所以下面的闸门也必须用同一个角度(见 {@link #attackRotation()}), 否则
     * 本地按新朝向算"打得到"、服务端按旧朝向算空挥, 就是 Reach/Hitboxes 抖动。
     */
    @EventTarget
    public void onPreMotion(PreMotionEvent event) {
        if (!NiloreClient.isReady()) {
            return;
        }
        this.attackTick();
    }

    /** 服务端此刻持有的朝向: 上一 tick 真正发出去的那个。 */
    private Rotation attackRotation() {
        if (RotationHandler.sentRotation != null) {
            return RotationHandler.sentRotation;
        }
        return new Rotation(mc.player.getYRot(), mc.player.getXRot());
    }

    /** 本 tick 攻击用的朝向; 在 {@link #attackTick()} 里取一次, 供闸门与多目标分支共用。 */
    private Rotation authoritativeAttackRotation() {
        return this.authoritativeRotation != null ? this.authoritativeRotation : this.attackRotation();
    }

    private void attackTick() {
        if (mc.player == null) return;
        if (this.isWebPlacing()) {
            this.attacks = 0.0f;
            return;
        }
        this.authoritativeRotation = this.attackRotation();
        if (!this.canAttackNow(this.authoritativeRotation)) {
            // A gate that closes for one tick must not throw away the charge built up over the
            // previous two: that turned every hitch into a full attack cycle of downtime. Cap it
            // instead, so the swing fires the moment the gate reopens.
            this.attacks = Math.min(this.attacks, 1.0f);
            return;
        }
        while (this.attacks >= 1.0f) {
            // Only a swing that actually left the client pays for itself. The per-tick lock can be
            // taken by the antikb counter or Critical first, and charging the cost anyway pushed
            // the accumulator negative - the next hit then had to make up the debt.
            if (!this.doAttack()) {
                break;
            }
            this.attacks -= 1.0f;
        }
    }

    /**
     * @param authoritative 服务端当前持有的朝向。视野闸门必须用它, 不能用本 tick 刚算出的
     *                      {@link #rotation} —— 后者还没发出去, 服务端不会用它校验这次交互。
     */
    private boolean canAttackNow(Rotation authoritative) {
        if (mc.player == null || mc.screen != null || mc.player.isUsingItem()) {
            return false;
        }
        if (target == null || !targetList.contains(target)) {
            return false;
        }
        double reachNow = Math.min(Math.max(this.reach.getValue().doubleValue(),
                RotationUtil.getReachLimit()), MAX_REACH);
        // Sight and distance now share one cap: the raycast used to allow the full reach while
        // isWithinReach already subtracted the margin, so a target sitting in that last strip passed
        // the visibility check and was then dropped by the distance gate every few ticks.
        double sightRange = reachNow - RotationUtil.REACH_MARGIN;
        // Through Walls only skips the block-clipped raycast inside Through Walls Range; beyond
        // it the attack still has to see the target, otherwise the range slider is never read.
        if (!this.ignoreBlocksAt(target) && !RotationUtil.isLookingAt(target, sightRange, 0.1f, authoritative)) {
            return false;
        }
        if (this.delayMode.is("1.9") && mc.player.getAttackStrengthScale(0.0f) < 0.95f) {
            return false;
        }
        if (!(this.ignoreSkipTicks.getValue() || ClientBase.delayPackets.isEmpty()
                || (Critical.INSTANCE != null && Critical.INSTANCE.isEnabled()))) {
            return false;
        }
        // Velocity gate. The counter hits are sprint hits by construction - the NoXZ counter only
        // swings while isSprinting() holds - so they cannot crit, and swinging the aura into the
        // same window would just fight it for the per-tick attack lock. The isInDelayWindow branch
        // is the keepsprint half of the contract: while the delay window still owns the input
        // (on ground, mid-counter, or about to land) the aura stays out so the window's own
        // isSprinting() gate stays true.
        if (NoXZMode.isCountering()) {
            return false;
        }
        if (NoXZMode.isInDelayWindow() && shouldKeepSprintInDelayWindow()) {
            return false;
        }
        if (this.keepSprint.getValue() && mc.player.isSprinting() && !NoXZMode.handlingVelocity) {
            return false;
        }
        return RotationUtil.isWithinReach(target);
    }

    /**
     * Whether Through Walls may skip the block-clipped raycast for this entity. Measured from
     * the eye to the closest point of the hitbox.
     */
    private boolean ignoreBlocksAt(Entity entity) {
        if (!this.throughWalls.getValue() || entity == null || mc.player == null) {
            return false;
        }
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        double dist = RotationUtil.closestPoint(eyePos, entity.getBoundingBox()).distanceTo(eyePos);
        return dist <= this.throughWallsRange.getValue().doubleValue();
    }

    /**
     * The crit gate. While it holds, the sprint key is dropped and the sprint flag cleared from the
     * SprintEvent handler, so the swing that follows lands as a crit instead of a sprint hit. The
     * 3.5-block reach (12.25 squared) and the velocity interlocks are what keep the release from
     * firing at the wrong time.
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
     * SprintEvent sits right before LocalPlayer.tick() calls super.tick(), which is the only window
     * where releasing the sprint still produces a STOP_SPRINTING packet before the attack. Later
     * than this the client has already moved with the +30% sprint attribute for the tick.
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

    /**
     * One attack per client tick, shared by the three paths that can swing: the aura itself, NoXZMode's
     * counter and Critical's extra hit. Whoever calls first in a tick wins, the rest are dropped, so the
     * same target can never eat two hits in one tick.
     *
     * <p>Keyed on the level's game time rather than {@code mc.player.tickCount}: Critical's SkipTicks
     * mode cancels LocalPlayer.tick outright, so the player's own counter stops advancing for the whole
     * fall and the lock stayed taken - every attack path was locked out until the player landed.
     */
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

    private boolean canAttackTarget() {
        Entity entity = target;
        if (entity == null || mc.player == null
                || entity.getBoundingBox().distanceToSqr(mc.player.position()) > 12.25) {
            return false;
        }
        return this.throughWalls.getValue() || mc.player.hasLineOfSight(entity);
    }

    public static boolean shouldKeepSprintInDelayWindow() {
        if (mc.player == null || mc.level == null) return false;
        if (mc.player.onGround() || NoXZMode.isCountering()) return true;
        if (mc.player.getDeltaMovement().y > 0 || mc.player.fallDistance <= 0.0f) return false;
        return new MotionSimulator(mc.player).findLandingBlock(1) != null;
    }

    public boolean doAttack() {
        if (this.isWebPlacing()) {
            this.attacks = 0.0f;
            return false;
        }
        if (targetList.isEmpty()) return false;
        if (this.rotation == null) return false;

        if (this.targetMode.is("Multiple") || this.multiAttack.getValue()) {
            int limit = this.targetMode.is("Multiple") ? Integer.MAX_VALUE : 2;
            int attacked = 0;
            Rotation authoritative = this.authoritativeAttackRotation();
            for (Entity entity : targetList) {
                if (mc.player == null) break;
                if (!RotationUtil.isWithinReach(entity)) continue;
                // 这条分支以前只判"任意角度可见"(canSeeAnyPoint 自己造射线, 与服务端无关),
                // 于是会出现"本地可见但服务端按当前朝向判空挥"的 Hitboxes 抖动。现在要求
                // 服务端此刻持有的朝向也真的打得到它; canSeeAnyPoint 只留作额外的可见性过滤。
                if (!this.ignoreBlocksAt(entity)) {
                    if (!RotationUtil.isLookingAt(entity, MAX_REACH, 0.2f, authoritative)) continue;
                    if (!RotationUtil.canSeeAnyPoint(entity)) continue;
                }
                if (this.attackEntity(entity)) {
                    attacked++;
                }
                if (attacked >= limit) break;
            }
            return attacked > 0;
        }
        return target != null && targetList.contains(target) && this.attackEntity(target);
    }

    public Entity getTarget() {
        Entity entity = target;
        if (entity == null) {
            List<Entity> list = this.getTargets();
            if (!list.isEmpty()) {
                entity = list.get(0);
            }
        }
        if (entity != null) {
            AntiBots antiBots = AntiBots.INSTANCE;
            if (antiBots != null && antiBots.isEnabled() && AntiBots.isBot(entity)) {
                return null;
            }
        }
        return entity;
    }

    public void updateTargets() {
        List<Entity> next = this.getTargets();
        targetList = next != null ? next : new ArrayList<>();
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

    public boolean isValidAttack(Entity entity) {
        if (mc.player == null) return false;
        return this.isValidTarget(entity);
    }

    public boolean attackEntity(Entity entity) {
        if (mc.player == null || mc.getConnection() == null) return false;
        if (this.isWebPlacing()) return false;
        if (!attackAndLock()) return false;

        ++this.attackTimes;
        mc.getConnection().send(ServerboundInteractPacket.createAttackPacket(entity, mc.player.isShiftKeyDown()));
        mc.player.attack(entity);
        mc.player.resetAttackStrengthTicker();
        mc.player.swing(InteractionHand.MAIN_HAND);

        if (this.morePart.getValue()) {
            mc.player.magicCrit(entity);
            mc.player.crit(entity);
        }
        return true;
    }

    private boolean isWebPlacing() {
        return AutoWebPlace.INSTANCE != null && AutoWebPlace.INSTANCE.isEnabled() && AutoWebPlace.targetRotation != null;
    }

    private List<Entity> getTargets() {
        if (mc.player == null || mc.level == null) {
            return new ArrayList<>();
        }
        // Gathering radius, not the hit radius: reach is enforced later (isLookingAt / isWithinReach),
        // this only decides who counts as "the opponent" for the sprint and crit machinery.
        AABB box = mc.player.getBoundingBox().inflate(MAX_REACH);
        // Plain loop, one pass, no stream: this runs every tick for the whole fight, and the old
        // version built a parallel-stream pipeline (ForkJoin tasks on the render thread), collected a
        // list, then made three more passes over it. Same filter set, same order (the sorts below are
        // stable), minus the machinery that costs the most while the JIT is still cold.
        double fov = this.fov.getValue().doubleValue();
        List<Entity> possibleTargets = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!box.intersects(entity.getBoundingBox())) continue;
            if (!this.isValidAttack(entity)) continue;
            // FoV filter, around the rotation the aura actually sends. The setting was read nowhere
            // before this, so raising or lowering it had no effect at all.
            if (!RotationUtil.isEntityInFov(entity, fov)) continue;
            possibleTargets.add(entity);
        }
        // Sorting is the expensive half of this method (three to four stable sorts per tick), and
        // with zero or one candidate every one of them is a no-op that still walks the comparator
        // machinery. The overwhelming majority of ticks in a real fight have exactly one candidate.
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
            // Final stable sort: an in-reach opponent becomes the target, out-of-reach ones only stay in
            // the list (ranked on the same 9.0 = 3.0^2). Inside each group the priority and
            // crystal order above survive - a stable sort keeps them as the secondary key.
            possibleTargets.sort(BY_OUT_OF_REACH);
        }
        if (this.infSwitch.getValue() || this.targetMode.is("Multiple")) {
            return possibleTargets;
        }
        int limit = (int) Math.min(possibleTargets.size(), this.switchSize.getValue().intValue());
        return new ArrayList<>(possibleTargets.subList(0, limit));
    }

    private static Integer getCrystalPriority(Entity entity) {
        return entity instanceof EndCrystal ? 0 : 1;
    }

    private static boolean isNotBaby(Entity entity) {
        return !(entity instanceof LivingEntity) || !((LivingEntity) entity).isBaby();
    }

    private static boolean isBaby(Entity entity) {
        return entity instanceof LivingEntity && ((LivingEntity) entity).isBaby();
    }

    private static boolean hasBaby(List<Entity> entities) {
        for (Entity entity : entities) {
            if (isBaby(entity)) {
                return true;
            }
        }
        return false;
    }

    // Built once instead of per tick: getTargets runs every tick, the old code allocated a fresh
    // comparator on each pass.
    private static final Comparator<Entity> BY_DISTANCE = Comparator.comparingDouble(KillAura::getDistanceToPlayer);
    private static final Comparator<Entity> BY_ANGLE = Comparator.comparingDouble(KillAura::getAngleDiffToTarget);
    private static final Comparator<Entity> BY_HEALTH = Comparator.comparingDouble(KillAura::getEntityHealth);
    private static final Comparator<Entity> BY_LIVING_TIME = Comparator.comparingInt(KillAura::getEntityLivingTime).reversed();
    private static final Comparator<Entity> BY_ARMOR = Comparator.comparingDouble(KillAura::getEntityArmor);
    private static final Comparator<Entity> BY_CRYSTAL_PRIORITY = Comparator.comparing(KillAura::getCrystalPriority);
    private static final Comparator<Entity> BY_OUT_OF_REACH = Comparator.comparingInt(KillAura::getOutOfReachRank);

    private static int getOutOfReachRank(Entity entity) {
        return RotationUtil.isWithinReach(entity) ? 0 : 1;
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

    private static boolean isLivingEntity(Entity entity) {
        return entity instanceof LivingEntity;
    }
}
