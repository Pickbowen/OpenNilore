package shit.lizz.modules.impl.movement;

import shit.lizz.event.impl.SneakEvent;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.event.EventTarget;

public class NoPush
extends Module {
    public NoPush() {
        super("NoPush", Category.MOVEMENT);
    }

    @EventTarget
    public void onSneak(SneakEvent sneakEvent) {
        if (!FireballBlink.INSTANCE.isEnabled()) {
            sneakEvent.setCancelled(true);
        }
    }
}