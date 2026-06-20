package shit.nilore.modules.impl.combat.antikb;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.concurrent.LinkedBlockingDeque;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import shit.nilore.event.impl.DisconnectEvent;
import shit.nilore.event.impl.GameTickEvent;
import shit.nilore.event.impl.MotionEvent;
import shit.nilore.event.impl.PreMotionEvent;
import shit.nilore.event.impl.ReceivePacketEvent;
import shit.nilore.event.impl.RotationEvent;
import shit.nilore.event.impl.SprintEvent;
import shit.nilore.event.impl.StrafeEvent;
import shit.nilore.event.impl.TickEvent;
import shit.nilore.modules.impl.combat.AntiKB;
import shit.nilore.modules.impl.combat.Backtrack;
import shit.nilore.modules.impl.movement.Scaffold;
import shit.nilore.modules.impl.player.NoFall;
import shit.nilore.utils.rotation.Rotation;
import shit.nilore.utils.rotation.RotationHandler;

public class JumpResetMode extends AntiKBMode {
    public static volatile boolean isJumping = false;

    private enum Phase {
        IDLE, AIR, GROUND
    }

    private ClientboundSetEntityMotionPacket knockbackPacket;
    private int rotationHeldTicks = 0;
    private final LinkedBlockingDeque<Packet<ClientGamePacketListener>> packetQueue = new LinkedBlockingDeque<>();
    private Packet<ClientGamePacketListener> pendingPacket;
    private boolean isSuspending = false;
    private int delayTicks = 0;
    private Phase currentPhase = Phase.IDLE;
    private int jumpTicks = 0;
    private Rotation targetRotation = null;

    public JumpResetMode() {
        super("Jump Reset");
    }

    @Override
    public String getName() {
        return "Jump Reset";
    }

    @Override
    public void onEnable() {
        this.knockbackPacket = null;
        AntiKB.rotation = null;
        this.rotationHeldTicks = 0;
        this.resetState();
        isJumping = false;
    }

    @Override
    public void onDisable() {
        this.onEnable();
    }

    private void resetState() {
        this.isSuspending = false;
        this.delayTicks = 0;
        this.pendingPacket = null;
        this.packetQueue.clear();
        isJumping = false;
        this.currentPhase = Phase.IDLE;
        this.jumpTicks = 0;
    }

    private boolean isNoFallEnabled() {
        return NoFall.INSTANCE != null && NoFall.INSTANCE.isEnabled()
                && (NoFall.INSTANCE.jumpLandingBoost || NoFall.INSTANCE.boostActive);
    }

    private boolean isBacktracking() {
        return Backtrack.INSTANCE != null && Backtrack.INSTANCE.isEnabled()
                && (Backtrack.INSTANCE.isActive() || Backtrack.INSTANCE.isBacktracking());
    }

    private boolean isSuspended() {
        return this.isNoFallEnabled() || this.isBacktracking();
    }

    private void flushQueue(boolean dropPending) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            this.packetQueue.clear();
            return;
        }
        if (!this.packetQueue.isEmpty() && dropPending && this.packetQueue.getFirst() == this.pendingPacket) {
            this.packetQueue.pollFirst();
        }
        Packet<ClientGamePacketListener> packet;
        while ((packet = this.packetQueue.poll()) != null) {
            try {
                packet.handle(connection);
            } catch (Exception e) {
                this.packetQueue.clear();
                break;
            }
        }
    }

    @Override
    public void onRotation(RotationEvent event) {
    }

    @Override
    public void onMotion(MotionEvent event) {
    }

    @Override
    public void onSprint(SprintEvent event) {
    }

    @Override
    public void onPreMotion(PreMotionEvent event) {
    }

    @Override
    public void onStrafe(StrafeEvent event) {
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (this.isSuspended()) {
            if (this.isSuspending) this.flushQueue(false);
            AntiKB.rotation = null;
            this.rotationHeldTicks = 0;
            this.resetState();
            return;
        }
        if ((AntiKB.mode.is("Jump Reset") || AntiKB.mode.is("Mix"))
                && AntiKB.INSTANCE.followDirection.getValue()
                && AntiKB.rotation != null) {
            event.setForward(1.0f);
            event.setStrafe(0.0f);
        }
    }

    @Override
    public void onReceivePacket(ReceivePacketEvent event) {
        LocalPlayer player = mc.player;
        if (player == null || !AntiKB.mode.is("Jump Reset")) return;
        if (this.isSuspended()) {
            if (this.isSuspending) this.flushQueue(false);
            AntiKB.rotation = null;
            this.rotationHeldTicks = 0;
            this.resetState();
            return;
        }
        Packet<ClientGamePacketListener> packet = event.getPacket();
        if (this.isSuspending
                && !(packet instanceof ClientboundSystemChatPacket)
                && !(packet instanceof ClientboundSetTimePacket)) {
            event.setCancelled(true);
            this.packetQueue.add(packet);
            return;
        }
        if (!(packet instanceof ClientboundSetEntityMotionPacket motion)) return;
        if (motion.getId() != player.getId()) return;
        this.knockbackPacket = motion;
        boolean wantRotate = AntiKB.INSTANCE.rotate.getValue() || AntiKB.INSTANCE.followDirection.getValue();
        Rotation kbRotation = null;
        if (wantRotate) {
            float xMotion = (float) (this.knockbackPacket.getXa() / 8000.0);
            float zMotion = (float) (this.knockbackPacket.getZa() / 8000.0);
            float yaw = (float) Math.toDegrees(Math.atan2(xMotion, -zMotion));
            kbRotation = new Rotation(yaw, player.getXRot());
        }
        if (!player.onGround()) {
            if (kbRotation != null) {
                AntiKB.rotation = kbRotation;
                this.rotationHeldTicks = 0;
                try {
                    RotationHandler.setTargetRotation(kbRotation);
                    RotationHandler.isRotating = true;
                } catch (Throwable ignored) {
                }
            }
            this.isSuspending = true;
            this.currentPhase = Phase.AIR;
            this.delayTicks = 20;
            isJumping = true;
            this.pendingPacket = packet;
            this.packetQueue.add(packet);
            event.setCancelled(true);
        } else {
            this.targetRotation = kbRotation;
            this.isSuspending = true;
            this.currentPhase = Phase.GROUND;
            this.delayTicks = 10;
            isJumping = true;
            this.pendingPacket = packet;
            this.packetQueue.add(packet);
            event.setCancelled(true);
        }
    }

    @Override
    public void onDisconnect(DisconnectEvent event) {
        AntiKB.rotation = null;
        this.knockbackPacket = null;
        this.rotationHeldTicks = 0;
        this.resetState();
    }

    @Override
    public void onGameTick(GameTickEvent event) {
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (!AntiKB.mode.is("Jump Reset") && !AntiKB.mode.is("Mix")) return;
        if (this.isSuspended()) {
            if (this.isSuspending) this.flushQueue(false);
            AntiKB.rotation = null;
            this.rotationHeldTicks = 0;
            this.resetState();
            return;
        }
        if (this.isSuspending && this.currentPhase == Phase.GROUND) {
            if (!Scaffold.INSTANCE.isEnabled()) {
                boolean down = InputConstants.isKeyDown(mc.getWindow().getWindow(), mc.options.keyJump.getKey().getValue());
                mc.options.keyJump.setDown(down);
            }
            return;
        }
        if (this.jumpTicks > 0 && !Scaffold.INSTANCE.isEnabled()) {
            mc.options.keyJump.setDown(true);
            this.jumpTicks--;
            return;
        }
        if (!Scaffold.INSTANCE.isEnabled()) {
            boolean down = InputConstants.isKeyDown(mc.getWindow().getWindow(), mc.options.keyJump.getKey().getValue());
            mc.options.keyJump.setDown(down);
        }
    }

    @Override
    public void onTick(TickEvent event) {
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (!AntiKB.mode.is("Jump Reset") && !AntiKB.mode.is("Mix")) return;
        if (this.isSuspended()) {
            if (this.isSuspending) this.flushQueue(false);
            AntiKB.rotation = null;
            this.rotationHeldTicks = 0;
            this.resetState();
            return;
        }
        if (this.isSuspending) {
            if (this.currentPhase == Phase.AIR) {
                if (player.onGround()) {
                    this.flushQueue(false);
                    this.resetState();
                } else if (this.delayTicks > 0) {
                    this.delayTicks--;
                } else {
                    this.flushQueue(false);
                    this.resetState();
                }
            } else if (this.currentPhase == Phase.GROUND) {
                if (this.delayTicks > 0) {
                    this.delayTicks--;
                } else {
                    this.flushQueue(false);
                    if (this.targetRotation != null) {
                        AntiKB.rotation = this.targetRotation;
                        this.rotationHeldTicks = 0;
                        try {
                            RotationHandler.setTargetRotation(this.targetRotation);
                            RotationHandler.isRotating = true;
                        } catch (Throwable ignored) {
                        }
                        this.targetRotation = null;
                    }
                    this.resetState();
                    this.jumpTicks = 1;
                }
            }
        }
        if (AntiKB.rotation != null) {
            this.rotationHeldTicks++;
        }
        boolean shouldClear = player.hurtTime == 0
                || this.rotationHeldTicks > AntiKB.INSTANCE.rotateTicks.getValue().intValue()
                || (!AntiKB.INSTANCE.rotate.getValue() && !AntiKB.INSTANCE.followDirection.getValue());
        if (shouldClear) {
            AntiKB.rotation = null;
            this.knockbackPacket = null;
            this.rotationHeldTicks = 0;
        }
    }
}
