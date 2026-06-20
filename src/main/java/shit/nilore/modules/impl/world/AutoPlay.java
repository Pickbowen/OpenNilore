package shit.nilore.modules.impl.world;

import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import shit.nilore.event.impl.DisconnectEvent;
import shit.nilore.event.impl.PacketEvent;
import shit.nilore.event.impl.TickEvent;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.settings.impl.NumberSetting;
import shit.nilore.utils.misc.ChatUtil;
import shit.nilore.event.EventTarget;

public class AutoPlay
extends Module {
    public static AutoPlay instance;
    private final NumberSetting delay = new NumberSetting("Delay", 2.0, 0.0, 10.0, 0.1);
    public long disconnectTime = -1L;
    public boolean pendingDisconnect = false;
    public long reconnectTime = -1L;

    public AutoPlay() {
        super("AutoPlay", Category.WORLD);
        instance = this;
    }

    @Override
    protected void onEnable() {
        this.pendingDisconnect = false;
        this.disconnectTime = -1L;
        this.reconnectTime = -1L;
        super.onEnable();
    }

    @Override
    protected void onDisable() {
        this.pendingDisconnect = false;
        this.disconnectTime = -1L;
        this.reconnectTime = -1L;
        super.onDisable();
    }

    @EventTarget
    public void onDisconnect(DisconnectEvent disconnectEvent) {
        this.pendingDisconnect = false;
        this.disconnectTime = -1L;
        this.reconnectTime = -1L;
    }

    @EventTarget
    public void onPacket(PacketEvent packetEvent) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (packetEvent.getPacket() instanceof ClientboundSystemChatPacket chatPacket) {
            String message = chatPacket.content().getString().replaceAll("§[0-9a-fk-or]", "").trim();
            if (message.contains("地图评分")) {
                ChatUtil.print("1");
                if (this.disconnectTime == -1L) {
                    this.disconnectTime = System.currentTimeMillis();
                    this.pendingDisconnect = true;
                }
            } else if (message.contains("游戏将在 1 秒 后开始")) {
                ChatUtil.print("2");
                this.disconnectTime = -1L;
                this.pendingDisconnect = false;
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        long elapsed;
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (this.reconnectTime != -1L) {
            if (System.currentTimeMillis() - this.reconnectTime > 1000L) {
                this.disconnectTime = -1L;
                this.pendingDisconnect = false;
                this.reconnectTime = -1L;
            }
            return;
        }
        if (this.disconnectTime != -1L && (double)(elapsed = System.currentTimeMillis() - this.disconnectTime) >= this.delay.getValue().doubleValue() * 1000.0) {
            mc.player.connection.sendCommand("again");
            this.reconnectTime = System.currentTimeMillis();
        }
    }

    public NumberSetting getDelay() {
        return this.delay;
    }
}