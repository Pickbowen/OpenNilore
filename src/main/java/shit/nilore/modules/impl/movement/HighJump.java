package shit.nilore.modules.impl.movement;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import shit.nilore.NiloreClient;
import shit.nilore.event.impl.MotionEvent;
import shit.nilore.event.impl.PacketEvent;
import shit.nilore.event.impl.PreTickEvent;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.utils.misc.ChatUtil;
import shit.nilore.utils.misc.PacketUtil;
import shit.nilore.event.EventTarget;

public class HighJump
extends Module {
    public static HighJump INSTANCE;
    public int boostPhase;
    public int tickCounter;
    public int flagState;
    public int skipPacket;
    public int fallbackTicks;

    public HighJump() {
        super("HighJump", Category.MOVEMENT);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.tickCounter = 0;
        this.boostPhase = 0;
        this.flagState = 0;
        this.skipPacket = 0;
        this.fallbackTicks = 0;
        if (NiloreClient.isReady()) {
            ChatUtil.print("你必须在Bedwars并且你的延迟必须在20ms以下才能使用这个模块");
        }
    }

    @EventTarget
    public void onPreTick(PreTickEvent preTickEvent) {
        if (mc.player == null) {
            return;
        }
        if (this.tickCounter == 0) {
            mc.player.jumpFromGround();
        }
        if (this.tickCounter == 1) {
            this.boostPhase = 1;
            for (int i = 0; i < 20; ++i) {
                PacketUtil.sendQueued(new ServerboundMovePlayerPacket.StatusOnly(false));
            }
        }
        ++this.tickCounter;
    }

    @EventTarget
    public void onPacket(PacketEvent packetEvent) {
        if (mc.player == null) {
            return;
        }
        Packet<?> packet = packetEvent.getPacket();
        if (packetEvent.isIncoming()) {
            ClientboundSetEntityMotionPacket clientboundSetEntityMotionPacket;
            if (packet instanceof ClientboundSetEntityMotionPacket && (clientboundSetEntityMotionPacket = (ClientboundSetEntityMotionPacket)packet).getId() == mc.player.getId() && (double)clientboundSetEntityMotionPacket.getYa() / 8000.0 < 0.0) {
                this.toggle();
            }
            if (packetEvent.getPacket() instanceof ClientboundPlayerPositionPacket) {
                this.flagState = 1;
            }
        } else if (packet instanceof ServerboundMovePlayerPacket && this.skipPacket == 1) {
            this.skipPacket = 0;
            packetEvent.setCancelled(true);
        }
    }

    @EventTarget
    public void onMotion(MotionEvent motionEvent) {
        if (motionEvent.isPre()) {
            return;
        }
        if (mc.player == null) {
            return;
        }
        NiloreClient.serverTickRate = 2.0f;
        motionEvent.setPitch((float)((double)motionEvent.getPitch() + (double)((float)Math.random()) * 0.1));
        if (this.boostPhase == 0) {
            return;
        }
        mc.player.setDeltaMovement(mc.player.getDeltaMovement().x, 0.42f, mc.player.getDeltaMovement().z);
        if (this.flagState == 1) {
            this.flagState = 2;
            this.fallbackTicks = 0;
        } else if (this.flagState == 2) {
            for (int i = 0; i < 2; ++i) {
                PacketUtil.sendQueued(new ServerboundMovePlayerPacket.StatusOnly(false));
            }
            this.skipPacket = 1;
            this.flagState = 0;
        } else {
            mc.player.setDeltaMovement(0.0, 0.0, 0.0);
            this.skipPacket = 1;
            ++this.fallbackTicks;
            if (this.fallbackTicks > 20) {
                // empty if block
            }
        }
    }
}