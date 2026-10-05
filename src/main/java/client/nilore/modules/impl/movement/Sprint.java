package client.nilore.modules.impl.movement;

import java.util.HashMap;

import client.nilore.event.impl.MotionEvent;
import net.minecraft.client.KeyMapping;
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
        // Reference SprintModule: Stuck is a stop condition of its own, on top of KillAura's gate.
        if ((Stuck.INSTANCE != null && Stuck.INSTANCE.isEnabled()) || KillAura.shouldStopSprint()) {
            mc.options.keySprint.setDown(false);
            mc.player.setSprinting(false);
            return;
        }
        mc.options.toggleSprint().set(false);
        KeyMapping.set(mc.options.keySprint.getKey(), true);
        // Reference: while eating and moving forward the sprint flag is asserted directly.
        // Vanilla's own re-derivation refuses to start sprinting while an item is in use, so
        // without this the sprint is lost for the whole eat and the antikb counter (which
        // requires isSprinting(), like the reference) stalls on that gate and holds KillAura's
        // attack window shut with it.
        if (mc.player.isUsingItem() && mc.player.zza != 0) {
            mc.player.setSprinting(true);
        }
    }
}