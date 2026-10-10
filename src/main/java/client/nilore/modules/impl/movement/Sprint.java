package client.nilore.modules.impl.movement;

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

    public Sprint() {
        super("Sprint", Category.MOVEMENT);
        this.setEnabled(true);
    }

    /**
     * The reference runs this at HIGHEST while the aura's own sprint handler sits at the default
     * priority. That ordering is the whole point: the aura releases the sprint first, then this
     * handler runs and must decide again. If it re-pressed the key here the sprint would go off and
     * on inside one tick, sendIsSprintingIfNeeded would emit nothing (isSprinting matches
     * wasSprinting at both ends) while the client had already moved at +30%, and that mismatch is
     * exactly the .029 Simulation offset. Running last is what makes the release stick.
     */
    @EventTarget(value = 0)
    public void onSprint(SprintEvent sprintEvent) {
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
        if (mc.player.zza != 0) {
            mc.player.setSprinting(true);
        }
    }

    /**
     * Ported from the reference's holdSprint(): StuckModule.isEnabled() || canCrit(). The two
     * releases are deliberately not the same: the crit gate only lets go for this tick, while the
     * aura's own release latches sprintCancelled so the w-tap window keeps the sprint off until the
     * player is airborne again. Without the latch the aura would have no w-tap at all - the flag is
     * only cleared once !isSprintReleaseWindow().
     */
    private boolean shouldHoldSprint() {
        if (Stuck.INSTANCE != null && Stuck.INSTANCE.isEnabled()) {
            return true;
        }
        if (KillAura.INSTANCE != null && KillAura.INSTANCE.canCritUnderVelocity()) {
            return true;
        }
        if (KillAura.shouldStopSprint()) {
            KillAura.INSTANCE.markSprintReleased();
            return true;
        }
        return KillAura.INSTANCE != null && KillAura.INSTANCE.isSprintReleased();
    }
}