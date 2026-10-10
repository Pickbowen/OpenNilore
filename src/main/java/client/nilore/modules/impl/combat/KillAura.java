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
import client.nilore.event.EventTarget;

public class KillAura extends Module {
    public static KillAura INSTANCE;
    public static Entity target;
    public static Entity aimingTarget;
    public static List<Entity> targetList = new ArrayList<>();

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
    public final BooleanSetting fakeAutoBlock   = new BooleanSetting("Fake AutoBlock", true);

    public final NumberSetting reach       = new NumberSetting("Reach", 3.0, 1.0, 4.0, 0.1);
    public final NumberSetting maxAps      = new NumberSetting("Max APS", 12.0, 1.0, 20.0, 1.0);
    public final NumberSetting minAps      = new NumberSetting("Min APS", 9.0, 1.0, 20.0, 1.0);
    public final NumberSetting switchSize  = new NumberSetting("Switch Size", 1.0, 1.0, 5.0, 1.0,
            () -> !(Boolean) this.infSwitch.getValue());
    public final NumberSetting switchDelay = new NumberSetting("Switch Delay (Attack Times)", 1.0, 1.0, 10.0, 1.0);
    public final NumberSetting fov         = new NumberSetting("FoV", 360.0, 10.0, 360.0, 1.0);
    public final NumberSetting hurtTime    = new NumberSetting("Hurt Time", 10.0, 0.0, 10.0, 1.0);
    public final ModeSetting delayMode    = new ModeSetting("Delay Mode", "1.8", "1.9").withDefault("1.8");

    public final ModeSetting targetMode   = new ModeSetting("Target Mode", "Single", "Switch", "Multiple").withDefault("Single");
    public final ModeSetting priorityMode = new ModeSetting("Priority", "Distance", "FoV", "Health", "LivingTime", "Armor", "None").withDefault("FoV");
    public final ModeSetting targetEsp    = new ModeSetting("Target ESP", "None", "Spiral", "Box", "Tab", "NurikZapen").withDefault("NurikZapen");

    public final NumberSetting rotationSpeed = new NumberSetting("Rotation Speed", 180, 1, 180, 1);

    public final NumberSetting rotationDrift = new NumberSetting("Drift", 1.0, 0, 3, 0.1);

    public final NumberSetting rotationJitter = new NumberSetting("Jitter", 1.0, 0, 3, 0.1);

    private int attackTimes;
    private float attacks;
    private int targetIndex;
    public int sprintTickCounter;
    public Rotation rotation;

    /** The sprint release is one-shot; the Keep Sprint hold arms the next one. */
    private boolean sprintCancelled;

    private Random organicRandom;

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
        this.targetIndex = 0;
        this.attacks = 0.0f;
        this.sprintCancelled = false;
        target = null;
        aimingTarget = null;
        targetList.clear();
        super.onEnable();
    }

    @Override
    public void onDisable() {
        this.attacks = 0.0f;
        this.sprintCancelled = false;
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
        if (!this.throughWalls.getValue() && !mc.player.hasLineOfSight(target)) {
            return null;
        }
        Vec3 aimPoint = RotationUtil.findAimPoint(target, range, 1.0f);
        if (aimPoint == null) {
            return null;
        }
        Rotation base = RotationUtil.rotationToPoint(mc.player.getEyePosition(), aimPoint);

        this.organicYawOffset += (float) ((this.organicRandom.nextDouble() - 0.5)
                * this.rotationDrift.getValue().doubleValue());
        this.organicPitchOffset += (float) ((this.organicRandom.nextDouble() - 0.5) * 2.0
                * this.rotationJitter.getValue().doubleValue());

        Rotation jittered = new Rotation(base.getYaw() + this.organicYawOffset,
                Mth.clamp(base.getPitch() + this.organicPitchOffset, -90.0f, 90.0f));
        HitResult probe = RotationUtil.rayTraceForAim(jittered, range);
        if (!(probe instanceof EntityHitResult hit) || hit.getEntity() != target) {
            this.organicYawOffset = 0.0f;
            this.organicPitchOffset = 0.0f;
            jittered = base;
        }

        Rotation smoothed = RotationUtil.smoothRotationTo(from, jittered, speed);
        if (smoothed == null
                || Float.isNaN(smoothed.getYaw()) || Float.isNaN(smoothed.getPitch())
                || Float.isInfinite(smoothed.getYaw()) || Float.isInfinite(smoothed.getPitch())) {
            return jittered;
        }
        return smoothed;
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
        if (!isSprintReleaseWindow()) {
            this.sprintCancelled = false;
        }
        double reach = Mth.clamp(this.reach.getValue().doubleValue(),
                this.reach.getMin().doubleValue(),
                this.reach.getMax().doubleValue());
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
        aimingTarget = this.getTarget();
        if (aimingTarget == null) {
            this.rotation = null;
        }
        if (targetList.isEmpty()) {
            target = null;
            return;
        }
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
                this.rotation = this.computeRotation(this.aimingTarget, from, speed,
                        Math.max(reach, 3.05));
            }
        } else {
            this.rotation = null;
        }

        this.attackTick();

        if (!this.canAttackNow()) {
            return;
        }
        // No antikb coupling here on purpose: the aura keeps its own APS while the
        // antikb is countering. It used to be throttled here and blocked outright in
        // attackTick/canAttackNow during the counter window, which made the aura go
        // dead whenever the antikb engaged. Double hits are still impossible - both
        // attack paths take attackAndLock's per-tick lock.
        float apsValue = this.maxAps.getValue().floatValue();
        float minApsValue = this.minAps.getValue().floatValue();
        this.attacks += (float)(MathUtil.randomDouble(minApsValue, apsValue) / 20.0);
    }

    private void attackTick() {
        if (mc.player == null) return;
        if (this.isWebPlacing()) {
            this.attacks = 0.0f;
            return;
        }
        if (!this.canAttackNow()) {
            this.attacks = 0.0f;
            return;
        }
        while (this.attacks >= 1.0f) {
            this.doAttack();
            this.attacks -= 1.0f;
        }
    }

    private boolean canAttackNow() {
        if (mc.player == null || mc.screen != null || mc.player.isUsingItem()) {
            return false;
        }
        if (target == null || !targetList.contains(target)) {
            return false;
        }
        if (!this.throughWalls.getValue()) {
            double reachNow = Math.max(this.reach.getValue().doubleValue(),
                    RotationUtil.getReachLimit());
            if (!RotationUtil.isLookingAt(target, reachNow, 0.1f)) {
                return false;
            }
        }
        if (this.delayMode.is("1.9") && mc.player.getAttackStrengthScale(0.0f) < 0.95f) {
            return false;
        }
        if (!(this.ignoreSkipTicks.getValue() || ClientBase.delayPackets.isEmpty()
                || (Critical.INSTANCE != null && Critical.INSTANCE.isEnabled()))) {
            return false;
        }
        // Velocity gate, mirroring the reference's canCrit(). The counter hits are sprint hits by
        // construction - the NoXZ counter only swings while isSprinting() holds - so they cannot
        // crit, and swinging the aura into the same window would just fight it for the per-tick
        // attack lock. The isInDelayWindow branch is the keepsprint half of the contract: while the
        // delay window still owns the input (on ground, mid-counter, or about to land) the aura
        // stays out so the window's own isSprinting() gate stays true.
        if (NoXZMode.isCountering()) {
            return false;
        }
        if (NoXZMode.isInDelayWindow() && shouldKeepSprintInDelayWindow()) {
            return false;
        }
        return RotationUtil.isWithinReach(target);
    }

    /**
     * The reference's canCrit() gate. While it holds, the reference drops the sprint key and clears
     * the sprint flag from its SprintEvent handler, so the swing that follows lands as a crit
     * instead of a sprint hit. Ported verbatim including the 3.5-block reach (12.25 squared) and the
     * velocity interlocks, because those are what keep the release from firing at the wrong time.
     */
    public boolean canCritUnderVelocity() {
        if (mc.player == null || mc.level == null || target == null) {
            return false;
        }
        if (NoXZMode.isInDelayWindow() && shouldKeepSprintInDelayWindow()) {
            return false;
        }
        if (NoXZMode.isCountering()) {
            return false;
        }
        if (target.distanceToSqr(mc.player) > 12.25) {
            return false;
        }
        if (mc.player.isDeadOrDying() || !this.keepSprint.getValue()) {
            return false;
        }
        return !mc.player.onGround() || !mc.options.keyJump.isDown();
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

    /** No jumping between a release and the sprint coming back. */
    @EventTarget
    public void onStrafe(StrafeEvent strafeEvent) {
        if (mc.player == null || !this.keepSprint.getValue()) {
            return;
        }
        // StrafeEvent carries Input.jumping in its "sprinting" field (see KeyboardInputPatch).
        if (this.sprintCancelled && !mc.player.isSprinting()) {
            strafeEvent.setSprinting(false);
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

    public void markSprintReleased() {
        this.sprintCancelled = true;
    }

    public boolean isSprintReleased() {
        return this.sprintCancelled;
    }

    public static boolean isSprintReleaseWindow() {
        if (mc.player == null) return false;
        return !mc.player.onGround();
    }

    public static boolean shouldStopSprint() {
        KillAura aura = INSTANCE;
        if (aura == null) return false;
        // The reference checks nothing about the aura being enabled - canCrit() stands on its own.
        // With the aura off there is no target, so the reach gate never opens on its own.
        if (aura.canCritUnderVelocity()) return true;
        if (!aura.keepSprint.getValue()) return false;
        if (aura.sprintCancelled) return false;
        if (mc.player == null || target == null) return false;
        if (mc.player.isUsingItem()) return false;
        // The Jump Reset gate belongs to that branch only. Checking the flag unconditionally also
        // pinned the release off for good after a mode switch: JumpResetMode clears isJumping from its
        // own handler, which starts with "if (!AntiKB.mode.is("Jump Reset")) return;" - so switching
        // away mid-jump-reset leaves the flag stuck true, every sprint release in the client stops
        // firing, and the Keep Sprint hold has nothing to wait for.
        if (AntiKB.mode.is("Jump Reset") && JumpResetMode.isJumping) return false;
        if (NoXZMode.isInDelayWindow() && shouldKeepSprintInDelayWindow()) return false;
        if (!aura.canAttackTarget()) return false;
        // No damage prediction here: the prediction belongs to CriticalsModule's crit-sync
        // predicate, not to the sprint release, and putting it here let the sprint stay on
        // for good whenever the estimate could not beat the recorded swing - with Keep Sprint's
        // hold that means no attack at all in the air.
        return isSprintReleaseWindow();
    }

    /** Shared "the delay window still owns the input" predicate: on the ground or mid-counter, plus a falling landing check. NoXZMode's onStrafe reads it too. */
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
            for (Entity entity : targetList) {
                if (mc.player == null) break;
                if (!RotationUtil.isWithinReach(entity)) continue;
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
        AABB box = mc.player.getBoundingBox().inflate(Math.max(this.reach.getValue().doubleValue(), 3.05));
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
