package client.nilore.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.game.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ServerboundKeepAlivePacket;

/**
 * 本地 ping 读数。
 *
 * <p>客户端在战局里拿不到"自己发出去多久才回来"的时间: 原版的 keep-alive 是服务端发起、
 * 客户端收到后立刻回包, 所以 RTT 一直是由服务端测的, 客户端能看到的只有
 * {@link PlayerInfo#getLatency()} 这个"服务端认为的你有多快"。
 *
 * <p>问题在于这个值会被**本客户端自己**污染: 只要有模块压住 keep-alive(入站的
 * {@link ClientboundKeepAlivePacket} 被延迟处理, 或者出站的 {@link ServerboundKeepAlivePacket}
 * 被排队/延迟发送), 服务端算出来的往返时间就会多出那段持有时长, 于是 HUD 上写着 500ms
 * 而真实链路只有 40ms —— 这正是"与实际值不符"的来源。
 *
 * <p>这里做的修正很直接, 而且不需要两端时钟同步:
 * <pre>
 *     服务端测得的 RTT = 下行 + 我方持有时长 + 上行
 *     真实 RTT        ≈ 服务端测得的 RTT - 我方持有时长
 * </pre>
 * 持有时长在本地就能精确量到 —— 记录入站 keep-alive 的到达时刻, 再记录同 id 的出站回包
 * 真正离开的时刻, 差值就是它。正常不压包时持有为 0, 读数与改动前完全一致。
 *
 * <p>另外两件事:
 * <ul>
 *   <li>服务端没下发本地玩家的延迟(不少代理/小游戏服只在进服时给一次 0)时, 返回上一次
 *       的有效读数, 而不是把 0 当成真实值显示出去;</li>
 *   <li>完全没有有效数据时返回 {@link #UNKNOWN}, 由调用方决定不显示, 而不是显示 0ms。</li>
 * </ul>
 */
public final class PingTracker {

    /** 没有可信读数。调用方应当隐藏毫秒字段, 而不是显示 0。 */
    public static final int UNKNOWN = -1;

    /** 持有时长的指数平滑系数: 压包是持续行为, 取近期均值比取单次样本稳。 */
    private static final double HOLD_SMOOTHING = 0.35;
    /** 超过这个持有时长就当作"没量到"(例如回包走了绕过 patch 的通道), 不计入平滑。 */
    private static final long HOLD_SANITY_MS = 5000L;

    private static long pendingKeepAliveId = Long.MIN_VALUE;
    private static long pendingReceivedAt;
    private static double smoothedHoldMs;
    private static long lastHoldAt;

    private static int lastKnown = UNKNOWN;
    private static long lastKnownAt;

    private PingTracker() {
    }

    /** 入站包: 记下 keep-alive 的到达时刻。 */
    public static void onIncomingPacket(Object packet) {
        if (!(packet instanceof ClientboundKeepAlivePacket keepAlive)) {
            return;
        }
        pendingKeepAliveId = keepAlive.getId();
        pendingReceivedAt = System.currentTimeMillis();
    }

    /** 出站包: 同 id 的回包真正离开时, 得出这次被压了多久。 */
    public static void onOutgoingPacket(Object packet) {
        if (!(packet instanceof ServerboundKeepAlivePacket keepAlive)) {
            return;
        }
        if (pendingKeepAliveId == Long.MIN_VALUE) {
            return;
        }
        // id 必须对得上: 服务端用自己发出去的那个 id 反查, 我们只认同一笔往返。
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

    /**
     * 当前 ping(ms), 已扣掉本地持有时长。
     *
     * @return {@link #UNKNOWN} 表示没有可信数据
     */
    public static int getPing() {
        // 压包模块关掉之后持有会立刻归零, 但指数平滑还留着一截历史; 超过两个 keep-alive
        // 周期没有新的持有样本就快速衰减, 免得"关掉 lag 之后 HUD 还少报一段时间"。
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
        // 服务端这一轮没给(或给了 0): 沿上一次的有效读数, 不要用 0 覆盖它。
        return lastKnown;
    }

    /** 上一次拿到读数的时间, 供需要判断数据新鲜度的调用方使用。 */
    public static long lastKnownAt() {
        return lastKnownAt;
    }

    /** 本地持有时长的当前平滑值, 调试用。 */
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
