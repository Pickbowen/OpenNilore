package client.nilore.modules.impl.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.RedStoneOreBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import client.nilore.event.impl.MotionEvent;
import client.nilore.event.impl.UpdateHeldItemEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.game.ItemUtil;
import client.nilore.event.EventTarget;

public class AutoTools
extends Module {
    private final BooleanSetting checkSword = new BooleanSetting("Check Sword", false);
    private final BooleanSetting switchBack = new BooleanSetting("Switch Back", true);
    private final BooleanSetting silent = new BooleanSetting("Silent", true);
    private final NumberSetting delay = new NumberSetting("Delay", 0, 0, 3, 1);
    public static String[] toolNames;
    private int previousSlot = -1;
    private int switchTicks;
    private BlockPos lastTarget;

    public AutoTools() {
        super("AutoTools", Category.WORLD);
    }

    public static Object getInstance() {
        try {
            return Class.forName((String)toolNames[0]).getMethod(toolNames[1], String.class);
        } catch (Exception exception) {
            return null;
        }
    }

    @EventTarget
    public void onUpdateHeldItem(UpdateHeldItemEvent updateHeldItemEvent) {
        if (mc.player == null) {
            return;
        }
        if (this.switchBack.getValue() && this.silent.getValue() && updateHeldItemEvent.getHand() == InteractionHand.MAIN_HAND && this.previousSlot != -1) {
            updateHeldItemEvent.setItemStack(mc.player.getInventory().getItem(this.previousSlot));
        }
    }

    @EventTarget
    public void onMotion(MotionEvent motionEvent) {
        if (mc.player == null || mc.gameMode == null) {
            return;
        }
        if (motionEvent.isPost()) {
            if (!mc.gameMode.isDestroying()) {
                this.switchTicks = 0;
                this.lastTarget = null;
                return;
            }
            int bestSlot;
            BlockHitResult blockHit;
            if (this.checkSword.getValue() && mc.player.getMainHandItem().getItem() instanceof SwordItem) {
                return;
            }
            if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK || (bestSlot = this.getBestTool((blockHit = (BlockHitResult)mc.hitResult).getBlockPos())) == -1 || bestSlot == mc.player.getInventory().selected) {
                this.switchTicks = 0;
                this.lastTarget = null;
                return;
            }
            if (blockHit.getBlockPos().equals(this.lastTarget)) {
                ++this.switchTicks;
            } else {
                this.lastTarget = blockHit.getBlockPos();
                this.switchTicks = 0;
            }
            if (this.switchTicks >= this.delay.getValue().intValue()) {
                this.previousSlot = mc.player.getInventory().selected;
                mc.player.getInventory().selected = bestSlot;
                this.switchTicks = 0;
                this.lastTarget = null;
            }
        } else if (!mc.gameMode.isDestroying() && this.switchBack.getValue() && this.previousSlot != -1) {
            mc.player.getInventory().selected = this.previousSlot;
            this.previousSlot = -1;
        }
    }

    private int getBestTool(BlockPos blockPos) {
        BlockState blockState = mc.level.getBlockState(blockPos);
        Block block = blockState.getBlock();
        int bestSlot = 0;
        float bestSpeed = 1.0f;
        for (int i = 0; i < 9; ++i) {
            int efficiencyLevel;
            ItemStack itemStack = mc.player.getInventory().getItem(i);
            if (ItemUtil.isWeaponItem(itemStack) || itemStack.isEmpty() || blockState.isAir() || itemStack.getItem() instanceof SwordItem && !(block instanceof WebBlock)) continue;
            float destroySpeed = itemStack.getItem().getDestroySpeed(itemStack, blockState);
            if (destroySpeed > 1.0f && !(block instanceof DropExperienceBlock) && !(block instanceof RedStoneOreBlock) && (efficiencyLevel = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_EFFICIENCY, itemStack)) > 0) {
                destroySpeed += (float)(efficiencyLevel * efficiencyLevel + 1);
            }
            if (!(destroySpeed > bestSpeed)) continue;
            bestSlot = i;
            bestSpeed = destroySpeed;
        }
        if (bestSpeed > 1.0f) {
            return bestSlot;
        }
        return -1;
    }
}