package shit.nilore.modules.impl.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.client.ForgeHooksClient;
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
import shit.nilore.ClientBase;
import shit.nilore.NiloreClient;
import shit.nilore.event.impl.PreMotionEvent;
import shit.nilore.event.impl.RenderEvent;
import shit.nilore.event.impl.SprintEvent;
import shit.nilore.event.impl.TickEvent;
import shit.nilore.event.impl.WorldChangeEvent;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.modules.impl.combat.antikb.NoXZMode;
import shit.nilore.modules.impl.movement.Scaffold;
import shit.nilore.modules.impl.player.AntiTNT;
import shit.nilore.modules.impl.player.AntiWeb;
import shit.nilore.modules.impl.player.AutoWebPlace;
import shit.nilore.modules.impl.player.Helper;
import shit.nilore.modules.impl.player.MidPearl;
import shit.nilore.modules.impl.player.Stuck;
import shit.nilore.modules.impl.world.Teams;
import shit.nilore.settings.impl.BooleanSetting;
import shit.nilore.settings.impl.ModeSetting;
import shit.nilore.settings.impl.NumberSetting;
import shit.nilore.utils.game.EntityUtil;
import shit.nilore.utils.game.ItemUtil;
import shit.nilore.utils.game.RotationUtil;
import shit.nilore.utils.math.MathUtil;
import shit.nilore.utils.misc.ChatUtil;
import shit.nilore.utils.render.RenderUtil;
import shit.nilore.utils.rotation.Rotation;
import shit.nilore.utils.rotation.RotationHandler;
import shit.nilore.event.EventTarget;

public class KillAura extends Module {
    public static KillAura INSTANCE;
    public static Entity target;
    public static Entity aimingTarget;
    public static List<Entity> targetList = new ArrayList<>();

    // Fields kept in sync with the obfuscated jar: 12 BooleanSetting / 7
    // NumberSetting / 3 ModeSetting, in declaration order.
    public final BooleanSetting attackPlayer    = new BooleanSetting("Attack Player", true);
    public final BooleanSetting attackInvisible = new BooleanSetting("Attack Invisible", false);
    public final BooleanSetting attackAnimals   = new BooleanSetting("Attack Animals", false);
    public final BooleanSetting attackMobs      = new BooleanSetting("Attack Mobs", true);
    public final BooleanSetting multiAttack     = new BooleanSetting("Multi Attack", true);
    public final BooleanSetting infSwitch       = new BooleanSetting("Infinity Switch", false);
    public final BooleanSetting preferBaby      = new BooleanSetting("Prefer Baby", false);
    public final BooleanSetting morePart        = new BooleanSetting("More Particles", false);
    public final BooleanSetting keepSprint      = new BooleanSetting("Keep Sprint", true);
    public final BooleanSetting ignoreSkipTicks = new BooleanSetting("Ignore skip ticks", false);
    public final BooleanSetting fakeAutoBlock   = new BooleanSetting("Fake AutoBlock", true);
    public final BooleanSetting test            = new BooleanSetting("Test", false);
    public final NumberSetting aimRange    = new NumberSetting("Aim Range", 4.0, 1.0, 6.0, 0.1);
    public final NumberSetting maxAps      = new NumberSetting("Max APS", 12.0, 1.0, 20.0, 1.0);
    public final NumberSetting minAps      = new NumberSetting("Min APS", 9.0, 1.0, 20.0, 1.0);
    public final NumberSetting switchSize  = new NumberSetting("Switch Size", 1.0, 1.0, 5.0, 1.0,
            () -> !(Boolean) this.infSwitch.getValue());
    public final NumberSetting switchDelay = new NumberSetting("Switch Delay (Attack Times)", 1.0, 1.0, 10.0, 1.0);
    public final NumberSetting fov         = new NumberSetting("FoV", 360.0, 10.0, 360.0, 1.0);
    public final NumberSetting hurtTime    = new NumberSetting("Hurt Time", 10.0, 0.0, 10.0, 1.0);
    public final ModeSetting delayMode    = new ModeSetting("Delay Mode", "1.8", "1.9").withDefault("1.8");
    public final ModeSetting priorityMode = new ModeSetting("Priority", "Distance", "FoV", "Health", "None").withDefault("FoV");
    public final ModeSetting targetEsp    = new ModeSetting("Target ESP", "None", "Spiral", "Box", "Tab").withDefault("None");

    public final BooleanSetting predictionEnabled  = new BooleanSetting("Prediction", false);
    public final NumberSetting enemyDelayThreshold = new NumberSetting("Enemy Delay Ticks", 3, 1, 5, 1,
            () -> (Boolean) this.predictionEnabled.getValue());
    public final NumberSetting selfDelayThreshold  = new NumberSetting("Self Delay Ticks", 3, 1, 5, 1,
            () -> (Boolean) this.predictionEnabled.getValue());

    public final NumberSetting rotationSpeed = new NumberSetting("Rotation Speed", 180, 0, 720, 5);
    public final NumberSetting rotationDrift = new NumberSetting("Drift", 0.1, 0, 5, 0.1);
    public final NumberSetting rotationJitter = new NumberSetting("Jitter", 0.02, 0, 1, 0.01);

    private RotationUtil.BestHitInfo currentBestHit;
    private RotationUtil.BestHitInfo prevBestHit;
    private int attackTimes;
    private float attacks;
    private int targetIndex;
    public int sprintTickCounter;
    private int sprintCounter;
    public Rotation rotation;

    private Random organicRandom;
    private double organicTimeAccumulator;
    private double orgFreqYaw1, orgFreqYaw2, orgFreqPitch1, orgFreqPitch2;
    private double orgPhaseYaw1, orgPhaseYaw2, orgPhasePitch1, orgPhasePitch2;

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
        this.sprintCounter = 0;
        this.attackTimes = 0;
        this.rotation = null;
        super.onDisable();
    }

    private void reinitOrganicModel() {
        this.organicRandom = new Random(System.nanoTime());
        this.organicTimeAccumulator = 0.0;
        this.orgFreqYaw1 = this.organicRandom.nextDouble() * 0.3 + 0.1;
        this.orgFreqYaw2 = this.organicRandom.nextDouble() * 0.5 + 0.5;
        this.orgFreqPitch1 = this.organicRandom.nextDouble() * 0.3 + 0.1;
        this.orgFreqPitch2 = this.organicRandom.nextDouble() * 0.5 + 0.5;
        this.orgPhaseYaw1 = this.organicRandom.nextDouble() * Math.PI * 2;
        this.orgPhaseYaw2 = this.organicRandom.nextDouble() * Math.PI * 2;
        this.orgPhasePitch1 = this.organicRandom.nextDouble() * Math.PI * 2;
        this.orgPhasePitch2 = this.organicRandom.nextDouble() * Math.PI * 2;
    }

    private Rotation applyOrganicRotation(Rotation from, Rotation to, float timeDelta) {
        float rawYawDelta = Mth.wrapDegrees(to.getYaw() - from.getYaw());
        float rawPitchDelta = to.getPitch() - from.getPitch();

        double speed = this.rotationSpeed.getValue().doubleValue();
        double driftIntensity = this.rotationDrift.getValue().doubleValue();
        double jitterIntensity = this.rotationJitter.getValue().doubleValue();

        // speed=0: 瞬转，不加漂移抖动
        if (speed <= 0) {
            return to;
        }

        float deltaYaw = rawYawDelta * timeDelta;
        float deltaPitch = rawPitchDelta * timeDelta;

        double distance = Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);
        if (distance < driftIntensity) {
            return new Rotation(from.getYaw() + deltaYaw, from.getPitch() + deltaPitch);
        }

        if (distance > 0) {
            double ratioYaw = Math.abs(deltaYaw) / distance;
            double ratioPitch = Math.abs(deltaPitch) / distance;
            double maxYaw = speed * ratioYaw * timeDelta;
            double maxPitch = speed * ratioPitch * timeDelta;
            deltaYaw = Mth.clamp(deltaYaw, (float)-maxYaw, (float)maxYaw);
            deltaPitch = Mth.clamp(deltaPitch, (float)-maxPitch, (float)maxPitch);
        }

        this.organicTimeAccumulator += timeDelta;

        double sinYaw = Math.sin(this.organicTimeAccumulator * this.orgFreqYaw1 + this.orgPhaseYaw1)
                + (this.organicRandom.nextDouble() * 0.1 + 0.45) * Math.sin(this.organicTimeAccumulator * this.orgFreqYaw2 + this.orgPhaseYaw2);
        double sinPitch = Math.sin(this.organicTimeAccumulator * this.orgFreqPitch1 + this.orgPhasePitch1)
                + (this.organicRandom.nextDouble() * 0.1 + 0.45) * Math.sin(this.organicTimeAccumulator * this.orgFreqPitch2 + this.orgPhasePitch2);
        double driftYaw = sinYaw * driftIntensity * timeDelta;
        double driftPitch = sinPitch * driftIntensity * timeDelta;

        double jitterYaw = (this.organicRandom.nextDouble() * 2 - 1) * jitterIntensity * timeDelta;
        double jitterPitch = (this.organicRandom.nextDouble() * 2 - 1) * jitterIntensity * timeDelta;

        float moveYaw = deltaYaw + (float)driftYaw + (float)jitterYaw;
        float movePitch = deltaPitch + (float)driftPitch + (float)jitterPitch;

        float finalYaw = from.getYaw() + moveYaw;
        float finalPitch = Mth.clamp(from.getPitch() + movePitch, -90.0f, 90.0f);
        return patchConstantRotation(new Rotation(finalYaw, finalPitch), from);
    }

    /**
     * GCD 对齐：将旋转增量取整到灵敏度步长的整数倍，
     * 防止对静止目标产生微抖。等价于 Candy 的 RotationUtility.patchConstantRotation。
     */
    private static Rotation patchConstantRotation(Rotation rotation, Rotation prevRotation) {
        double sensitivity = mc.options.sensitivity().get().floatValue() * 0.6 + 0.2;
        double multiplier = (sensitivity * sensitivity * sensitivity) * 8.0;
        double divisor = multiplier * 0.15;

        float yawDelta = rotation.getYaw() - prevRotation.getYaw();
        float pitchDelta = rotation.getPitch() - prevRotation.getPitch();
        float yaw = prevRotation.getYaw() + (float)(Math.round(yawDelta / divisor) * divisor);
        float pitch = prevRotation.getPitch() + (float)(Math.round(pitchDelta / divisor) * divisor);
        return new Rotation(yaw, pitch);
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
            default -> {
            }
        }
        poseStack.popPose();
    }

    @EventTarget
    public void onSprint(SprintEvent event) {
        if (this.keepSprint.getValue()) {
            ++this.sprintTickCounter;
            if (this.sprintTickCounter % 2 == 0 && mc.player != null) {
                mc.player.setSprinting(false);
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!NiloreClient.isReady()) {
            return;
        }
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
            this.currentBestHit = null;
            this.rotation = null;
            this.prevBestHit = null;
            targetList.clear();
            this.sprintTickCounter = 0;
            this.attacks = 0.0f;
            this.sprintCounter = 0;
            return;
        }
        boolean isSwitch = this.switchSize.getValue().intValue() > 1
                || this.infSwitch.getValue()
                || this.multiAttack.getValue();
        this.updateTargets();
        aimingTarget = this.getTarget();
        this.prevBestHit = this.currentBestHit;
        this.currentBestHit = null;
        if (aimingTarget != null) {
            this.currentBestHit = RotationUtil.getBestHit(aimingTarget);
            Rotation rawTarget = this.currentBestHit != null ? this.currentBestHit.rotation() : null;
            if (rawTarget != null) {
                Rotation from = RotationHandler.prevRotation != null
                        ? RotationHandler.prevRotation
                        : new Rotation(mc.player.getYRot(), mc.player.getXRot());
                Rotation organic = this.applyOrganicRotation(from, rawTarget, 1.0f);
                this.rotation = (organic != null
                        && !Float.isNaN(organic.getYaw())
                        && !Float.isNaN(organic.getPitch())
                        && !Float.isInfinite(organic.getYaw())
                        && !Float.isInfinite(organic.getPitch()))
                        ? organic
                        : rawTarget;
            } else {
                this.rotation = null;
            }
        } else {
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
                && (this.attackTimes >= this.switchDelay.getValue().intValue()
                || (this.currentBestHit != null && this.currentBestHit.distance() > 3.0))) {
            this.attackTimes = 0;
            for (int i = 0; i < targetList.size(); ++i) {
                ++this.targetIndex;
                if (this.targetIndex > targetList.size() - 1) {
                    this.targetIndex = 0;
                }
                Entity nextTarget = targetList.get(this.targetIndex);
                RotationUtil.BestHitInfo nextHit = RotationUtil.getBestHit(nextTarget);
                if (nextHit != null && nextHit.distance() < 3.0) {
                    break;
                }
            }
        }
        if (this.targetIndex > targetList.size() - 1 || !isSwitch) {
            this.targetIndex = 0;
        }
        target = targetList.get(this.targetIndex);
        if (this.delayMode.is("1.8")) {
            float apsValue;
            float minApsValue;
            if (NoXZMode.isAttacking) {
                int kbAttackAmount = AntiKB.INSTANCE != null
                        ? AntiKB.INSTANCE.attackAmount.getValue().intValue()
                        : 0;
                apsValue = this.maxAps.getValue().floatValue() - kbAttackAmount;
                minApsValue = this.minAps.getValue().floatValue() - kbAttackAmount;
            } else {
                apsValue = this.maxAps.getValue().floatValue();
                minApsValue = this.minAps.getValue().floatValue();
            }
            if (this.keepSprint.getValue()) {
                apsValue *= 2.0f;
                minApsValue *= 2.0f;
            }
            this.attacks += (float)(MathUtil.randomDouble(minApsValue, apsValue) / 20.0);
        } else if (this.sprintCounter > 0) {
            this.sprintCounter--;
        } else if (mc.player.getAttackStrengthScale(0.0f) >= 0.9f) {
            this.doAttack();
        }
    }

    @EventTarget
    public void onPreMotion(PreMotionEvent event) {
        if (mc.player == null) return;
        if (this.isWebPlacing()) {
            this.attacks = 0.0f;
            return;
        }
        if (mc.player.getUseItem().isEmpty()
                && mc.screen == null
                && (this.ignoreSkipTicks.getValue() || ClientBase.delayPackets.isEmpty()
                || (Critical.INSTANCE != null && Critical.INSTANCE.isEnabled()))) {
            while (this.attacks >= 1.0f) {
                this.doAttack();
                this.attacks -= 1.0f;
            }
        } else {
            this.attacks = 0.0f;
        }
    }

    public void doAttack() {
        if (this.isWebPlacing()) {
            this.attacks = 0.0f;
            return;
        }
        if (targetList.isEmpty()) return;
        if (this.rotation == null) return;

        HitResult hitResult = mc.hitResult;
        if (hitResult != null && hitResult.getType() == HitResult.Type.ENTITY) {
            Entity hitEntity = ((EntityHitResult) hitResult).getEntity();
            if (AntiBots.isBot(hitEntity)) {
                ChatUtil.print("Skipped attack on suspected bot");
                return;
            }
        }
        if (this.multiAttack.getValue()) {
            int attacked = 0;
            Rotation aimRot = RotationHandler.targetRotation;
            for (Entity entity : targetList) {
                if (mc.player == null) break;
                // 无旋转状态时跳过 rotation 距离检查，直接攻击目标
                if (aimRot != null && RotationUtil.getHitDistance(entity, mc.player.getEyePosition(), aimRot) >= 3.0) continue;
                this.attackEntity(entity);
                if (++attacked >= 2) break;
            }
        } else if (hitResult != null && hitResult.getType() == HitResult.Type.ENTITY) {
            Entity hitEntity = ((EntityHitResult) hitResult).getEntity();
            this.attackEntity(hitEntity);
        } else if (target != null && targetList.contains(target)) {
            // 没有 ray trace 命中（无瞄准目标时），直接攻击当前目标
            this.attackEntity(target);
        }
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
            if (entity instanceof Player player) {
                if (this.test.getValue() && player.getY() >= mc.player.getY() + 0.05f) {
                    return true;
                }
                // NiloreClient.isOwner() was stripped during deobfuscation; the
                // original jar bailed here when the entity name matched the
                // client owner. Re-enable once that helper is restored.
            }
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
        if (!this.isValidTarget(entity)) return false;
        if (entity instanceof LivingEntity le && le.hurtTime > this.hurtTime.getValue().intValue()) {
            return false;
        }
        Vec3 vec3 = RotationUtil.closestPoint(mc.player.getEyePosition(), entity.getBoundingBox());
        double dist = vec3.distanceTo(mc.player.getEyePosition());
        if (dist <= 2.9) {
            // 已经在 2.9 格以内，不预测，直接通过
        } else if (dist > 5.0) {
            return false;
        } else if (dist > this.aimRange.getValue().floatValue()) {
            if (!(Boolean) this.predictionEnabled.getValue()
                    || this.predictDistance(entity) >= 3.0) {
                return false;
            }
        }
        return RotationUtil.isEntityInFov(entity, this.fov.getValue().floatValue() / 2.0f);
    }

    private double predictDistance(Entity entity) {
        double selfDelayMs = 0.0;
        if (mc.getConnection() != null
                && mc.getConnection().getPlayerInfo(mc.player.getUUID()) != null) {
            selfDelayMs = mc.getConnection().getPlayerInfo(mc.player.getUUID()).getLatency();
        }
        double selfDelayTicks = Math.min(selfDelayMs / 50.0, this.selfDelayThreshold.getValue().doubleValue());

        double enemyDelayMs = 0.0;
        if (entity instanceof Player player) {
            if (mc.getConnection() != null
                    && mc.getConnection().getPlayerInfo(player.getUUID()) != null) {
                enemyDelayMs = mc.getConnection().getPlayerInfo(player.getUUID()).getLatency();
            }
        }
        double enemyDelayTicks = Math.min(enemyDelayMs / 50.0, this.enemyDelayThreshold.getValue().doubleValue());

        // 固定 3tick 预测 + 延迟 tick
        double totalTicks = 3.0 + selfDelayTicks + enemyDelayTicks;

        double playerVelX = mc.player.getX() - mc.player.xOld;
        double playerVelZ = mc.player.getZ() - mc.player.zOld;
        double enemyVelX = entity.getX() - entity.xOld;
        double enemyVelZ = entity.getZ() - entity.zOld;

        double predictedPlayerX = mc.player.getX() + playerVelX * totalTicks;
        double predictedPlayerZ = mc.player.getZ() + playerVelZ * totalTicks;
        double predictedEnemyX = entity.getX() + enemyVelX * totalTicks;
        double predictedEnemyZ = entity.getZ() + enemyVelZ * totalTicks;

        double dx = predictedEnemyX - predictedPlayerX;
        double dz = predictedEnemyZ - predictedPlayerZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public void attackEntity(Entity entity) {
        if (mc.player == null || mc.gameMode == null) return;
        if (this.isWebPlacing()) return;

        ++this.attackTimes;
        float currentYaw = mc.player.getYRot();
        float currentPitch = mc.player.getXRot();
        if (RotationHandler.targetRotation != null) {
            mc.player.setYRot(RotationHandler.targetRotation.getYaw());
            mc.player.setXRot(RotationHandler.targetRotation.getPitch());
        }

        int attackKey = mc.options.keyAttack.getKey().getValue();
        if (this.keepSprint.getValue()) {
            if (this.sprintTickCounter % 2 == 0) {
                mc.gameMode.attack(mc.player, entity);
                ForgeHooksClient.onMouseButtonPre(attackKey, 1, 0);
                mc.player.swing(InteractionHand.MAIN_HAND);
                ForgeHooksClient.onMouseButtonPost(attackKey, 1, 0);
            }
        } else {
            mc.gameMode.attack(mc.player, entity);
            ForgeHooksClient.onMouseButtonPre(attackKey, 1, 0);
            mc.player.swing(InteractionHand.MAIN_HAND);
            ForgeHooksClient.onMouseButtonPost(attackKey, 1, 0);
        }

        if (this.morePart.getValue()) {
            mc.player.magicCrit(entity);
            mc.player.crit(entity);
        }

        mc.player.setYRot(currentYaw);
        mc.player.setXRot(currentPitch);

        if (this.delayMode.is("1.9")) {
            this.sprintCounter = (int) mc.player.getCurrentItemAttackStrengthDelay();
        }
    }

    private boolean isWebPlacing() {
        return AutoWebPlace.INSTANCE != null && AutoWebPlace.INSTANCE.isEnabled() && AutoWebPlace.targetRotation != null;
    }

    private List<Entity> getTargets() {
        if (mc.player == null || mc.level == null) {
            return new ArrayList<>();
        }
        Stream<Entity> stream = StreamSupport.stream(mc.level.entitiesForRendering().spliterator(), true)
                .filter(this::isValidAttack);
        List<Entity> possibleTargets = stream.collect(Collectors.toList());
        if (this.priorityMode.is("Distance")) {
            possibleTargets.sort(Comparator.comparingDouble(KillAura::getDistanceToPlayer));
        } else if (this.priorityMode.is("FoV")) {
            possibleTargets.sort(Comparator.comparingDouble(KillAura::getAngleDiffToTarget));
        } else if (this.priorityMode.is("Health")) {
            possibleTargets.sort(Comparator.comparingDouble(KillAura::getEntityHealth));
        }
        if (this.preferBaby.getValue()
                && possibleTargets.stream().anyMatch(KillAura::isBaby)) {
            possibleTargets.removeIf(KillAura::isNotBaby);
        }
        possibleTargets.sort(Comparator.comparing(KillAura::getCrystalPriority));
        if (this.infSwitch.getValue()) {
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

    private static double getEntityHealth(Entity entity) {
        if (entity instanceof LivingEntity le) {
            return le.getHealth();
        }
        return 0.0;
    }

    private static double getAngleDiffToTarget(Entity entity) {
        return RotationUtil.angleDiff(RotationHandler.targetRotation.getYaw(), RotationUtil.entityRotation(entity).getYaw());
    }

    private static double getDistanceToPlayer(Entity entity) {
        return entity.distanceTo(mc.player);
    }

    private static boolean isLivingEntity(Entity entity) {
        return entity instanceof LivingEntity;
    }
}
