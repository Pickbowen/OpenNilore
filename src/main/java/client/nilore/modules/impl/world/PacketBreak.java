package client.nilore.modules.impl.world;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
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

public class PacketBreak
extends Module {
    public static PacketBreak INSTANCE;

    /** 面采样网格：贴边留一点余量，正好落在棱上的点会被相邻面抢走。 */
    private static final double FACE_INSET = 0.06;
    private static final double[] FACE_FRACTIONS = {0.5, 0.22, 0.78};
    private static final Direction[] DIRECTIONS = Direction.values();
    /**
     * 服务端 {@code Player#canInteractWithBlock} 的外沿：blockInteractionRange(4.5) + 1.0。
     * 超过这个距离的挖掘服务端直接不认，本地再攒多久都是白搭。
     */
    private static final double MAX_SERVER_BLOCK_DISTANCE = 5.5;

    private final NumberSetting range = new NumberSetting("Range", 4.5, 1.0, 6.0, 0.1);
    private final BooleanSetting rotate = new BooleanSetting("Rotate", true);
    private final NumberSetting rotationSpeed = new NumberSetting("Rotation Speed", 45, 1, 180, 1,
            () -> this.rotate.getValue());
    private final BooleanSetting swingSetting = new BooleanSetting("Swing", true);
    private final BooleanSetting hold = new BooleanSetting("Hold", true);
    private final BooleanSetting esp = new BooleanSetting("ESP", true);
    private final BooleanSetting showProgress = new BooleanSetting("Progress", true);
    private final ModeSetting progressMode = new ModeSetting("Mode", "Up", "In", "Out");
    private final NumberSetting red = new NumberSetting("Red", 255, 0, 255, 1);
    private final NumberSetting green = new NumberSetting("Green", 255, 0, 255, 1);
    private final NumberSetting blue = new NumberSetting("Blue", 255, 0, 255, 1);

    private BlockPos targetPos;
    private float accumulated;
    private boolean landed;
    private boolean finishPending;

    /**
     * 本 tick 交给 {@link RotationHandler} 的朝向。null = 不接管转向。
     *
     * <p>必须由模块自己平滑：{@code onTickHigh} 只是把最终角度贴到所有出站包上，不做时间积分。
     */
    public Rotation targetRotation;
    /** 选定的挖矿瞄准点（方块表面上的世界坐标）。 */
    private Vec3 aimPoint;
    /** aimPoint 落在哪张面上。STOP 包要带着它出门，比按"眼睛→中心"猜准。 */
    private Direction aimFace;
    /** aimPoint 是为哪个方块选的。方块换了就要重新找。 */
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
            if (!this.isRealViewTarget(pos)) {
                return;
            }
            this.targetPos = pos.immutable();
            this.accumulated = 0.0f;
            this.landed = false;
            this.finishPending = false;
            // 新方块：上一块的角度与惯性一概作废。
            this.resetAim();
            if (mc.player.onGround()) {
                this.landed = true;
            }
        } else if (actionPacket.getAction() == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK
                && this.hold.getValue()
                && this.targetPos != null
                && this.targetPos.equals(pos)) {
            packetEvent.setCancelled(true);
        }
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        BlockPos pos = this.targetPos;
        if (pos == null || mc.player == null || mc.level == null || mc.gameMode == null) {
            return;
        }
        if (!this.isValid(pos)) {
            this.clearTarget();
            return;
        }
        // 先解决"从哪儿挖得进去"。没有任何角度能摸到这个方块 = 被遮挡（只看方块，
        // 不看实体），这时候继续攒进度只是在给服务端送一串挖不到的包，
        // 直接中止整个挖掘流程。
        if (this.rotate.getValue()) {
            if (!this.updateAim(pos)) {
                this.clearTarget();
                return;
            }
        } else if (this.targetRotation != null) {
            // 中途把 Rotate 关掉：必须立刻把转向交还，否则 RotationHandler 会继续把
            // 出站 yaw/pitch 锁在上一次算出来的瞄准点上，玩家的视角就转不动了。
            this.resetAim();
        }
        if (mc.player.onGround()) {
            this.landed = true;
        }
        // 服务端校验这次挖掘用的是**上一 tick 真正发出去的角度**：TickEvent 在 Minecraft.tick()
        // 的最前面，本 tick 的移动包还没发，所以线上此刻仍是 sentRotation。之前这里只按本地
        // 刚算出的瞄准角判断，线上角度和判定角不是一回事 —— 那就是 Grim 记这类检查的来源。
        //
        // Rotate 关着的时候不设这道闸：那时本模块的定位就是"我在看别处，包还指着方块"，
        // 拦下来它就没有存在意义了。开了 Rotate 才要求两个角度必须是同一个。
        if (this.rotate.getValue() && !this.hitsBlock(pos, mc.player.getEyePosition(1.0f), this.wireRotation())) {
            // 两种可能：一是自己还在转向的路上（下一两 tick 就到位），二是别的模块
            // （KillAura / Scaffold / AntiKB …）接管了出站朝向而它的角度打不到这个方块。
            // 两种都冻结：进度不涨、swing 不发、STOP 不发，但**不放弃目标** ——
            // 角度一回来就自动续上。发一个打不到方块的 break 包对服务端毫无用处，
            // 在 Grim 一侧纯粹是白送的告警。
            return;
        }
        float damage = this.tickDamage(pos, this.landed);
        if (damage <= 0.0f) {
            this.clearTarget();
            return;
        }
        this.accumulated = Math.min(1.0f, this.accumulated + damage);
        if (this.swingSetting.getValue()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        } else {
            PacketUtil.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        }
        if (!this.finishPending && this.accumulated >= 1.0f) {
            this.finishPending = true;
            return;
        }
        if (this.finishPending) {
            // 上面的闸已经保证线上角度确实打得到这个方块，STOP 可以带着同一套角度出门了。
            Direction face = this.resolveFace(pos);
            PacketUtil.sendPredictiveDirect(sequence -> new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face, sequence));
            this.clearTarget();
        }
    }

    /**
     * 维护本 tick 的出站朝向。
     *
     * @return false = 这个方块没有任何可挖的角度（被方块挡住），调用方应当中止挖掘
     */
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
            // 换了瞄准点就是换了一次目标，速度状态清零重新起步，别带着旧方向的惯性过去。
            this.smoother.reset();
        }
        Rotation aim = RotationUtil.rotationToPoint(eye, this.aimPoint);
        Rotation from = RotationHandler.prevRotation != null
                ? RotationHandler.prevRotation
                : new Rotation(mc.player.getYRot(), mc.player.getXRot());
        Rotation smoothed = this.smoother.tick(from, aim, this.rotationSpeed.getValue().doubleValue());
        // GCD 量化：Grim 的 AimProcessor 用连续增量的 GCD 反推鼠标灵敏度，所有出站增量必须
        // 落在真实灵敏度的网格上。KillAura / Scaffold 都走这一步，挖掘没理由例外 ——
        // 少了它就等于每 tick 送一个"人手不可能产生"的角增量。
        this.targetRotation = RotationSmoother.patchConstantRotation(smoothed, from);
        return true;
    }

    /**
     * 在方块表面找一个"从当前眼睛位置真的能打到它"的点。
     *
     * <p>候选点取朝玩家那一侧的面上（背面永远挖不到，白算），每个面 3×3 采样：
     * 面心 + 四分之一处的格子。分散采样是必要的 —— 面心可能正好被一根柱子/半砖挡住，
     * 而同一个面的角上却是通的。
     *
     * <p>评分用"相对当前发包朝向的角度差"（pitch 加权，因为上下看更贵），也就是优先走
     * 转得最少的那条路：转头更短、更顺，也更不像一次突然的视角跳变。
     */
    private Vec3 findAimPoint(BlockPos pos, Vec3 eye) {
        AABB box = BlockUtil.getBoundingBox(pos);
        Vec3 center = box.getCenter();
        double reach = this.range.getValue().doubleValue();
        Rotation current = RotationHandler.sentRotation != null
                ? RotationHandler.sentRotation
                : new Rotation(mc.player.getYRot(), mc.player.getXRot());
        Vec3 best = null;
        Direction bestFace = null;
        double bestScore = Double.MAX_VALUE;
        for (Direction face : DIRECTIONS) {
            // 玩家在面外侧才可能看到它：外法线与"眼睛 - 方块中心"同向。
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
                    // 进入面必须就是采样点所在的那一面：STOP 包里的 Direction 是服务端/
                    // 反作弊核对"你在挖哪一面"的依据。射线可能从上表面切进去而采样点落在
                    // 侧面上，那种候选点虽然打得到方块，报出去的面却是错的。
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

    /** 面上的采样点：法线方向固定在面上，另两轴按 u/v 在 {@link #FACE_INSET} 内插值。 */
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

    /**
     * 这条朝向的射线最先打到的方块是不是 {@code pos}；是就返回命中结果（带进入面）。
     *
     * <p>只用方块裁剪（{@link ClipContext.Block#OUTLINE}），射线里的实体一律不参与 ——
     * 站在别人脑袋后面挖方块不该被判定成"挖不到"。
     */
    private BlockHitResult traceBlock(BlockPos pos, Vec3 eye, Rotation rotation) {
        Vec3 direction = RotationUtil.directionFromRotation(rotation);
        // 多打半格：采样点就在方块表面上，正好clip 到表面时命中结果可能是浮点擦边。
        Vec3 end = eye.add(direction.scale(this.range.getValue().doubleValue() + 0.5));
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) ? hit : null;
    }

    private boolean hitsBlock(BlockPos pos, Vec3 eye, Rotation rotation) {
        return this.traceBlock(pos, eye, rotation) != null;
    }

    /**
     * 服务端手里此刻的朝向 —— 上一 tick 真正发出去的那个。
     *
     * <p>和 KillAura 的 {@code attackRotation()} 同一个理由：TickEvent 在 tick 的最前面，
     * 本 tick 的移动包还没发，所以此刻服务端校验交互用的就是上一 tick 的角度。
     */
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

    private float tickDamage(BlockPos pos, boolean forceGround) {
        BlockState blockState = mc.level.getBlockState(pos);
        float damage = blockState.getDestroyProgress(mc.player, mc.level, pos);
        if (forceGround && !mc.player.onGround()) {
            damage *= 5.0f;
        }
        return damage;
    }

    private boolean isRealViewTarget(BlockPos pos) {
        if (mc.hitResult instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(pos)) {
            return true;
        }
        Vec3 eye = mc.player.getEyePosition(1.0f);
        double reach = this.range.getValue().doubleValue();
        Vec3 end = eye.add(RotationUtil.directionFromRotation(this.realRotation()).scale(reach));
        return BlockUtil.getBoundingBox(pos).clip(eye, end).isPresent();
    }

    private Rotation realRotation() {
        return new Rotation(mc.player.getYRot(), mc.player.getXRot());
    }

    private Direction resolveFace(BlockPos pos) {
        // 有实测瞄准面就用它：STOP 包里的 Direction 是服务端校验"你在挖哪一面"的依据，
        // 而 aimFace 是这条射线真的打到的那个面，比按"眼睛→方块中心"猜准。
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
        // 按**最近点**量距离，不按方块中心。旧实现量的是 eye→中心，对满方块来说中心和最近的
        // 面之间还有约 0.87 格的斜距，于是 Range 填 4.5 时实际只能摸到 ~3.6 格 —— 这就是
        // "触发范围比设置的小"的来源。Range 的语义本来就是"准星到方块表面的距离"。
        double distance = RotationUtil.closestPoint(eye, BlockUtil.getBoundingBox(pos)).distanceTo(eye);
        if (distance > this.range.getValue().doubleValue()) {
            return false;
        }
        // 但服务端还有自己的一道闸：Player#canInteractWithBlock 量的是 eye→方块中心，
        // 上限是 blockInteractionRange(4.5) + 1.0。最近点过了线而中心没过线的话，
        // 本地会以为能挖、服务端直接不理，所以在这一层再兜一次。
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
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        RenderSystem.setShaderColor(red, green, blue, 0.85f);
        RenderUtil.drawOutlineBox(box, poseStack);
        if (this.showProgress.getValue() && this.accumulated > 0.0f) {
            RenderSystem.setShaderColor(red, green, blue, 0.35f);
            RenderUtil.drawSolidBox(this.progressBox(box), poseStack);
        }
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        poseStack.popPose();
    }

    private AABB progressBox(AABB box) {
        float progress = Math.max(0.0f, Math.min(1.0f, this.accumulated));
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
        this.landed = false;
        this.finishPending = false;
        // 停挖就必须把转向交还：留着 targetRotation 会让 RotationHandler 每 tick 继续把
        // 玩家的出站 yaw/pitch 锁在这个方块上，人就再也转不动视角了。
        this.resetAim();
    }
}
