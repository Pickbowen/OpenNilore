package shit.nilore.modules.impl.movement;

import shit.nilore.event.impl.SneakEvent;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.event.EventTarget;

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