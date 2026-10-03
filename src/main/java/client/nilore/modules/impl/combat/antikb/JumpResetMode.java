package client.nilore.modules.impl.combat.antikb;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import client.nilore.event.impl.DisconnectEvent;
import client.nilore.event.impl.EntityRemoveEvent;
import client.nilore.event.impl.GameTickEvent;
import client.nilore.event.impl.MotionEvent;
import client.nilore.event.impl.PreMotionEvent;
import client.nilore.event.impl.ReceivePacketEvent;
import client.nilore.event.impl.RotationEvent;
import client.nilore.event.impl.SprintEvent;
import client.nilore.event.impl.StrafeEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.modules.impl.combat.AntiKB;
import client.nilore.modules.impl.combat.Backtrack;
import client.nilore.modules.impl.combat.KillAura;
import client.nilore.modules.impl.movement.Scaffold;
import client.nilore.modules.impl.player.NoFall;
import client.nilore.utils.rotation.Rotation;
import client.nilore.utils.rotation.RotationHandler;

public class JumpResetMode extends AntiKBMode {
    public static volatile boolean isJumping = false;

    private ClientboundSetEntityMotionPacket knockbackPacket;
    private int rotationHeldTicks = 0;
    private int jumpTicks = 0;

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
        isJumping = false;
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

    private void cancelJumpReset() {
        AntiKB.rotation = null;
        this.rotationHeldTicks = 0;
        this.resetState();
    }

    @Override
    public void onRotation(RotationEvent event) {
    }

    @Override
    public void onMotion(MotionEvent event) {
    }

    @Override
    public void onAttack(EntityRemoveEvent event) {
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
            this.cancelJumpReset();
            return;
        }
        if (AntiKB.mode.is("Jump Reset")
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
            this.cancelJumpReset();
            return;
        }
        if (!(event.getPacket() instanceof ClientboundSetEntityMotionPacket motion)) return;
        if (motion.getId() != player.getId()) return;
        this.knockbackPacket = motion;

        boolean wantRotate = (AntiKB.INSTANCE.rotate.getValue() || AntiKB.INSTANCE.followDirection.getValue())
                && KillAura.target == null;
        float xMotion = (float) (motion.getXa() / 8000.0);
        float zMotion = (float) (motion.getZa() / 8000.0);
        if (wantRotate && (Math.abs(xMotion) > 1.0E-6 || Math.abs(zMotion) > 1.0E-6)) {
            float yaw = (float) Math.toDegrees(Math.atan2(xMotion, -zMotion));
            Rotation kbRotation = new Rotation(yaw, player.getXRot());
            AntiKB.rotation = kbRotation;
            this.rotationHeldTicks = 0;
            try {
                RotationHandler.setTargetRotation(kbRotation);
                RotationHandler.isRotating = true;
            } catch (Throwable ignored) {
            }
        }

        isJumping = true;
        if (player.onGround()) {
            this.jumpTicks = 1;
        }
        logger.info("[AntiKB] JumpReset kb=({},{},{}) onGround={} rotating={}",
                motion.getXa(), motion.getYa(), motion.getZa(), player.onGround(), AntiKB.rotation != null);
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
        if (!AntiKB.mode.is("Jump Reset")) return;
        if (this.isSuspended()) {
            this.cancelJumpReset();
            return;
        }
        if (this.jumpTicks > 0 && !Scaffold.INSTANCE.isEnabled()) {
            mc.options.keyJump.setDown(true);
            this.jumpTicks--;
            logger.info("[AntiKB] JumpReset jump tick={} onGround={}", player.tickCount, player.onGround());
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
        if (!AntiKB.mode.is("Jump Reset")) return;
        if (this.isSuspended()) {
            this.cancelJumpReset();
            return;
        }
        if (AntiKB.rotation != null) {
            this.rotationHeldTicks++;
        }
        if (player.hurtTime == 0) {
            isJumping = false;
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
