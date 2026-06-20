package shit.nilore.event.impl;

import lombok.*;
import shit.nilore.event.EventMarker;

@Data
@AllArgsConstructor
public class FallFlyingEvent
implements EventMarker {
    @Getter @Setter
    private float pitch;
}