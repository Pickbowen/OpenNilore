package shit.nilore.event.impl;

import lombok.Getter;
import lombok.Setter;
import lombok.Generated;
import net.minecraft.network.protocol.Packet;
import shit.nilore.event.Event;

public class PacketEvent
extends Event {
    @Getter @Setter
    private Packet<?> packet;
    @Setter
    private boolean incoming;

    public boolean isIncoming() {
        return !this.incoming;
    }

    @Generated
    public boolean isIncomingRaw() {
        return this.incoming;
    }

    @Generated
    public PacketEvent(Packet<?> packet, boolean incoming) {
        this.packet = packet;
        this.incoming = incoming;
    }
}