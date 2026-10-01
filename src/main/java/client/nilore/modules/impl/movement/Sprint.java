package client.nilore.modules.impl.movement;

import java.util.HashMap;

import client.nilore.event.impl.MotionEvent;
import net.minecraft.client.KeyMapping;
import client.nilore.event.impl.RotationEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.combat.KillAura;
import client.nilore.modules.impl.player.InventoryManager;
import client.nilore.event.EventTarget;

public class Sprint
extends Module {
    // private final HashMap<String, String> keyMappings = new HashMap<>();
    public Sprint() {
        super("Sprint", Category.MOVEMENT);
        this.setEnabled(true);
    }

    @EventTarget
    public void onRotation(RotationEvent rotationEvent) {
        if (InventoryManager.isPerformingAction) {
            return;
        }
        // KillAura stopSprint 交火停疾跑期间不再压疾跑键, 否则疾跑每 tick
        // 都被这里重新压上, "非疾跑攻击"永远不成立(NoXZ 击退收放期由 isBusy() 放行)
        if (KillAura.shouldStopSprint()) {
            mc.options.keySprint.setDown(false);
            mc.player.setSprinting(false);
            return;
        }
        mc.options.toggleSprint().set(false);
        KeyMapping.set(mc.options.keySprint.getKey(), true);
    }
}