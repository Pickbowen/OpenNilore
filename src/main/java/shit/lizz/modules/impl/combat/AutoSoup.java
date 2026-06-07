package shit.lizz.modules.impl.combat;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import shit.lizz.event.impl.SprintEvent;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.settings.impl.BooleanSetting;
import shit.lizz.settings.impl.NumberSetting;
import shit.lizz.utils.animation.Timer;
import shit.lizz.utils.game.ItemUtil;
import shit.lizz.utils.game.PlayerUtil;
import shit.lizz.event.EventTarget;

public class AutoSoup
extends Module {
    public static AutoSoup INSTANCE;
    private final NumberSetting health = new NumberSetting("Health", 15, 0, 20, 1);
    private final NumberSetting delay = new NumberSetting("Delay", 300, 0, 1000, 1);
    private final NumberSetting switchDelay = new NumberSetting("Switch Delay", 100, 0, 1000, 1);
    private final BooleanSetting drop = new BooleanSetting("Drop", true);
    private final Timer switchDelayTimer = new Timer();
    private final Timer delayTimer = new Timer();
    private int prevSelectedSlot = -1;
    public boolean isUsingSoup;

    public AutoSoup() {
        super("AutoSoup", Category.COMBAT);
        INSTANCE = this;
    }

    @Override
    protected void onDisable() {
        this.prevSelectedSlot = -1;
        this.isUsingSoup = false;
    }

    @EventTarget
    public void onSprint(SprintEvent sprintEvent) {
        if (mc.player == null || mc.level == null || mc.screen != null) {
            return;
        }
        if (this.switchDelayTimer.hasPassed(this.switchDelay.getValue().longValue()) && this.prevSelectedSlot != -1) {
            mc.player.getInventory().selected = this.prevSelectedSlot;
            PlayerUtil.sendCarriedItem();
            this.prevSelectedSlot = -1;
            this.delayTimer.reset();
        }
        if (!this.delayTimer.hasPassed(this.delay.getValue().longValue())) {
            return;
        }
        int soupSlot = ItemUtil.findItemInRange(0, 9, Items.MUSHROOM_STEW);
        if (mc.player.getHealth() <= this.health.getValue().floatValue() && soupSlot != -1) {
            boolean alreadySelected = mc.player.getInventory().selected == soupSlot;
            if (!alreadySelected) {
                this.prevSelectedSlot = mc.player.getInventory().selected;
                mc.player.getInventory().selected = soupSlot;
                PlayerUtil.sendCarriedItem();
                this.switchDelayTimer.reset();
            }
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            this.isUsingSoup = true;
            if (this.drop.getValue()) {
                mc.player.drop(true);
            }
            this.delayTimer.reset();
        } else {
            this.isUsingSoup = false;
        }
    }
}