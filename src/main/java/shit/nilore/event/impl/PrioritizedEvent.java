package shit.nilore.event.impl;

import shit.nilore.event.EventMarker;
import shit.nilore.event.Prioritized;

public abstract class PrioritizedEvent
implements Prioritized,
EventMarker {
    private final byte priority;

    protected PrioritizedEvent(byte by) {
        this.priority = by;
    }

    @Override
    public byte getPriority() {
        return this.priority;
    }
}