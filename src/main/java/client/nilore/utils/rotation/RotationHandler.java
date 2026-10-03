package client.nilore.utils.rotation;

import client.nilore.NiloreClient;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.util.Mth;
import client.nilore.ClientBase;
import client.nilore.event.impl.CameraPitchEvent;
import client.nilore.event.impl.ChatEvent;
import client.nilore.event.impl.FallFlyingEvent;
import client.nilore.event.impl.RotationAnimationEvent;
import client.nilore.event.impl.JumpMarkerEvent;
import client.nilore.event.impl.MotionEvent;
import client.nilore.event.impl.PacketEvent;
import client.nilore.event.impl.RayTraceEvent;
import client.nilore.event.impl.RotationEvent;
import client.nilore.event.impl.StrafeEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.event.impl.UseItemRayTraceEvent;
import client.nilore.event.impl.WorldChangeEvent;
import client.nilore.modules.impl.combat.AntiKB;
import client.nilore.modules.impl.combat.AutoThrow;
import client.nilore.modules.impl.combat.CrystalAura;
import client.nilore.modules.impl.combat.KillAura;
import client.nilore.modules.impl.movement.FireballBlink;
import client.nilore.modules.impl.movement.Scaffold;
import client.nilore.modules.impl.player.AntiTNT;
import client.nilore.modules.impl.player.AntiWeb;
import client.nilore.modules.impl.player.AutoMLG;
import client.nilore.modules.impl.player.AutoWebPlace;
import client.nilore.modules.impl.player.Helper;
import client.nilore.modules.impl.player.MidPearl;
import client.nilore.utils.animation.TickTimer;
import client.nilore.utils.game.MovementUtil;
import client.nilore.event.EventTarget;

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
                RotationHandler.resetToPlayerRotation();
            }
        }
    }

    private static final float RESET_SPEED = 180.0f;

    private static void resetToPlayerRotation() {
        if (mc.player == null) {
            isRotating = false;
            return;
        }
        float playerYaw = mc.player.getYRot();
        float playerPitch = mc.player.getXRot();
        if (sentRotation == null) {
            RotationHandler.setTargetRotation(new Rotation(playerYaw, playerPitch));
            isRotating = false;
            return;
        }
        float yawDiff = Mth.wrapDegrees(playerYaw - sentRotation.getYaw());
        float pitchDiff = playerPitch - sentRotation.getPitch();
        if (Math.abs(yawDiff) < 0.01f && Math.abs(pitchDiff) < 0.01f) {
            RotationHandler.setTargetRotation(new Rotation(sentRotation.getYaw(), Mth.clamp(playerPitch, -90.0f, 90.0f)));
            isRotating = false;
            return;
        }
        float yaw = sentRotation.getYaw() + Mth.clamp(yawDiff, -RESET_SPEED, RESET_SPEED);
        float pitch = Mth.clamp(sentRotation.getPitch() + Mth.clamp(pitchDiff, -RESET_SPEED, RESET_SPEED), -90.0f, 90.0f);
        RotationHandler.setTargetRotation(new Rotation(yaw, pitch));
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
            float baseYaw = sentRotation != null ? sentRotation.getYaw() : mc.player.getYRot();
            float yaw = baseYaw + Mth.wrapDegrees(targetRotation.getYaw() - baseYaw);
            float pitch = Mth.clamp(targetRotation.getPitch(), -90.0f, 90.0f);
            if (Math.abs(yaw - baseYaw) > 100.0f) {
                logger.info("[Rot] jump base={} target={} out={} delta={} rotating={}",
                        baseYaw, targetRotation.getYaw(), yaw, yaw - baseYaw, isRotating);
            }
            if (!Float.isNaN(yaw) && !Float.isNaN(pitch) && !Float.isInfinite(yaw) && !Float.isInfinite(pitch)) {
                e.setYaw(yaw);
                e.setPitch(pitch);
            }
            sentRotation = new Rotation(e.getYaw(), Mth.clamp(e.getPitch(), -90.0f, 90.0f));
            prevRotation = new Rotation(e.getYaw(), Mth.clamp(e.getPitch(), -90.0f, 90.0f));
            ClientBase.yaw = sentRotation.getYaw();
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