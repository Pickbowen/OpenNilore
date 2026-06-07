package shit.lizz.event.impl;

import lombok.Getter;
import lombok.Generated;
import net.minecraft.network.protocol.Packet;
import shit.lizz.event.Event;

public class PrePacketEvent
extends Event {
    @Getter
    private final Packet<?> packet;

    @Generated
    public PrePacketEvent(Packet<?> packet) {
        this.packet = packet;
    }
}