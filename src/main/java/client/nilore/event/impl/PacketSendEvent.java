package client.nilore.event.impl;

import lombok.Getter;
import lombok.Generated;
import net.minecraft.network.protocol.Packet;
import client.nilore.event.Event;

public class PacketSendEvent
extends Event {
    @Getter
    private final Packet<?> packet;

    @Generated
    public PacketSendEvent(Packet<?> packet) {
        this.packet = packet;
    }
}