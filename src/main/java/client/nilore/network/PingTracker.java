package client.nilore.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.game.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ServerboundKeepAlivePacket;

/**
 *
 *
 *
 * <pre>
 * </pre>
 *
 * <ul>
 * </ul>
 */
public final class PingTracker {

    public static final int UNKNOWN = -1;

    private static final double HOLD_SMOOTHING = 0.35;
    private static final long HOLD_SANITY_MS = 5000L;

    private static long pendingKeepAliveId = Long.MIN_VALUE;
    private static long pendingReceivedAt;
    private static double smoothedHoldMs;
    private static long lastHoldAt;

    private static int lastKnown = UNKNOWN;
    private static long lastKnownAt;

    private PingTracker() {
    }

    public static void onIncomingPacket(Object packet) {
        if (!(packet instanceof ClientboundKeepAlivePacket keepAlive)) {
            return;
        }
        pendingKeepAliveId = keepAlive.getId();
        pendingReceivedAt = System.currentTimeMillis();
    }

    public static void onOutgoingPacket(Object packet) {
        if (!(packet instanceof ServerboundKeepAlivePacket keepAlive)) {
            return;
        }
        if (pendingKeepAliveId == Long.MIN_VALUE) {
            return;
        }
        if (keepAlive.getId() != pendingKeepAliveId) {
            return;
        }
        long hold = System.currentTimeMillis() - pendingReceivedAt;
        pendingKeepAliveId = Long.MIN_VALUE;
        if (hold < 0L || hold > HOLD_SANITY_MS) {
            return;
        }
        smoothedHoldMs = smoothedHoldMs * (1.0 - HOLD_SMOOTHING) + hold * HOLD_SMOOTHING;
        lastHoldAt = System.currentTimeMillis();
    }

    public static int getPing() {
        long now = System.currentTimeMillis();
        if (smoothedHoldMs > 0.5 && now - lastHoldAt > 2500L) {
            smoothedHoldMs *= 0.75;
        }
        int reported = readReportedLatency();
        if (reported > 0) {
            int corrected = (int) Math.max(1L, Math.round(reported - smoothedHoldMs));
            lastKnown = corrected;
            lastKnownAt = now;
            return corrected;
        }
        return lastKnown;
    }

    public static long lastKnownAt() {
        return lastKnownAt;
    }

    public static double heldMs() {
        return smoothedHoldMs;
    }

    public static void reset() {
        pendingKeepAliveId = Long.MIN_VALUE;
        pendingReceivedAt = 0L;
        smoothedHoldMs = 0.0;
        lastHoldAt = 0L;
        lastKnown = UNKNOWN;
        lastKnownAt = 0L;
    }

    private static int readReportedLatency() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return UNKNOWN;
        }
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null) {
            return UNKNOWN;
        }
        PlayerInfo info = connection.getPlayerInfo(minecraft.player.getUUID());
        return info == null ? UNKNOWN : info.getLatency();
    }
}
