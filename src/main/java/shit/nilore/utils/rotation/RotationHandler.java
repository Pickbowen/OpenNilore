package shit.nilore.utils.rotation;

import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import shit.nilore.ClientBase;
import shit.nilore.NiloreClient;
import shit.nilore.event.impl.CameraPitchEvent;
import shit.nilore.event.impl.ChatEvent;
import shit.nilore.event.impl.FallFlyingEvent;
import shit.nilore.event.impl.RotationAnimationEvent;
import shit.nilore.event.impl.JumpMarkerEvent;
import shit.nilore.event.impl.MotionEvent;
import shit.nilore.event.impl.PacketEvent;
import shit.nilore.event.impl.RayTraceEvent;
import shit.nilore.event.impl.RotationEvent;
import shit.nilore.event.impl.StrafeEvent;
import shit.nilore.event.impl.TickEvent;
import shit.nilore.event.impl.UseItemRayTraceEvent;
import shit.nilore.event.impl.WorldChangeEvent;
import shit.nilore.modules.impl.combat.AntiKB;
import shit.nilore.modules.impl.combat.AutoThrow;
import shit.nilore.modules.impl.combat.CrystalAura;
import shit.nilore.modules.impl.combat.KillAura;
import shit.nilore.modules.impl.movement.FireballBlink;
import shit.nilore.modules.impl.movement.Scaffold;
import shit.nilore.modules.impl.player.AntiTNT;
import shit.nilore.modules.impl.player.AntiWeb;
import shit.nilore.modules.impl.player.AutoMLG;
import shit.nilore.modules.impl.player.AutoWebPlace;
import shit.nilore.modules.impl.player.Helper;
import shit.nilore.modules.impl.player.MidPearl;
import shit.nilore.utils.animation.TickTimer;
import shit.nilore.utils.game.MovementUtil;
import shit.nilore.utils.misc.ReflectionUtil;
import shit.nilore.event.EventTarget;

public class RotationHandler
extends ClientBase {
    public static Rotation targetRotation;
    public static Rotation prevRotation;
    public static Rotation sentRotation;
    public static Rotation prevSentRotation;
    public static boolean isRotating;

    public static void setTargetRotation(Rotation rotation) {
        targetRotation = rotation;
        ClientBase.yaw = rotation.getYaw();
    }

    @EventTarget
    public void onWorldChange(WorldChangeEvent worldChangeEvent) {
        prevRotation = null;
        targetRotation = null;
    }

    @EventTarget(value=0)
    public void onTick(TickEvent tickEvent) {
        TickTimer.tickAll();
    }

    @EventTarget(value=0)
    public void onPacket(PacketEvent packetEvent) {
        ServerboundMovePlayerPacket serverboundMovePlayerPacket;
        Object object = packetEvent.getPacket();
        if (object instanceof ServerboundMovePlayerPacket movePacket
                && movePacket.getYRot(0.0f) < 360.0f && movePacket.getYRot(0.0f) > -360.0f) {
            ReflectionUtil.setYRot(movePacket, movePacket.getYRot(0.0f) + 720.0f);
        }
        Object packet2 = packetEvent.getPacket();
        if (packet2 instanceof ServerboundChatPacket chatPacket) {
            ChatEvent event = new ChatEvent(chatPacket.message());
            if (NiloreClient.isReady()) {
                NiloreClient.getInstance().getEventBus().call(event);
                if (event.isCancelled()) {
                    packetEvent.setCancelled(true);
                }
            }
        }
    }

    @EventTarget(value=4)
    public void onTickHigh(TickEvent tickEvent) {
        if (mc.player != null) {
            KillAura killAura = KillAura.INSTANCE;
            Scaffold scaffold = Scaffold.INSTANCE;
            CrystalAura crystalAura = CrystalAura.INSTANCE;
            AutoMLG autoMLG = AutoMLG.INSTANCE;
            FireballBlink fireballBlink = FireballBlink.INSTANCE;
            AntiTNT antiTNT = AntiTNT.INSTANCE;
            Helper helper = Helper.INSTANCE;
            AntiWeb antiWeb = AntiWeb.INSTANCE;
            AutoWebPlace autoWebPlace = AutoWebPlace.INSTANCE;
            AutoThrow autoThrow = AutoThrow.INSTANCE;
            AntiKB antiKB = AntiKB.INSTANCE;
            MidPearl midPearl = MidPearl.INSTANCE;
            isRotating = true;
            if (autoMLG != null && autoMLG.isEnabled() && autoMLG.targetRotation != null) {
                RotationHandler.setTargetRotation(autoMLG.targetRotation);
                autoMLG.targetRotation = null;
            } else if (crystalAura != null && crystalAura.isEnabled() && CrystalAura.aimRotation != null) {
                RotationHandler.setTargetRotation(CrystalAura.aimRotation);
            } else if (fireballBlink != null && fireballBlink.isEnabled() && FireballBlink.rotation != null) {
                RotationHandler.setTargetRotation(FireballBlink.rotation);
            } else if (midPearl != null && midPearl.isEnabled() && MidPearl.targetRotation != null) {
                RotationHandler.setTargetRotation(MidPearl.targetRotation);
            } else if (antiTNT != null && antiTNT.isEnabled() && AntiTNT.targetRotation != null) {
                RotationHandler.setTargetRotation(AntiTNT.targetRotation);
            } else if (helper != null && helper.isEnabled() && helper.hasTargetRotation() && Helper.targetRotation != null) {
                RotationHandler.setTargetRotation(Helper.targetRotation);
            } else if (antiWeb != null && antiWeb.isEnabled() && AntiWeb.currentPhase != AntiWeb.Phase.IDLE && AntiWeb.targetRotation != null) {
                RotationHandler.setTargetRotation(AntiWeb.targetRotation);
            } else if (autoWebPlace != null && autoWebPlace.isEnabled() && AutoWebPlace.targetRotation != null) {
                RotationHandler.setTargetRotation(AutoWebPlace.targetRotation);
            } else if (autoThrow != null && autoThrow.isEnabled() && autoThrow.targetRotation != null) {
                RotationHandler.setTargetRotation(autoThrow.targetRotation);
            } else if (scaffold != null && scaffold.isEnabled() && scaffold.rots != null) {
                RotationHandler.setTargetRotation(scaffold.rots);
            } else if (killAura != null && killAura.isEnabled() && KillAura.target != null && killAura.rotation != null) {
                RotationHandler.setTargetRotation(new Rotation(killAura.rotation.getYaw(), killAura.rotation.getPitch()));
            } else if (antiKB != null && antiKB.isEnabled() && AntiKB.rotation != null) {
                RotationHandler.setTargetRotation(AntiKB.rotation);
            } else {
                isRotating = false;
            }
        }
    }

    @EventTarget
    public void onHeadTurn(RotationAnimationEvent e) {
        if (sentRotation != null && prevSentRotation != null && mc.player != null && isRotating) {
            e.setYaw(sentRotation.getYaw());
            e.setLastYaw(prevSentRotation.getYaw());

            e.setPitch(sentRotation.getPitch());
            e.setLastPitch(prevSentRotation.getPitch());
        }
    }

    @EventTarget
    public void onCameraPitch(CameraPitchEvent cameraPitchEvent) {
        if (sentRotation != null && prevSentRotation != null) {
            cameraPitchEvent.setPitch(sentRotation.getPitch());
        }
    }

    @EventTarget(value=4)
    public void onMotion(MotionEvent e) {
        if (e.isPost()) {
            if (mc.player != null && mc.player.tickCount <= 1 && NiloreClient.isReady()) {
                NiloreClient.getInstance().getEventBus().call(new WorldChangeEvent());
            }
            if (mc.player == null) {
                return;
            }
            if (targetRotation == null || prevRotation == null) {
                targetRotation = prevRotation = new Rotation(mc.player.getYRot(), mc.player.getXRot());
            }
            prevSentRotation = sentRotation;
            float yaw = targetRotation.getYaw();
            float pitch = targetRotation.getPitch();
            if (!Float.isNaN(yaw) && !Float.isNaN(pitch) && isRotating) {
                e.setYaw(yaw);
                e.setPitch(pitch);
            }
            ClientBase.yaw = targetRotation.getYaw();
            sentRotation = new Rotation(e.getYaw(), e.getPitch());
            prevRotation = new Rotation(e.getYaw(), e.getPitch());
        }
    }

    @EventTarget
    public void onStrafe(StrafeEvent strafeEvent) {
        if (isRotating && targetRotation != null) {
            float yaw = targetRotation.getYaw();
            MovementUtil.handleStrafe(strafeEvent, yaw);
        }
    }

    @EventTarget
    public void onRayTrace(RayTraceEvent rayTraceEvent) {
        if (targetRotation != null && rayTraceEvent.entity == mc.player && isRotating) {
            rayTraceEvent.setYaw(targetRotation.getYaw());
            rayTraceEvent.setPitch(targetRotation.getPitch());
        }
    }

    @EventTarget
    public void onUseItemRayTrace(UseItemRayTraceEvent useItemRayTraceEvent) {
        if (targetRotation != null && isRotating) {
            useItemRayTraceEvent.setYaw(targetRotation.getYaw());
            useItemRayTraceEvent.setPitch(targetRotation.getPitch());
        }
    }

    @EventTarget
    public void onRotation(RotationEvent rotationEvent) {
        if (isRotating && targetRotation != null) {
            rotationEvent.setYaw(targetRotation.getYaw());
        }
    }

    @EventTarget
    public void onJump(JumpMarkerEvent jumpMarkerEvent) {
        if (isRotating && targetRotation != null) {
            jumpMarkerEvent.setYaw(targetRotation.getYaw());
        }
    }

    @EventTarget
    public void onFallFlying(FallFlyingEvent fallFlyingEvent) {
        if (targetRotation != null) {
            fallFlyingEvent.setPitch(targetRotation.getPitch());
        }
    }

    static {
        isRotating = false;
    }
}