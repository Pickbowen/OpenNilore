package client.nilore.event.impl;

import client.nilore.event.Event;

/**
 * Fired from the {@code setSprinting} / {@code setDeltaMovement} call sites inside
 * {@code Player.attack} (see {@link client.nilore.patch.PlayerPatch}). Cancelling skips the
 * vanilla call entirely:
 * <ul>
 *   <li>{@code sprintReset == true}  — the {@code setSprinting(false)} that kills sprint on
 *       every swing;</li>
 *   <li>{@code sprintReset == false} — the {@code 0.6x} attack friction on the movement vector.</li>
 * </ul>
 * Ported from Naven's {@code tech.naven.events.impl.EventAttackSlowdown}.
 */
public class EventAttackSlowdown extends Event {
    private final boolean sprintReset;

    public EventAttackSlowdown(boolean sprintReset) {
        this.sprintReset = sprintReset;
    }

    public boolean isSprintReset() {
        return this.sprintReset;
    }
}
