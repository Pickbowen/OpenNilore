package client.nilore.modules.impl.player;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.BowlFoodItem;
import net.minecraft.world.item.ItemStack;
import client.nilore.ClientBase;
import client.nilore.NiloreClient;
import client.nilore.event.impl.MotionEvent;
import client.nilore.event.impl.PacketEvent;
import client.nilore.event.impl.StrafeEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.event.impl.WorldChangeEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.movement.Scaffold;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.utils.misc.PacketUtil;
import client.nilore.utils.misc.ReflectionUtil;
import client.nilore.utils.rotation.Rotation;
import client.nilore.utils.rotation.RotationHandler;
import client.nilore.event.EventTarget;

public class Stuck
extends Module {
    public static Stuck INSTANCE;
    private final ModeSetting modeSetting = new ModeSetting("Mode", "Delay", "Packet").withDefault("Delay");
    private int stuckState = 0;
    private Packet<?> capturedPacket;
    private float savedYaw;
    private float savedPitch;
    private boolean pendingDisable = false;
    private final Queue<ServerboundPongPacket> pongQueue = new ConcurrentLinkedQueue<>();

    public Stuck() {
        super("Stuck", Category.PLAYER);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.stuckState = 0;
        this.capturedPacket = null;
        Rotation target = RotationHandler.targetRotation;
        this.savedYaw = target != null ? target.getYaw() : mc.player.getYRot();
        this.savedPitch = target != null ? target.getPitch() : mc.player.getXRot();
        this.pendingDisable = false;
    }

    @Override
    public void setEnabled(boolean enable) {
        if (mc.player == null) {
            return;
        }
        if (enable) {
            super.setEnabled(true);
        } else if (this.modeSetting.is("Delay")) {
            if (this.stuckState == 3) {
                super.setEnabled(false);
            } else {
                this.pendingDisable = true;
            }
        } else {
            super.setEnabled(false);
        }
    }

    @Override
    public void onDisable() {
        super.onDisable();
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        if (!this.modeSetting.is("Packet")) {
            return;
        }
        Scaffold scaffold = Scaffold.INSTANCE;
        if (scaffold.isEnabled()) {
            scaffold.setEnabled(false);
            return;
        }
        if (mc.player == null) {
            return;
        }
        if (!this.isAntiVoidActive()) {
            PacketUtil.sendQueued(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        }
    }

    private boolean isAntiVoidActive() {
        return NiloreClient.isReady() && AntiVoid.INSTANCE != null && AntiVoid.INSTANCE.isEnabled() && !mc.player.onGround() && AntiVoid.INSTANCE.bufferingPackets;
    }

    @EventTarget
    public void onMotion(MotionEvent motionEvent) {
        Scaffold scaffold = Scaffold.INSTANCE;
        if (!this.isAntiVoidActive() && scaffold.isEnabled()) {
            scaffold.setEnabled(false);
            return;
        }
        if (mc.player == null) {
            return;
        }
        if (motionEvent.isPost()) {
            mc.player.setDeltaMovement(0.0, 0.0, 0.0);
            if (this.stuckState == 1) {
                this.stuckState = 2;
                float currentYaw = mc.player.getYRot();
                float currentPitch = mc.player.getXRot();
                if (this.shouldSendCapturedPacket() && (this.savedYaw != currentYaw || this.savedPitch != currentPitch)) {
                    PacketUtil.sendQueued(new ServerboundMovePlayerPacket.Rot(currentYaw, currentPitch, mc.player.onGround()));
                    while (!this.pongQueue.isEmpty()) {
                        PacketUtil.sendQueued((Packet<ServerGamePacketListener>) this.pongQueue.poll());
                    }
                    this.savedYaw = currentYaw;
                    this.savedPitch = currentPitch;
                }
                PacketUtil.sendQueued((Packet<ServerGamePacketListener>) this.capturedPacket);
            } else if (!this.isAntiVoidActive() && this.modeSetting.is("Packet") && mc.player.tickCount % 10 == 0) {
                while (!this.pongQueue.isEmpty()) {
                    PacketUtil.sendQueued((Packet<ServerGamePacketListener>) this.pongQueue.poll());
                }
            }
            if (this.pendingDisable) {
                if (this.modeSetting.is("Delay")) {
                    PacketUtil.sendQueued(new ServerboundMovePlayerPacket.Pos(mc.player.getX() + 1337.0, mc.player.getY(), mc.player.getZ() + 1337.0, mc.player.onGround()));
                } else {
                    PacketUtil.sendQueued(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                }
                while (!this.pongQueue.isEmpty()) {
                    PacketUtil.sendQueued((Packet<ServerGamePacketListener>) this.pongQueue.poll());
                }
                if (this.modeSetting.is("Packet")) {
                    for (int i = 1; i <= 4; ++i) {
                        ClientBase.delayPackets.add(() -> {});
                    }
                }
                this.stuckState = 3;
                this.pendingDisable = false;
            }
        }
    }

    private boolean shouldSendCapturedPacket() {
        if (this.capturedPacket instanceof ServerboundUseItemPacket useItemPacket) {
            ItemStack heldStack = mc.player.getItemInHand(useItemPacket.getHand());
            return !(heldStack.getItem() instanceof BowlFoodItem) && !(heldStack.getItem() instanceof BowItem);
        }
        if (this.capturedPacket instanceof ServerboundPlayerActionPacket actionPacket) {
            return actionPacket.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM && mc.player.getUseItem().getItem() instanceof BowItem;
        }
        return false;
    }

    @EventTarget
    public void onStrafe(StrafeEvent strafeEvent) {
        strafeEvent.setForward(0.0f);
        strafeEvent.setStrafe(0.0f);
        strafeEvent.setJumping(false);
    }

    @EventTarget
    public void onWorldChange(WorldChangeEvent worldChangeEvent) {
        this.stuckState = 3;
        this.capturedPacket = null;
        this.setEnabled(false);
    }

    @EventTarget(value=1)
    public void onPacket(PacketEvent packetEvent) {
        if (mc.player == null) {
            return;
        }
        Object rawPacket = packetEvent.getPacket();
        if (rawPacket instanceof ServerboundMovePlayerPacket movePacket) {
            if (this.stuckState != 1 && this.modeSetting.is("Packet")) {
                Rotation jitterRotation = new Rotation(mc.player.getYRot() + (float)(Math.random() - 0.5), mc.player.getXRot());
                ReflectionUtil.setXRot(movePacket, jitterRotation.getPitch());
                ReflectionUtil.setYRot(movePacket, jitterRotation.getYaw());
            }
            packetEvent.setCancelled(true);
        } else if (packetEvent.getPacket() instanceof ServerboundPongPacket) {
            this.pongQueue.offer((ServerboundPongPacket)packetEvent.getPacket());
            packetEvent.setCancelled(true);
        } else if (packetEvent.getPacket() instanceof ServerboundUseItemPacket || packetEvent.getPacket() instanceof ServerboundPlayerActionPacket) {
            this.capturedPacket = packetEvent.getPacket();
            this.stuckState = 1;
            packetEvent.setCancelled(true);
        } else if (packetEvent.getPacket() instanceof ClientboundPlayerPositionPacket && this.modeSetting.is("Delay")) {
            while (!this.pongQueue.isEmpty()) {
                PacketUtil.sendQueued((Packet<ServerGamePacketListener>) this.pongQueue.poll());
            }
            this.stuckState = 3;
            this.setEnabled(false);
        }
    }
}