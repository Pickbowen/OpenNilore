package shit.nilore.utils.misc;

import java.lang.reflect.Field;
import java.util.ArrayList;
import lombok.Generated;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ClientboundGameProfilePacket;
import net.minecraft.network.protocol.status.ClientboundPongResponsePacket;
import shit.nilore.ClientBase;
import shit.nilore.NiloreClient;
import shit.nilore.event.impl.PacketSendEvent;
import shit.nilore.utils.misc.ReflectionUtil;

public final class PacketUtil
extends ClientBase {
    public static final ArrayList<Packet<ServerGamePacketListener>> queuedPackets = new ArrayList<>();

    public static boolean shouldBypass(Packet<ServerGamePacketListener> packet) {
        PacketSendEvent packetSendEvent = new PacketSendEvent(packet);
        NiloreClient.getInstance().getEventBus().call(packetSendEvent);
        if (packetSendEvent.isCancelled()) {
            return true;
        }
        if (queuedPackets.contains(packet)) {
            queuedPackets.remove(packet);
            return true;
        }
        return false;
    }

    public static void sendPredictive(PredictiveAction predictiveAction) {
        if (mc.getConnection() == null || mc.level == null) {
            return;
        }
        try {
            String fieldName = ReflectionUtil.getMappedFieldName(ClientLevel.class, "blockStatePredictionHandler");
            if (fieldName == null) {
                return;
            }
            Field field = ClientLevel.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            BlockStatePredictionHandler predictionHandler = (BlockStatePredictionHandler)field.get(mc.level);
            try (BlockStatePredictionHandler predicting = predictionHandler.startPredicting()){
                int sequence = predicting.currentSequence();
                mc.getConnection().send(predictiveAction.predict(sequence));
            }
        } catch (Exception ex) {
            logger.error(ex);
            ex.printStackTrace();
        }
    }

    public static void sendPredictiveDirect(PredictiveAction predictiveAction) {
        if (mc.getConnection() == null || mc.level == null) {
            return;
        }
        try {
            String fieldName = ReflectionUtil.getMappedFieldName(ClientLevel.class, "blockStatePredictionHandler");
            if (fieldName == null) {
                return;
            }
            Field field = ClientLevel.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            BlockStatePredictionHandler predictionHandler = (BlockStatePredictionHandler)field.get(mc.level);
            try (BlockStatePredictionHandler predicting = predictionHandler.startPredicting()){
                int sequence = predicting.currentSequence();
                PacketUtil.sendQueued(predictiveAction.predict(sequence));
            }
        } catch (Exception ex) {
            logger.error(ex);
            ex.printStackTrace();
        }
    }

    public static boolean isIgnored(Packet<?> packet) {
        if (mc.player == null) {
            return true;
        }
        if (mc.screen instanceof ReceivingLevelScreen) {
            return true;
        }
        if (packet instanceof ClientboundCustomQueryPacket) {
            return true;
        }
        if (packet instanceof ClientboundTabListPacket) {
            return true;
        }
        if (packet instanceof ClientboundDisconnectPacket) {
            return true;
        }
        if (packet instanceof ClientboundLevelChunkPacketData) {
            return true;
        }
        if (packet instanceof ClientboundForgetLevelChunkPacket) {
            return true;
        }
        if (packet instanceof ClientboundPongResponsePacket) {
            return true;
        }
        if (packet instanceof ClientboundLoginPacket) {
            return true;
        }
        if (packet instanceof ClientboundGameProfilePacket) {
            return true;
        }
        if (packet instanceof ClientboundMapItemDataPacket) {
            return true;
        }
        if (packet instanceof ClientboundSetDefaultSpawnPositionPacket) {
            return true;
        }
        if (packet instanceof ClientboundSetEntityMotionPacket) {
            return true;
        }
        if (packet instanceof ClientboundCustomPayloadPacket) {
            return true;
        }
        if (packet instanceof ClientboundPlayerChatPacket) {
            return true;
        }
        if (packet instanceof ClientboundSetTitleTextPacket) {
            return true;
        }
        return mc.player.tickCount <= 60;
    }

    public static void sendQueued(Packet<ServerGamePacketListener> packet) {
        if (mc.player == null) {
            return;
        }
        queuedPackets.add(packet);
        mc.player.connection.send(packet);
    }

    public static void send(Packet<ServerGamePacketListener> packet) {
        if (mc.player == null) {
            return;
        }
        mc.player.connection.send(packet);
    }

    @Generated
    private PacketUtil() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}