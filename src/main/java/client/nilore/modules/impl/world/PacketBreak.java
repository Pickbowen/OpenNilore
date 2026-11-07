package client.nilore.modules.impl.world;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
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

/**
 * PacketBreak —— 点一下方块，之后由客户端替你把它挖完。
 * <p>
 * 选取只用玩家真实视角, 模块<b>不发任何旋转包、也不接管 RotationHandler</b>:
 * 点击那一刻原版 pick 命中该方块(或真实视线射线打到它的碰撞箱)才接受为新目标。
 * 因为视角始终是玩家自己的, 点别的方块必定产生一次新的 START, 目标就跟着真实视角换;
 * 之前版本把视角静默压到正在挖的方块上, 于是每次点击的 pick 又被拉回那个位置, 换不了目标。
 * <p>
 * 选中之后不要求视线持续对着方块, 想换目标必须重新点一下另一个方块。
 * <p>
 * 进度按 tick 累加 {@code getDestroyProgress} 的实时值: 中途落地、换工具、踩水都会立刻反映到
 * 剩余时间上。1.20.1 {@code Player.getDestroySpeed} 末尾是 {@code if (!onGround) f /= 5},
 * 所以"本次挖掘只要到过地面"之后就用 {@code 当前值 * 5} 当地面速度继续累加, 之后即使再跳起来也不回退。
 * <p>
 * 发包时序仍是原版节奏: 服务端 {@code ServerPlayerGameMode.m_214168_} 里 START 只记
 * {@code destroyProgressStart}, 结算在 STOP
 * ({@code progress = getDestroyProgress * (gameTicks - startTick + 1)}, {@code >= 0.7} 立刻摧毁)。
 * 累计满 1.0 后再等 1 tick 才发 STOP, 给 Grim FastBreak 留余量(迟到只让 buffer 衰减)。
 * STOP 的 face 按当前眼睛位置重算, 满足 Grim PositionBreakA 的朝向判定。
 */
public class PacketBreak
extends Module {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final double MAX_SERVER_BLOCK_DISTANCE = 5.5;

    private final NumberSetting range = new NumberSetting("Range", 4.5, 1.0, 6.0, 0.1);
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

    public PacketBreak() {
        super("PacketBreak", Category.WORLD);
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
        // 本次挖掘只要落过地, 之后一直按地面速度算(再跳起来也不回退)。
        if (mc.player.onGround()) {
            this.landed = true;
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
                // 原版挖穿时 (continueDestroyBlock → 预测 lambda m_233736_) 也是先本地
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
            poseStack.translate((double)pos.getX() + 0.5 - camera.x, (double)pos.getY() + 0.5 - camera.y, (double)pos.getZ() + 0.5 - camera.z);
            poseStack.mulPose(mc.gameRenderer.getMainCamera().rotation());
            poseStack.scale(-0.025f, -0.025f, 0.025f);
            float textWidth = mc.font.width(label);
            mc.font.drawInBatch(label, -textWidth / 2.0f, -mc.font.lineHeight / 2.0f, -1, false,
                    poseStack.last().pose(), mc.renderBuffers().bufferSource(),
                    Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
            poseStack.popPose();
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
    }
}
