package client.nilore.modules.impl.movement;

import java.util.HashMap;

import client.nilore.event.impl.MotionEvent;
import client.nilore.event.impl.SprintEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.combat.KillAura;
import client.nilore.modules.impl.player.InventoryManager;
import client.nilore.modules.impl.player.Stuck;
import client.nilore.event.EventTarget;

public class Sprint
extends Module {
    // private final HashMap<String, String> keyMappings = new HashMap<>();

    public Sprint() {
        super("Sprint", Category.MOVEMENT);
        this.setEnabled(true);
    }

    @EventTarget
    public void onSprint(SprintEvent sprintEvent) {
        if (mc.player == null || InventoryManager.isPerformingAction) {
            return;
        }
        if (Stuck.INSTANCE != null && Stuck.INSTANCE.isEnabled() || KillAura.shouldStopSprint()) {
            mc.options.keySprint.setDown(false);
            mc.player.setSprinting(false);
            KillAura.INSTANCE.markSprintReleased();
            return;
        }
        if (KillAura.INSTANCE.isSprintReleased()) {
            mc.options.keySprint.setDown(false);
            mc.player.setSprinting(false);
            return;
        }
        mc.options.toggleSprint().set(false);
        mc.options.keySprint.setDown(true);
        if (mc.player.zza != 0) {
            mc.player.setSprinting(true);
        }
    }
}