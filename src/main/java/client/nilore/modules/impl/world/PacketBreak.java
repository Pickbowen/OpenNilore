package client.nilore.modules.impl.world;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Random;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.PacketEvent;
import client.nilore.event.impl.RenderEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.event.impl.WorldChangeEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.game.BlockUtil;
import client.nilore.utils.game.RotationUtil;
import client.nilore.utils.misc.PacketUtil;
import client.nilore.utils.render.RenderUtil;
import client.nilore.utils.rotation.Rotation;
import client.nilore.utils.rotation.RotationHandler;
import client.nilore.utils.rotation.RotationSmoother;

/**
 * PacketBreak —— 点一下方块，之后由客户端替你把它挖完。
 * <p>
 * 挖掘期间把静默旋转压在目标方块上(Rotate)，只为满足 Grim PositionBreakA 的朝向判定，
 * 顺便让原版 continueDestroyBlock 一直认为视线还在方块上(不然每 tick 都会补 ABORT)。
 * 但<b>选取永远按客户端真实视角</b>：原版 pick 走的是静默旋转，点别的方块时 START 会被拉回旧目标，
 * 因此 MinecraftPatch 在 startAttack 入口用 {@link #clientViewBlockHit()} 把 hitResult 换成
 * 真实准星选中的方块，换目标靠的就是这一次点击产生的 START。
 * <p>
 * 选中之后不要求视线持续对着方块，想换目标必须重新点一下另一个方块。
 * <p>
 * 进度按 tick 累加 {@code getDestroyProgress} 的实时值: 中途落地、换工具、踩水都会立刻反映到
 * 剩余时间上。1.20.1 {@code Player.getDestroySpeed} 末尾是 {@code if (!onGround) f /= 5},
 * 所以"本次挖掘只要到过地面"之后就用 {@code 当前值 * 5} 当地面速度继续累加, 之后即使再跳起来也不回退。
 * <p>
 * 发包时序仍是原版节奏: 服务端 {@code ServerPlayerGameMode.handleBlockBreakAction} 里 START 只记
 * {@code destroyProgressStart}, 结算在 STOP
 * ({@code progress = getDestroyProgress * (gameTicks - startTick + 1)}, {@code >= 0.7} 立刻摧毁)。
 * 累计满 1.0 后再等 1 tick 才发 STOP, 给 Grim FastBreak 留余量(迟到只让 buffer 衰减)。
 * STOP 的 face 按当前眼睛位置重算, 满足 Grim PositionBreakA 的朝向判定。
 */
public class PacketBreak
extends Module {
    public static PacketBreak INSTANCE;

    private static final double FACE_INSET = 0.06;
    private static final double[] FACE_FRACTIONS = {0.5, 0.22, 0.78};
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final double MAX_SERVER_BLOCK_DISTANCE = 5.5;

    private final NumberSetting range = new NumberSetting("Range", 4.5, 1.0, 6.0, 0.1);
    private final BooleanSetting rotate = new BooleanSetting("Rotate", true);
    private final NumberSetting rotationSpeed = new NumberSetting("Rotation Speed", 45, 1, 180, 1,
            () -> this.rotate.getValue());
    private final BooleanSetting swingSetting = new BooleanSetting("Swing", true);
    private final BooleanSetting hold = new BooleanSetting("Hold", true);
    /**
     * 完成时相对 Grim 预测时间额外多等的百分比。Grim FastBreak 的判据是
     * {@code predictedTime = ceil(1 / maximumBlockDamage) * 50ms}(用本次最大每 tick 伤害),
     * 早于它才累计 buffer, 晚于它只会让 buffer 衰减。按比例留余量而不是固定 tick,
     * 这样慢方块(几十 tick)能盖住模型误差, 快方块也不会被固定值拖成龟速。
     */
    private final NumberSetting extraPercent = new NumberSetting("Extra %", 10, 0, 50, 1);
    private final BooleanSetting esp = new BooleanSetting("ESP", true);
    private final BooleanSetting showProgress = new BooleanSetting("Progress", true);
    private final ModeSetting progressMode = new ModeSetting("Mode", "Up", "In", "Out");
    private final NumberSetting red = new NumberSetting("Red", 255, 0, 255, 1);
    private final NumberSetting green = new NumberSetting("Green", 255, 0, 255, 1);
    private final NumberSetting blue = new NumberSetting("Blue", 255, 0, 255, 1);

    private BlockPos targetPos;
    /** 本次挖掘已累计的破坏进度(0..1)。 */
    private float accumulated;
    /** 本次挖掘见过的最大每 tick 伤害, 用来还原 Grim 的 predictedTime 模型。 */
    private float maxDamage;
    /** 本次挖掘经过的 tick 数。 */
    private int elapsedTicks;
    /** 渲染用进度: 取累计进度与"按 tick 下限推进"的较小值, 正好在发包那一刻到 100%。 */
    private float progress;
    /** 本次挖掘是否已经到过地面; 一旦为 true 就一直按地面速度算。 */
    private boolean landed;

    public Rotation targetRotation;
    private Vec3 aimPoint;
    private Direction aimFace;
    private BlockPos aimPos;
    private final RotationSmoother smoother = new RotationSmoother(new Random());

    public PacketBreak() {
        super("PacketBreak", Category.WORLD);
        INSTANCE = this;
    }

    @Override
    protected void onDisable() {
        this.clearTarget();
    }

    @EventTarget
    public void onWorldChange(WorldChangeEvent worldChangeEvent) {
        this.clearTarget();
    }

    @EventTarget
    public void onPacket(PacketEvent packetEvent) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (!(packetEvent.getPacket() instanceof ServerboundPlayerActionPacket actionPacket)) {
            return;
        }
        BlockPos pos = actionPacket.getPos();
        if (actionPacket.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
            // 选取只发生在点击这一刻, 且必须是玩家真实视角能选中的方块; 换目标也要过这一关。
            if (!this.isRealViewTarget(pos)) {
                return;
            }
            this.targetPos = pos.immutable();
            this.accumulated = 0.0f;
            this.maxDamage = 0.0f;
            this.elapsedTicks = 0;
            this.progress = 0.0f;
            this.landed = false;
            this.resetAim();
            if (mc.player.onGround()) {
                this.landed = true;
            }
        } else if (actionPacket.getAction() == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK
                && this.hold.getValue()
                && this.targetPos != null
                && this.targetPos.equals(pos)) {
            // 视线离开方块后原版会补 ABORT, 拦下来保住服务端那次 START 会话。
            packetEvent.setCancelled(true);
        }
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        BlockPos pos = this.targetPos;
        if (pos == null || mc.player == null || mc.level == null || mc.gameMode == null) {
            return;
        }
        // 选中之后不再看视角: 只校验方块还在、离得够近。
        if (!this.isValid(pos)) {
            this.clearTarget();
            return;
        }
        if (this.rotate.getValue()) {
            if (!this.updateAim(pos)) {
                this.clearTarget();
                return;
            }
        } else if (this.targetRotation != null) {
            this.resetAim();
        }
        // 本次挖掘只要落过地, 之后一直按地面速度算(再跳起来也不回退)。
        if (mc.player.onGround()) {
            this.landed = true;
        }
        // 旋转被别人抢走(KillAura 等)时视线可能已经不在方块上, 此时累加进度只会让
        // 服务端的 destroyProgressStart 与会话对不上, 先空转等旋转回来。
        if (this.rotate.getValue() && !this.hitsBlock(pos, mc.player.getEyePosition(1.0f), this.wireRotation())) {
            return;
        }
        float damage = this.tickDamage(pos, this.landed);
        if (damage <= 0.0f) {
            this.clearTarget();
            return;
        }
        this.maxDamage = Math.max(this.maxDamage, damage);
        this.accumulated = Math.min(1.0f, this.accumulated + damage);
        ++this.elapsedTicks;
        // Grim 的预测时间 = ceil(1 / 最大每 tick 伤害) 个 tick; 再按比例留余量。
        // 实际耗时不低于这个下限时 diff = predictedTime - realTime <= 0, buffer 只衰减不累计。
        int predictedTicks = Math.max(1, (int)Math.ceil(1.0 / this.maxDamage));
        int extraTicks = Math.max(1, (int)Math.ceil(predictedTicks * this.extraPercent.getValue().doubleValue() / 100.0));
        int finishAt = predictedTicks + extraTicks;
        this.progress = Math.min(1.0f, Math.min(this.accumulated,
                (float)this.elapsedTicks / (float)finishAt));
        if (this.swingSetting.getValue()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        } else {
            PacketUtil.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        }
        if (this.accumulated >= 1.0f && this.elapsedTicks >= finishAt) {
            // face 按当前眼睛位置重算, 保证 PositionBreakA 的朝向判定成立。
            Direction face = this.resolveFace(pos);
            PacketUtil.sendPredictiveDirect(sequence -> {
                // 原版挖穿时 (continueDestroyBlock → 预测 lambda) 也是先本地
                // destroyBlock(pos) 再发 STOP, 两者在同一个预测窗口里, 服务端 ack 能回滚误判。
                // 不做这一步的话, 方块在服务端已经没了、客户端还当它存在, 玩家会继续上报
                // onGround=true —— 站在被挖方块上时正好被 Grim 的 NoFall 判成"悬空却声称在地面"。
                mc.gameMode.destroyBlock(pos);
                return new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face, sequence);
            });
            this.clearTarget();
        }
    }

    /**
     * 点击那一刻客户端真实视角(鼠标)选中的方块, 供 MinecraftPatch 在 startAttack 入口替换 hitResult。
     * 只在模块确实在静默旋转(即存在挖掘会话)时返回值; 真实准星上挡着实体时返回 null,
     * 那种情况点击该是打人而不是挖方块。
     */
    public BlockHitResult clientViewBlockHit() {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            return null;
        }
        if (!this.rotate.getValue() || this.targetPos == null) {
            return null;
        }
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 look = RotationUtil.directionFromRotation(this.realRotation());
        double blockReach = mc.gameMode.getPickRange();
        double entityReach = mc.player.getEntityReach();
        Vec3 end = eye.add(look.scale(Math.max(blockReach, entityReach)));
        BlockHitResult blockHit = mc.level.clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (blockHit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        double blockDistanceSq = blockHit.getLocation().distanceToSqr(eye);
        if (blockDistanceSq > blockReach * blockReach) {
            return null;
        }
        AABB searchBox = mc.player.getBoundingBox()
                .expandTowards(look.scale(Math.max(blockReach, entityReach)))
                .inflate(1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(mc.player, eye, end, searchBox,
                entity -> !entity.isSpectator() && entity.isPickable(), blockDistanceSq);
        if (entityHit != null) {
            double entityDistanceSq = entityHit.getLocation().distanceToSqr(eye);
            if (entityDistanceSq < blockDistanceSq && entityDistanceSq <= entityReach * entityReach) {
                return null;
            }
        }
        return blockHit;
    }

    private boolean updateAim(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition(1.0f);
        boolean stale = this.aimPoint == null
                || !pos.equals(this.aimPos)
                || !this.hitsBlock(pos, eye, RotationUtil.rotationToPoint(eye, this.aimPoint));
        if (stale) {
            Vec3 found = this.findAimPoint(pos, eye);
            if (found == null) {
                return false;
            }
            this.aimPoint = found;
            this.aimPos = pos.immutable();
            this.smoother.reset();
        }
        Rotation aim = RotationUtil.rotationToPoint(eye, this.aimPoint);
        Rotation from = RotationHandler.prevRotation != null
                ? RotationHandler.prevRotation
                : new Rotation(mc.player.getYRot(), mc.player.getXRot());
        Rotation smoothed = this.smoother.tick(from, aim, this.rotationSpeed.getValue().doubleValue());
        this.targetRotation = RotationSmoother.patchConstantRotation(smoothed, from);
        return true;
    }

    private Vec3 findAimPoint(BlockPos pos, Vec3 eye) {
        AABB box = BlockUtil.getBoundingBox(pos);
        Vec3 center = box.getCenter();
        double reach = this.range.getValue().doubleValue();
        Rotation current = this.realRotation();
        Vec3 best = null;
        Direction bestFace = null;
        double bestScore = Double.MAX_VALUE;
        for (Direction face : DIRECTIONS) {
            double toward = face.getStepX() * (eye.x - center.x)
                    + face.getStepY() * (eye.y - center.y)
                    + face.getStepZ() * (eye.z - center.z);
            if (toward <= 0.0) {
                continue;
            }
            for (double u : FACE_FRACTIONS) {
                for (double v : FACE_FRACTIONS) {
                    Vec3 point = facePoint(box, face, u, v);
                    if (point.distanceTo(eye) > reach) {
                        continue;
                    }
                    Rotation candidate = RotationUtil.rotationToPoint(eye, point);
                    BlockHitResult hit = this.traceBlock(pos, eye, candidate);
                    if (hit == null) {
                        continue;
                    }
                    if (hit.getDirection() != face) {
                        continue;
                    }
                    double score = Math.abs(Mth.wrapDegrees(candidate.getYaw() - current.getYaw()))
                            + Math.abs(candidate.getPitch() - current.getPitch()) * 1.5;
                    if (score < bestScore) {
                        bestScore = score;
                        best = point;
                        bestFace = face;
                    }
                }
            }
        }
        this.aimFace = bestFace;
        return best;
    }

    private static Vec3 facePoint(AABB box, Direction face, double u, double v) {
        double minX = box.minX + FACE_INSET;
        double maxX = box.maxX - FACE_INSET;
        double minY = box.minY + FACE_INSET;
        double maxY = box.maxY - FACE_INSET;
        double minZ = box.minZ + FACE_INSET;
        double maxZ = box.maxZ - FACE_INSET;
        return switch (face) {
            case DOWN -> new Vec3(lerp(minX, maxX, u), box.minY, lerp(minZ, maxZ, v));
            case UP -> new Vec3(lerp(minX, maxX, u), box.maxY, lerp(minZ, maxZ, v));
            case NORTH -> new Vec3(lerp(minX, maxX, u), lerp(minY, maxY, v), box.minZ);
            case SOUTH -> new Vec3(lerp(minX, maxX, u), lerp(minY, maxY, v), box.maxZ);
            case WEST -> new Vec3(box.minX, lerp(minY, maxY, u), lerp(minZ, maxZ, v));
            case EAST -> new Vec3(box.maxX, lerp(minY, maxY, u), lerp(minZ, maxZ, v));
        };
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    private BlockHitResult traceBlock(BlockPos pos, Vec3 eye, Rotation rotation) {
        Vec3 direction = RotationUtil.directionFromRotation(rotation);
        Vec3 end = eye.add(direction.scale(this.range.getValue().doubleValue() + 0.5));
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) ? hit : null;
    }

    private boolean hitsBlock(BlockPos pos, Vec3 eye, Rotation rotation) {
        return this.traceBlock(pos, eye, rotation) != null;
    }

    private Rotation wireRotation() {
        if (RotationHandler.sentRotation != null) {
            return RotationHandler.sentRotation;
        }
        return new Rotation(mc.player.getYRot(), mc.player.getXRot());
    }

    private void resetAim() {
        this.aimPoint = null;
        this.aimFace = null;
        this.aimPos = null;
        this.targetRotation = null;
        this.smoother.reset();
    }

    /**
     * 本 tick 应该累加多少进度。{@code forceGround} 为 true 表示本次挖掘已经落过地, 即使此刻在空中
     * 也按地面速度算: 1.20.1 {@code Player.getDestroySpeed} 的最后一步是 {@code if (!onGround) f /= 5.0F},
     * 所以地面速度 = 当前值 * 5(水中惩罚与它互相独立)。
     */
    private float tickDamage(BlockPos pos, boolean forceGround) {
        BlockState blockState = mc.level.getBlockState(pos);
        float damage = blockState.getDestroyProgress(mc.player, mc.level, pos);
        if (forceGround && !mc.player.onGround()) {
            damage *= 5.0f;
        }
        return damage;
    }

    /** 点击时刻的"真实视角选中": 原版 pick 命中该方块, 或真实视线射线能打到它的碰撞箱。 */
    private boolean isRealViewTarget(BlockPos pos) {
        if (mc.hitResult instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(pos)) {
            return true;
        }
        Vec3 eye = mc.player.getEyePosition(1.0f);
        double reach = this.range.getValue().doubleValue();
        Vec3 end = eye.add(RotationUtil.directionFromRotation(this.realRotation()).scale(reach));
        return BlockUtil.getBoundingBox(pos).clip(eye, end).isPresent();
    }

    /** 玩家真实转角: 实体自身的 yaw/pitch, 不是静默旋转用的 ClientBase.yaw。 */
    private Rotation realRotation() {
        return new Rotation(mc.player.getYRot(), mc.player.getXRot());
    }

    /**
     * 按眼睛位置推出"朝向玩家的那一面": 取中心→眼睛的主轴, 就是玩家所在的外侧方向。
     * Grim 的 PositionBreakA 逐面判定: 例如 EAST 要求眼睛 maxX 不在方块 X 区间内。
     */
    private Direction resolveFace(BlockPos pos) {
        if (this.aimFace != null && pos.equals(this.aimPos)) {
            return this.aimFace;
        }
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 center = Vec3.atCenterOf(pos);
        double dx = eye.x - center.x;
        double dy = eye.y - center.y;
        double dz = eye.z - center.z;
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ax >= ay && ax >= az) {
            return dx >= 0.0 ? Direction.EAST : Direction.WEST;
        }
        if (ay >= az) {
            return dy >= 0.0 ? Direction.UP : Direction.DOWN;
        }
        return dz >= 0.0 ? Direction.SOUTH : Direction.NORTH;
    }

    private boolean isValid(BlockPos pos) {
        BlockState blockState = mc.level.getBlockState(pos);
        if (blockState.isAir()) {
            return false;
        }
        if (blockState.getDestroySpeed(mc.level, pos) < 0.0f) {
            return false;
        }
        Vec3 eye = mc.player.getEyePosition(1.0f);
        double distance = RotationUtil.closestPoint(eye, BlockUtil.getBoundingBox(pos)).distanceTo(eye);
        if (distance > this.range.getValue().doubleValue()) {
            return false;
        }
        return eye.distanceTo(Vec3.atCenterOf(pos)) <= MAX_SERVER_BLOCK_DISTANCE;
    }

    @EventTarget
    public void onRender(RenderEvent renderEvent) {
        BlockPos pos = this.targetPos;
        if (!this.esp.getValue() || pos == null || mc.level == null || mc.player == null) {
            return;
        }
        PoseStack poseStack = renderEvent.poseStack();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        AABB box = BlockUtil.getBoundingBox(pos);
        float red = this.red.getValue().floatValue() / 255.0f;
        float green = this.green.getValue().floatValue() / 255.0f;
        float blue = this.blue.getValue().floatValue() / 255.0f;
        poseStack.pushPose();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // RenderEvent 的矩阵只带视图旋转, 顶点要喂相机相对坐标, 所以先把栈推到相机原点。
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        RenderSystem.setShaderColor(red, green, blue, 0.85f);
        RenderUtil.drawOutlineBox(box, poseStack);
        if (this.showProgress.getValue() && this.progress > 0.0f) {
            RenderSystem.setShaderColor(red, green, blue, 0.35f);
            RenderUtil.drawSolidBox(this.progressBox(box), poseStack);
            // 进度文字放在框的正中间(方块几何中心)
            String label = Math.round(this.progress * 100.0f) + "%";
            poseStack.pushPose();
            poseStack.translate((double)pos.getX() + 0.5, (double)pos.getY() + 0.5, (double)pos.getZ() + 0.5);
            poseStack.mulPose(mc.gameRenderer.getMainCamera().rotation());
            poseStack.scale(-0.025f, -0.025f, 0.025f);
            float textWidth = mc.font.width(label);
            mc.font.drawInBatch(label, -textWidth / 2.0f, -mc.font.lineHeight / 2.0f, -1, false,
                    poseStack.last().pose(), mc.renderBuffers().bufferSource(),
                    Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
            poseStack.popPose();
            mc.renderBuffers().bufferSource().endBatch();
        }
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        poseStack.popPose();
    }

    private AABB progressBox(AABB box) {
        float progress = Math.max(0.0f, Math.min(1.0f, this.progress));
        if (this.progressMode.is("In") || this.progressMode.is("Out")) {
            double scale = Math.max(0.001, this.progressMode.is("In") ? 1.0 - progress : progress);
            double centerX = (box.minX + box.maxX) / 2.0;
            double centerY = (box.minY + box.maxY) / 2.0;
            double centerZ = (box.minZ + box.maxZ) / 2.0;
            double halfX = (box.maxX - box.minX) / 2.0 * scale;
            double halfY = (box.maxY - box.minY) / 2.0 * scale;
            double halfZ = (box.maxZ - box.minZ) / 2.0 * scale;
            return new AABB(centerX - halfX, centerY - halfY, centerZ - halfZ,
                    centerX + halfX, centerY + halfY, centerZ + halfZ).inflate(0.002);
        }
        double height = box.maxY - box.minY;
        return new AABB(box.minX, box.minY, box.minZ, box.maxX, box.minY + height * progress, box.maxZ)
                .inflate(0.002);
    }

    private void clearTarget() {
        this.targetPos = null;
        this.accumulated = 0.0f;
        this.maxDamage = 0.0f;
        this.elapsedTicks = 0;
        this.progress = 0.0f;
        this.landed = false;
        this.resetAim();
    }
}
