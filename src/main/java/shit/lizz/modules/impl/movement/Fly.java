package shit.lizz.modules.impl.movement;

import java.util.LinkedHashSet;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import shit.lizz.event.impl.ReceivePacketEvent;
import shit.lizz.event.impl.RotationEvent;
import shit.lizz.event.impl.TickEvent;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.event.EventTarget;

public class Fly extends Module {
    public static Fly INSTANCE;

    public record HeldPacket(Packet<?> packet) {
    }

    private boolean elytraActive = false;
    private boolean initialized = false;
    private int pingCount = 0;
    private final LinkedHashSet<HeldPacket> pendingPackets = new LinkedHashSet<>();

    public Fly() {
        super("Fly", Category.MOVEMENT);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.elytraActive = false;
        this.initialized = false;
        this.pingCount = 0;
        this.pendingPackets.clear();
        if (mc.player == null || (mc.level == null && !mc.player.onGround())) {
            this.setEnabled(false);
        }
    }

    @Override
    public void onDisable() {
        this.releasePackets(true);
    }

    @EventTarget
    public void onRotation(RotationEvent event) {
        if (mc.player == null) return;
        if (mc.player.onGround() && this.initialized && !mc.options.keyJump.isDown()) {
            mc.player.jumpFromGround();
            this.initialized = false;
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || mc.player == null || mc.level == null) return;
        if (!this.initialized) {
            this.initialized = true;
            return;
        }
        if (!this.elytraActive) {
            if (!mc.player.onGround() && mc.player.getDeltaMovement().y > 0.0) {
                this.elytraActive = true;
                this.sendElytraPacket();
            }
            return;
        }
        if (this.pingCount > 8) {
            this.sendElytraPacket();
        }
    }

    private void sendElytraPacket() {
        mc.getConnection().send(new ServerboundPlayerCommandPacket(
                mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
    }

    @EventTarget
    public void onReceivePacket(ReceivePacketEvent event) {
        if (!this.isEnabled() || mc.player == null) return;
        Packet<ClientGamePacketListener> packet = event.getPacket();
        if (!this.initialized) return;
        if (packet instanceof ClientboundSetEntityMotionPacket motion
                && motion.getId() == mc.player.getId()) {
            this.pingCount = 0;
        }
        if (packet instanceof ClientboundPingPacket && this.elytraActive) {
            event.setCancelled(true);
            this.pendingPackets.add(new HeldPacket(packet));
            ++this.pingCount;
        }
        if (packet instanceof ClientboundPlayerPositionPacket) {
            event.setCancelled(true);
            this.pendingPackets.add(new HeldPacket(packet));
            this.releasePackets(true);
            this.pingCount = 0;
        }
    }

    private void releasePackets(boolean flush) {
        synchronized (this.pendingPackets) {
            if (flush) {
                this.pendingPackets.forEach(held -> {
                    if (mc.getConnection() != null) {
                        try {
                            handlePacket(held.packet, mc.getConnection());
                        } catch (Exception ignored) {
                        }
                    }
                });
                this.pendingPackets.clear();
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void handlePacket(Packet packet, PacketListener listener) {
        packet.handle(listener);
    }
}
