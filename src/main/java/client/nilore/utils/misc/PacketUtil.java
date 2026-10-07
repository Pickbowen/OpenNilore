package client.nilore.utils.misc;

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
import client.nilore.ClientBase;
import client.nilore.NiloreClient;
import client.nilore.event.impl.PacketSendEvent;
import client.nilore.utils.misc.ReflectionUtil;

public final class PacketUtil
extends ClientBase {
    public static final ArrayList<Packet<ServerGamePacketListener>> queuedPackets = new ArrayList<>();

    private static Field predictionHandlerField;
    private static boolean predictionHandlerFieldResolved;

    /**
     * The prediction handler lives on ClientLevel and both predictive senders reach it on hot
     * paths. getDeclaredField walks the whole field table and hands back a fresh copy that then
     * has to be made accessible again, so the result is resolved once and kept. Resolution
     * failures are cached too: a rename that does not apply stays a rename that does not apply,
     * and retrying it on every send would cost the same lookup it just failed.
     */
    private static BlockStatePredictionHandler predictionHandler() throws ReflectiveOperationException {
        if (!predictionHandlerFieldResolved) {
            predictionHandlerFieldResolved = true;
            String fieldName = ReflectionUtil.getMappedFieldName(ClientLevel.class, "blockStatePredictionHandler");
            if (fieldName != null) {
                Field field = ClientLevel.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                predictionHandlerField = field;
            }
        }
        if (predictionHandlerField == null) {
            throw new NoSuchFieldException("blockStatePredictionHandler");
        }
        return (BlockStatePredictionHandler)predictionHandlerField.get(mc.level);
    }

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
            BlockStatePredictionHandler predictionHandler = predictionHandler();
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
            BlockStatePredictionHandler predictionHandler = predictionHandler();
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