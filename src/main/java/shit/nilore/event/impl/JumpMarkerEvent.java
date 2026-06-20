package shit.nilore.event.impl;

import lombok.AllArgsConstructor;
import lombok.Data;
import shit.nilore.event.EventMarker;

@Data
@AllArgsConstructor
public class JumpMarkerEvent
implements EventMarker {
    private float yaw;
}