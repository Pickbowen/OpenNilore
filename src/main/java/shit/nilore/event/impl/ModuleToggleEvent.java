package shit.nilore.event.impl;

import shit.nilore.event.EventMarker;
import shit.nilore.modules.Module;

public record ModuleToggleEvent(Module module, boolean enabled) implements EventMarker {
}
