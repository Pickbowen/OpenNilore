package shit.nilore.event.impl;

import lombok.Getter;
import lombok.Generated;
import net.minecraft.network.protocol.Packet;
import shit.nilore.event.Event;

public class PrePacketEvent
extends Event {
    @Getter
    private final Packet<?> packet;

    @Generated
    public PrePacketEvent(Packet<?> packet) {
        this.packet = packet;
    }
}