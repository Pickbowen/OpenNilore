package shit.lizz.event.impl;

import lombok.*;
import shit.lizz.event.EventMarker;

@Data
@AllArgsConstructor
public class FallFlyingEvent
implements EventMarker {
    @Getter @Setter
    private float pitch;
}