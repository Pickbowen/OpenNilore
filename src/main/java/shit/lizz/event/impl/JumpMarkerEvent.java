package shit.lizz.event.impl;

import lombok.AllArgsConstructor;
import lombok.Data;
import shit.lizz.event.EventMarker;

@Data
@AllArgsConstructor
public class JumpMarkerEvent
implements EventMarker {
    private float yaw;
}