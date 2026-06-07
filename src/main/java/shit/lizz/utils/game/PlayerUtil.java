package shit.lizz.utils.game;

import com.mojang.blaze3d.platform.InputConstants;
import java.lang.reflect.Method;
import javax.annotation.Nullable;
import lombok.Generated;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import shit.lizz.ClientBase;
import shit.lizz.utils.game.RotationUtil;
import shit.lizz.utils.misc.ChatUtil;
import shit.lizz.utils.misc.ReflectionUtil;
import shit.lizz.utils.rotation.Rotation;

public final class PlayerUtil
extends ClientBase {
    public static void updateWalkAnim() {
        if (mc.player == null) {
            return;
        }
        mc.player.walkAnimation.setSpeed(mc.player.walkAnimation.speed());
        double dx = mc.player.getX() - mc.player.xo;
        double dz = mc.player.getZ() - mc.player.zo;
        float speed = Mth.sqrt((float)(dx * dx + dz * dz)) * 4.0f;
        speed = Mth.clamp(speed, 0.0f, 1.0f);
        mc.player.walkAnimation.update(speed, 0.4f);
    }

    public static void sendCarriedItem() {
        try {
            Method method = mc.gameMode.getClass().getDeclaredMethod(ReflectionUtil.getMappedMethodName(mc.gameMode.getClass(), "ensureHasSentCarriedItem", "()V"));
            method.setAccessible(true);
            method.invoke(mc.gameMode);
        } catch (Exception exception) {
            exception.printStackTrace();
            ChatUtil.print("Failed to set item!");
        }
    }

    public static boolean isSafe(double maxFall) {
        int offset = 0;
        while ((double)offset < maxFall) {
            AABB aABB = mc.player.getBoundingBox().move(0.0, -offset, 0.0);
            if (!PlayerUtil.isNoClip(mc.player, aABB)) {
                return true;
            }
            offset += 2;
        }
        return false;
    }

    public static boolean isNoClip(@Nullable Entity entity, AABB aABB) {
        for (VoxelShape voxelShape : mc.level.getBlockCollisions(entity, aABB)) {
            if (voxelShape.isEmpty()) continue;
            return false;
        }
        return true;
    }

    public static void click(int button, boolean pressed) {
        InputConstants.Key key = button == 0 ? mc.options.keyAttack.getKey() : mc.options.keyUse.getKey();
        KeyMapping.set(key, pressed);
        if (pressed) {
            KeyMapping.click(key);
        }
    }

    public static int getArmorPoints(LivingEntity livingEntity) {
        int totalDefense = 0;
        for (ItemStack itemStack : livingEntity.getArmorSlots()) {
            Item item = itemStack.getItem();
            if (!(item instanceof ArmorItem armorItem)) continue;
            totalDefense += armorItem.getDefense();
        }
        return totalDefense;
    }

    public static Block getBlock(double x, double y, double z) {
        return PlayerUtil.getBlock(BlockPos.containing(x, y, z));
    }

    public static Block getBlock(BlockPos blockPos) {
        if (mc.level == null || mc.player == null) {
            return null;
        }
        return mc.level.getBlockState(blockPos).getBlock();
    }

    public static Block getBlockBelow(Player player) {
        return PlayerUtil.getBlock(BlockPos.containing(player.getX(), player.getY() - 1.0, player.getZ()));
    }

    public static HitResult rayTrace(double range, float yaw, float pitch) {
        if (mc.player == null || mc.level == null) {
            return null;
        }
        Vec3 eyePos = mc.player.getEyePosition(1.0f);
        Vec3 lookDir = RotationUtil.directionFromRotation(new Rotation(yaw, pitch));
        Vec3 endPos = eyePos.add(lookDir.x * range, lookDir.y * range, lookDir.z * range);
        return mc.level.clip(new ClipContext(eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
    }

    public static boolean isSafeToScaffold() {
        if (mc.player == null || mc.level == null) {
            return true;
        }
        Vec3 playerPos = mc.player.position();
        BlockPos frontPos = new BlockPos((int)Math.floor(playerPos.x + Math.sin(Math.toRadians(mc.player.getYRot())) * 0.8), (int)Math.floor(playerPos.y), (int)Math.floor(playerPos.z + -Math.cos(Math.toRadians(mc.player.getYRot())) * 0.8));
        if (mc.level.getBlockState(frontPos).is(Blocks.LAVA)) {
            return false;
        }
        BlockPos belowPos = frontPos.below();
        BlockState belowState = mc.level.getBlockState(belowPos);
        if (belowState.is(Blocks.LAVA)) {
            return false;
        }
        if (belowState.isAir()) {
            if (belowPos.getY() < mc.level.getMinBuildHeight()) {
                return false;
            }
            if (mc.level.getBlockState(belowPos.below()).isAir()) {
                return belowPos.below().getY() >= mc.level.getMinBuildHeight() && !mc.level.getBlockState(belowPos.below().below()).isAir();
            }
        }
        return true;
    }

    @Generated
    private PlayerUtil() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
