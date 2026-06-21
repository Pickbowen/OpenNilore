package shit.nilore.modules.impl.misc;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import shit.nilore.settings.impl.NumberSetting;
import shit.nilore.utils.game.BlockUtil;

/**
 * Blockin: 在玩家周围放置方块形成保护壳 (surround)
 * 用于 SkyWars/BedWars 等场景下快速围住自己防止被水晶炸或被近战
 */
public class Blockin extends Module {

    private static final Direction[] HORIZONTALS = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    public final NumberSetting delay = new NumberSetting("Delay", 0, 0, 4, 1);
    public final BooleanSetting head = new BooleanSetting("Head Level", true);
    public final BooleanSetting rotate = new BooleanSetting("Rotate", true);

    private int tickCounter = 0;

    public Blockin() {
        super("Blockin", Category.MISC);
    }

    @Override
    protected void onEnable() {
        tickCounter = 0;
    }

    @Override
    protected void onDisable() {
        tickCounter = 0;
    }

    @EventTarget
    public void onMotion(MotionEvent event) {
        if (!event.isPre()) return;
        if (mc.player == null || mc.level == null) return;

        if (tickCounter > 0) {
            tickCounter--;
            return;
        }

        BlockPos playerPos = mc.player.blockPosition();
        int blockSlot = findBlockSlot();
        if (blockSlot == -1) return;

        int prevSlot = mc.player.getInventory().selected;
        boolean placed = false;

        // 先放脚下的四个方向
        for (Direction dir : HORIZONTALS) {
            BlockPos placePos = playerPos.relative(dir);
            if (tryPlace(placePos, blockSlot)) {
                placed = true;
            }
        }

        // 放头部位置
        if (head.getValue()) {
            BlockPos headPos = playerPos.above();
            for (Direction dir : HORIZONTALS) {
                BlockPos placePos = headPos.relative(dir);
                if (tryPlace(placePos, blockSlot)) {
                    placed = true;
                }
            }
        }

        // 放脚下 (可选, 防止被从下面打)
        BlockPos belowPos = playerPos.below();
        for (Direction dir : HORIZONTALS) {
            BlockPos placePos = belowPos.relative(dir);
            if (tryPlace(placePos, blockSlot)) {
                placed = true;
            }
        }

        if (placed) {
            mc.player.getInventory().selected = prevSlot;
            tickCounter = (int) delay.getValue();
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
}
