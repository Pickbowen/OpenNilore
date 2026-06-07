package shit.lizz.event.impl;

import lombok.Getter;
import lombok.Generated;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import shit.lizz.event.Event;

public class ReceivePacketEvent
extends Event {
    @Getter
    private final Packet<ClientGamePacketListener> packet;

    @Generated
    public ReceivePacketEvent(Packet<ClientGamePacketListener> packet) {
        this.packet = packet;
    }
}