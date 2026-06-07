package shit.lizz.event;

import shit.lizz.event.Cancellable;
import shit.lizz.event.EventMarker;

public abstract class Event
implements Cancellable,
EventMarker {
    public boolean cancelled;

    protected Event() {
    }

    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}