package shit.lizz.manager;

import java.util.ArrayDeque;
import java.util.Queue;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundKeepAlivePacket;
import org.apache.commons.lang3.tuple.Pair;
import shit.lizz.ClientBase;
import shit.lizz.event.impl.PacketEvent;
import shit.lizz.event.impl.TickEvent;
import shit.lizz.event.EventTarget;

public class LagManager {
    public static LagManager INSTANCE;
    @Getter @Setter
    private long lagTime = 500L;
    @Getter
    private boolean blinking;
    private int blinkCount = 0;
    private final Queue<Pair<Packet<?>, Long>> packetQueue = new ArrayDeque<>();

    public LagManager() {
        INSTANCE = this;
    }

    public void setBlink(boolean enable) {
        if (enable) {
            ++this.blinkCount;
        } else if (this.blinkCount > 0) {
            --this.blinkCount;
        }
        boolean shouldBlink = this.blinkCount > 0;
        if (this.blinking == shouldBlink) {
            return;
        }
        this.blinking = shouldBlink;
        if (this.blinking) {
            this.packetQueue.clear();
        } else {
            this.flushQueue();
        }
    }

    private void flushQueue() {
        if (ClientBase.mc.getConnection() == null) {
            this.packetQueue.clear();
            return;
        }
        while (!this.packetQueue.isEmpty()) {
            ClientBase.mc.getConnection().send(this.packetQueue.poll().getLeft());
        }
    }

    @EventTarget
    public void onPacket(PacketEvent packetEvent) {
        if (!this.blinking || ClientBase.mc.getConnection() == null || !packetEvent.isIncomingRaw()) {
            return;
        }
        if (packetEvent.getPacket() instanceof ServerboundKeepAlivePacket) {
            this.packetQueue.add(Pair.of(packetEvent.getPacket(), System.currentTimeMillis()));
            packetEvent.setCancelled(true);
        }
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        if (!this.blinking || ClientBase.mc.getConnection() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        while (!this.packetQueue.isEmpty() && now - this.packetQueue.peek().getRight() >= this.lagTime) {
            ClientBase.mc.getConnection().send(this.packetQueue.poll().getLeft());
        }
    }

    }