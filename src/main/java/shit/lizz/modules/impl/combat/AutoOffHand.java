package shit.lizz.modules.impl.combat;

import java.util.concurrent.TimeUnit;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPickItemPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import shit.lizz.event.impl.TickEvent;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.settings.impl.BooleanSetting;
import shit.lizz.settings.impl.NumberSetting;
import shit.lizz.utils.misc.PacketUtil;
import shit.lizz.utils.misc.ThreadPool;
import shit.lizz.event.EventTarget;

public class AutoOffHand extends Module {
    public static AutoOffHand INSTANCE;

    public enum ItemType { SNOWBALL, GAPPLE, NONE }

    public final NumberSetting range = new NumberSetting("Range", 6.0, 1.0, 12.0, 0.5);
    public final BooleanSetting gapple = new BooleanSetting("Gapple", true);
    public final BooleanSetting snowball = new BooleanSetting("SnowBall", true);
    public final NumberSetting health = new NumberSetting("Health", 10.0, 1.0, 20.0, 0.5);
    public final BooleanSetting revertOnRegen = new BooleanSetting("RevertOnRegen", true);
    private int equipCooldown = 0;

    public AutoOffHand() {
        super("AutoOffHand", Category.COMBAT);
        INSTANCE = this;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || mc.player == null || mc.level == null
                || mc.player.isUsingItem()) return;
        if (mc.screen instanceof AbstractContainerScreen) return;
        if (this.equipCooldown > 0) {
            --this.equipCooldown;
            return;
        }
        boolean nearEnemy = this.isNearEnemy();
        ItemType desired = this.getDesiredItemType(nearEnemy);
        if (desired == ItemType.NONE) return;
        ItemStack mainHand = mc.player.getMainHandItem();
        if (this.isItemOfType(mainHand, desired)) return;
        ItemStack offHand = mc.player.getOffhandItem();
        if (this.isItemOfType(offHand, desired)) return;
        int slot = this.findItemSlot(desired);
        if (slot != -1) {
            this.equipOffhand(slot);
        }
    }

    private boolean hasEmptyHotbarSlot() {
        for (int i = 0; i < 9; ++i) {
            if (mc.player.getInventory().getItem(i).isEmpty()) return true;
        }
        return false;
    }

    private int getEmptyHotbarSlot() {
        for (int i = 0; i < 9; ++i) {
            if (mc.player.getInventory().getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    private boolean isNearEnemy() {
        if (mc.level == null || mc.player == null) return false;
        double rangeValue = this.range.getValue().doubleValue();
        return mc.level.players().stream().anyMatch(
                p -> !p.equals(mc.player)
                        && !p.isRemoved()
                        && mc.player.distanceToSqr(p) <= rangeValue * rangeValue
                        && this.hasLineOfSight(p));
    }

    private boolean hasLineOfSight(Entity entity) {
        if (mc.player == null || mc.level == null) return false;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 targetEye = entity.getEyePosition();
        return mc.level.clip(new ClipContext(eye, targetEye,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player)).getType() == HitResult.Type.MISS;
    }

    private ItemType getDesiredItemType(boolean nearEnemy) {
        if (mc.player.getHealth() < this.health.getValue().doubleValue()) {
            if (this.gapple.getValue()) return ItemType.GAPPLE;
        } else if (this.revertOnRegen.getValue() && mc.player.hasEffect(MobEffects.REGENERATION)) {
            if (this.snowball.getValue()) return ItemType.SNOWBALL;
        } else if (nearEnemy) {
            if (this.snowball.getValue()) return ItemType.SNOWBALL;
        } else if (this.gapple.getValue()) {
            return ItemType.GAPPLE;
        }
        return ItemType.NONE;
    }

    private boolean isItemOfType(ItemStack stack, ItemType type) {
        if (stack == null || stack.isEmpty()) return false;
        return switch (type) {
            case SNOWBALL -> stack.getItem() == Items.SNOWBALL || stack.getItem() == Items.EGG;
            case GAPPLE -> stack.getItem() == Items.GOLDEN_APPLE || stack.getItem() == Items.ENCHANTED_GOLDEN_APPLE;
            default -> false;
        };
    }

    private int findItemSlot(ItemType type) {
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); ++i) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (this.isItemOfType(stack, type)) return i;
        }
        return -1;
    }

    private int getPing() {
        if (mc.getConnection() == null
                || mc.getConnection().getPlayerInfo(mc.player.getUUID()) == null) {
            return 50;
        }
        return mc.getConnection().getPlayerInfo(mc.player.getUUID()).getLatency();
    }

    private void equipOffhand(int slot) {
        if (slot == -1 || this.equipCooldown > 0) return;
        if (mc.screen instanceof AbstractContainerScreen) return;
        if (slot >= 9 && this.getEmptyHotbarSlot() == -1) return;
        mc.options.keyUse.setDown(false);
        final int savedSlot = mc.player.getInventory().selected;
        if (slot >= 9) {
            PacketUtil.sendQueued(new ServerboundPickItemPacket(slot));
            PacketUtil.sendQueued(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
            ThreadPool.scheduleWithDelay(() -> mc.execute(() -> {
                mc.player.getInventory().selected = savedSlot;
            }), 200L, TimeUnit.MILLISECONDS);
        } else {
            mc.player.getInventory().selected = slot;
            PacketUtil.sendQueued(new ServerboundSetCarriedItemPacket(mc.player.getInventory().selected));
            PacketUtil.sendQueued(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
            PacketUtil.sendQueued(new ServerboundSetCarriedItemPacket(savedSlot));
            mc.player.getInventory().selected = savedSlot;
        }
        ThreadPool.submit(() -> this.equipCooldown = (int) (3.0f + this.getPing() / 25.0f));
    }
}
