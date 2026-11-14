package client.nilore.modules.impl.movement;

import client.nilore.event.impl.TickEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.player.InventoryManager;
import client.nilore.modules.impl.player.Stuck;
import client.nilore.event.EventTarget;

public class Sprint extends Module {

    public Sprint() {
        super("Sprint", Category.MOVEMENT);
        this.setEnabled(true);
    }

    @EventTarget(value = 0)
    public void onTick(TickEvent event) {
        if (mc.player == null || InventoryManager.isPerformingAction) {
            return;
        }
        if (this.shouldHoldSprint()) {
            mc.options.keySprint.setDown(false);
            mc.player.setSprinting(false);
            return;
        }
        mc.options.toggleSprint().set(false);
        mc.options.keySprint.setDown(true);
    }

    private boolean shouldHoldSprint() {
        return Stuck.INSTANCE != null && Stuck.INSTANCE.isEnabled();
    }

    @Override
    public void onDisable() {
        mc.options.keySprint.setDown(false);
    }
}
