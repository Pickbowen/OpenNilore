package shit.lizz.network;

import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.util.thread.BlockableEventLoop;
import org.slf4j.Logger;
import shit.lizz.LizzClient;
import shit.lizz.event.impl.ReceivePacketEvent;

public class PacketHandlerUtil {
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T extends PacketListener> void processPacket(Logger logger, Packet<T> packet, T listener, BlockableEventLoop<?> loop) throws RunningOnDifferentThreadException {
        if (loop.isSameThread()) {
            return;
        }
        loop.executeIfPossible(() -> {
            if (!listener.isAcceptingMessages()) {
                logger.debug("Ignoring packet due to disconnection: {}", packet);
                return;
            }
            try {
                ReceivePacketEvent event = new ReceivePacketEvent((Packet<ClientGamePacketListener>) (Packet) packet);
                if (loop.isSameThread() && LizzClient.isReady()) {
                    LizzClient.instance.getEventBus().call(event);
                    if (event.isCancelled()) {
                        return;
                    }
                }
                packet.handle(listener);
            } catch (Exception exception) {
                if (listener.shouldPropagateHandlingExceptions()) {
                    throw exception;
                }
                logger.error("Failed to handle packet {}, suppressing error", packet, exception);
            }
        });
        throw RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD;
    }
}
