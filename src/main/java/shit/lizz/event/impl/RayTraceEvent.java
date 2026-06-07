package shit.lizz.event.impl;

import lombok.*;
import net.minecraft.world.entity.Entity;
import shit.lizz.event.EventMarker;

import java.util.Objects;

@Data
@AllArgsConstructor
public class RayTraceEvent
implements EventMarker {
    @Getter @Setter
    public Entity entity;
    @Getter @Setter
    public float yaw;
    @Getter @Setter
    public float pitch;
}