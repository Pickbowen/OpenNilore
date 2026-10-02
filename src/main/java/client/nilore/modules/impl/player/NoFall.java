package client.nilore.modules.impl.player;

import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import client.nilore.event.impl.MotionEvent;
import client.nilore.event.impl.PacketEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.misc.PacketUtil;
import client.nilore.event.EventTarget;

public class NoFall
extends Module {
    public static NoFall INSTANCE;
    public boolean jumpLandingBoost = false;
    public boolean boostActive = false;
    private final NumberSetting fallDistanceSetting = new NumberSetting("Fall Distance", 3.0, 0.0, 10.0, 0.5);
    private boolean fallDistanceReached = false;
    private boolean sentFlyPacket = false;

    public NoFall() {
        super("NoFall", Category.PLAYER);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.reset();
    }

    @Override
    public void onDisable() {
        this.reset();
    }

    private void reset() {
        this.fallDistanceReached = false;
        this.sentFlyPacket = false;
        this.jumpLandingBoost = false;
        this.boostActive = false;
    }

    @EventTarget(value=0)
    public void onMotion(MotionEvent motionEvent) {
        if (mc.player == null || mc.isSingleplayer()) {
            return;
        }
        if (mc.player.onClimbable()) {
            this.fallDistanceReached = false;
            return;
        }
        if (!mc.player.onGround()) {
            // 空中下落, 记录下落距离是否达标
            if (mc.player.fallDistance >= this.fallDistanceSetting.getValue().floatValue()) {
                this.fallDistanceReached = true;
            }
            return;
        }
        // 碰到地板 + 下落距离达标, 才发鞘翅包
        if (this.fallDistanceReached) {
            this.fallDistanceReached = false;
            if (!this.sentFlyPacket) {
                PacketUtil.sendQueued(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                this.sentFlyPacket = true;
                this.boostActive = true;
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent packetEvent) {
        if (mc.player == null) {
            return;
        }
        // 收到服务器回应(s08 位置同步包)后, 把 y 向上微调 1e-9
        if (this.sentFlyPacket && packetEvent.getPacket() instanceof ClientboundPlayerPositionPacket) {
            mc.player.setPos(mc.player.getX(), mc.player.getY() + 1.0E-9, mc.player.getZ());
            this.sentFlyPacket = false;
            this.boostActive = false;
        }
    }
}
