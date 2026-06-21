package shit.nilore.modules.impl.misc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import shit.nilore.event.EventTarget;
import shit.nilore.event.impl.MotionEvent;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.settings.impl.BooleanSetting;
import shit.nilore.settings.impl.ModeSetting;
import shit.nilore.settings.impl.NumberSetting;
import shit.nilore.utils.game.BlockUtil;

/**
 * Blockin — 在玩家周围放置方块形成保护壳 (surround)
 *
 * 基于 LiquidBounce NextGen ModuleBlockIn 逻辑移植
 * 核心机制: 记录起始位置，按顺序在周围放置方块，放满自动关闭
 *
 * 支持放置顺序: Normal, Random, BottomTop, TopBottom
 * 自动检测玩家移动，移出范围自动禁用
 */
public class Blockin extends Module {

    private final ModeSetting placeOrder = new ModeSetting("Place Order", "Normal", "Normal", "Random", "BottomTop", "TopBottom").withDefault("Normal");
    public final NumberSetting delay = new NumberSetting("Delay", 0, 0, 4, 1);
    public final BooleanSetting head = new BooleanSetting("Head Level", true);
    public final BooleanSetting rotate = new BooleanSetting("Rotate", true);
    private final BooleanSetting autoDisable = new BooleanSetting("Auto Disable", true);

    private BlockPos startPos = BlockPos.ZERO;
    private boolean rotateClockwise = false;
    private List<BlockPos> blockList = Collections.emptyList();
    private int tickCounter = 0;

    public Blockin() {
        super("Blockin", Category.MISC);
    }

    @Override
    protected void onEnable() {
        if (mc.player == null) return;
        startPos = mc.player.blockPosition();
        rotateClockwise = Math.random() < 0.5;
        blockList = getPositions();
        tickCounter = 0;
    }

    @Override
    protected void onDisable() {
        startPos = BlockPos.ZERO;
        blockList = Collections.emptyList();
        tickCounter = 0;
    }

    @EventTarget
    public void onMotion(MotionEvent event) {
        if (!event.isPre()) return;
        if (mc.player == null || mc.level == null) return;

        // 检测玩家是否移动了位置
        BlockPos currentPos = mc.player.blockPosition();
        if (!currentPos.equals(startPos) && !currentPos.equals(startPos.above())) {
            if (autoDisable.getValue()) {
                this.setEnabled(false);
            }
            return;
        }

        if (tickCounter > 0) {
            tickCounter--;
            return;
        }

        int blockSlot = findBlockSlot();
        if (blockSlot == -1) return;

        boolean allFilled = true;
        for (BlockPos pos : blockList) {
            if (!BlockUtil.isEmpty(pos)) continue;

            allFilled = false;
            if (tryPlace(pos, blockSlot)) {
                tickCounter = (int) delay.getValue();
                return;
            }
        }

        if (allFilled && autoDisable.getValue()) {
            this.setEnabled(false);
        }
    }

    private boolean tryPlace(BlockPos pos, int blockSlot) {
        if (mc.level == null || mc.player == null) return false;
        if (!BlockUtil.isEmpty(pos)) return false;

        // 寻找相邻实体方块作为支撑面
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            if (!BlockUtil.isSolid(neighbor)) continue;

            // 检查距离 (4.5 格)
            Vec3 placeVec = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (mc.player.getEyePosition().distanceTo(placeVec) > 4.5) continue;

            // 切换物品栏
            mc.player.getInventory().selected = blockSlot;

            // 计算点击向量
            Vec3 hitVec = new Vec3(
                    neighbor.getX() + 0.5 + dir.getStepX() * 0.5,
                    neighbor.getY() + 0.5 + dir.getStepY() * 0.5,
                    neighbor.getZ() + 0.5 + dir.getStepZ() * 0.5
            );

            BlockHitResult hitResult = new BlockHitResult(hitVec, dir, neighbor, false);

            // 旋转
            if (rotate.getValue()) {
                float[] rot = calcRotation(hitVec);
                mc.player.setYRot(rot[0]);
                mc.player.setXRot(rot[1]);
            }

            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
            mc.player.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        return false;
    }

    private int findBlockSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) continue;
            if (BlockUtil.isPlaceable(stack)) return i;
        }
        return -1;
    }

    private float[] calcRotation(Vec3 target) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, dist));
        return new float[]{yaw, pitch};
    }

    // --- 位置生成 (基于 LiquidBounce Order 逻辑) ---

    private List<BlockPos> getPositions() {
        String order = placeOrder.getValue();
        switch (order) {
            case "Random":
                List<BlockPos> shuffled = getNormalPositions();
                Collections.shuffle(shuffled);
                return shuffled;
            case "BottomTop":
                List<BlockPos> bottomTop = getNormalPositions();
                bottomTop.sort((a, b) -> Integer.compare(a.getY(), b.getY()));
                return bottomTop;
            case "TopBottom":
                List<BlockPos> topBottom = getNormalPositions();
                topBottom.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
                return topBottom;
            default:
                return getNormalPositions();
        }
    }

    private List<BlockPos> getNormalPositions() {
        int playerHeight = Mth.ceil(mc.player.getBbHeight());
        List<BlockPos> result = new ArrayList<>();

        // 脚下
        result.add(startPos.below());

        // 四个水平方向 × 玩家高度
        Direction direction = mc.player.getDirection();
        for (int i = 0; i < 4; i++) {
            BlockPos sidePos = startPos.relative(direction);
            for (int h = 0; h < playerHeight; h++) {
                result.add(sidePos.above(h));
            }
            direction = rotateClockwise ? direction.getClockWise() : direction.getCounterClockWise();
        }

        // 头顶
        result.add(startPos.above(playerHeight));

        return result;
    }
}
