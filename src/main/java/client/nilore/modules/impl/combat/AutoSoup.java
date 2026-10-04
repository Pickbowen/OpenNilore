package client.nilore.modules.impl.combat;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import client.nilore.event.impl.SprintEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.animation.Timer;
import client.nilore.utils.game.ItemUtil;
import client.nilore.utils.game.PlayerUtil;
import client.nilore.event.EventTarget;

public class AutoSoup
extends Module {
    public static AutoSoup INSTANCE;
    private final NumberSetting health = new NumberSetting("Health", 15, 0, 20, 1);
    private final NumberSetting delay = new NumberSetting("Delay", 300, 0, 1000, 1);
    private final BooleanSetting drop = new BooleanSetting("Drop", true);
    private final Timer delayTimer = new Timer();
    private int prevSelectedSlot = -1;
    private int currentSoupSlot = -1;
    public boolean isUsingSoup;

    public AutoSoup() {
        super("AutoSoup", Category.COMBAT);
        INSTANCE = this;
    }

    @Override
    protected void onDisable() {
        this.prevSelectedSlot = -1;
        this.currentSoupSlot = -1;
        this.isUsingSoup = false;
    }

    @EventTarget
    public void onSprint(SprintEvent sprintEvent) {
        if (mc.player == null || mc.level == null || mc.screen != null) {
            return;
        }
        if (this.prevSelectedSlot != -1) {
            if (mc.player.getInventory().selected != this.currentSoupSlot) {
                this.prevSelectedSlot = -1;
                this.currentSoupSlot = -1;
                this.isUsingSoup = false;
                this.delayTimer.reset();
                return;
            }
            ItemStack carried = mc.player.getInventory().getSelected();
            if (carried.getItem() != Items.MUSHROOM_STEW) {
                if (this.drop.getValue() && carried.getItem() == Items.BOWL) {
                    mc.player.drop(true);
                }
                mc.player.getInventory().selected = this.prevSelectedSlot;
                PlayerUtil.sendCarriedItem();
                this.prevSelectedSlot = -1;
                this.currentSoupSlot = -1;
                this.isUsingSoup = false;
                this.delayTimer.reset();
            }
            return;
        }
        if (!this.delayTimer.hasPassed(this.delay.getValue().longValue())) {
            return;
        }
        int foundSlot = ItemUtil.findItemInRange(0, 9, Items.MUSHROOM_STEW);
        if (mc.player.getHealth() <= this.health.getValue().floatValue() && foundSlot != -1) {
            this.prevSelectedSlot = mc.player.getInventory().selected;
            this.currentSoupSlot = foundSlot;
            mc.player.getInventory().selected = foundSlot;
            PlayerUtil.sendCarriedItem();
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            this.isUsingSoup = true;
            this.delayTimer.reset();
        } else {
            this.isUsingSoup = false;
        }
    }
}