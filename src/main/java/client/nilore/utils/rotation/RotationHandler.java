package client.nilore.utils.rotation;

import java.util.Random;

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
        // Keep the value handed to other patches (attack direction reads this via
        // PlayerPatch) in the same accumulated frame as the outgoing rotation and apply
        // the same naturalisation - otherwise the two disagree by a multiple of 360.
        float y = rotation.getYaw();
        if (sentRotation != null) {
            y = sentRotation.getYaw() + Mth.wrapDegrees(y - sentRotation.getYaw());
        }
        ClientBase.yaw = naturalizeYaw(y, sentRotation != null ? sentRotation.getYaw() : y);
    }

    @EventTarget
    public void onWorldChange(WorldChangeEvent worldChangeEvent) {
        prevRotation = null;
        targetRotation = null;
        lastWireYaw = Float.NaN;
        lastWirePitch = Float.NaN;
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
                // NO module owns the rotation this tick -> hands off completely. The
                // outgoing yaw/pitch stay exactly vanilla's; we only keep sentRotation in
                // sync so the next silent-rotation starts from the right base.
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
            // Nobody owns the rotation: vanilla's own yaw/pitch go out untouched apart from
            // the naturalisation below.
            if (!isRotating) {
                prevSentRotation = sentRotation;
                // Keep sentRotation in the same (accumulated) yaw frame as the silent
                // rotation used: vanilla's yRot may sit 360 apart from it, and copying the
                // wrapped value straight in made a -360 delta (Grim AimModulo360).
                float vanillaYaw = e.getYaw();
                float vanillaPitch = e.getPitch();
                if (sentRotation != null) {
                    vanillaYaw = sentRotation.getYaw() + Mth.wrapDegrees(vanillaYaw - sentRotation.getYaw());
                }
                float refYaw = sentRotation != null ? sentRotation.getYaw() : vanillaYaw;
                float refPitch = sentRotation != null ? sentRotation.getPitch() : vanillaPitch;
                float naturalYaw = naturalizeYaw(vanillaYaw, refYaw);
                float naturalPitch = naturalizePitch(vanillaPitch, refPitch);
                // Write even when the naturalisation was a no-op. sentRotation records this
                // value, so leaving the packet on vanilla's own frame while sentRotation sat a
                // multiple of 360 away made the next tick's write jump by that much - Grim
                // AimModulo360, which fired on every tick spent standing at a yaw near 0. When
                // nothing is quantised the value written is vanilla's own, so this changes
                // nothing on the ticks that do not need a nudge.
                e.setYaw(naturalYaw);
                e.setPitch(naturalPitch);
                prevRotation = sentRotation = new Rotation(naturalYaw, Mth.clamp(naturalPitch, -90.0f, 90.0f));
                ClientBase.yaw = sentRotation.getYaw();
                guardDuplicateRotation(e);
                return;
            }
            if (targetRotation == null || prevRotation == null) {
                targetRotation = prevRotation = new Rotation(mc.player.getYRot(), mc.player.getXRot());
            }
            prevSentRotation = sentRotation;
            // Never wrap the outgoing yaw: the local yRot accumulates past +/-180 when the
            // player spins the view, and normalising here would turn an identical rotation
            // into a -360 delta (Grim AimModulo360).
            float baseYaw = sentRotation != null ? sentRotation.getYaw() : mc.player.getYRot();
            float yaw = baseYaw + Mth.wrapDegrees(targetRotation.getYaw() - baseYaw);
            float pitch = Mth.clamp(targetRotation.getPitch(), -90.0f, 90.0f);
            float refYaw = sentRotation != null ? sentRotation.getYaw() : baseYaw;
            float refPitch = sentRotation != null ? sentRotation.getPitch() : pitch;
            yaw = naturalizeYaw(yaw, refYaw);
            pitch = naturalizePitch(pitch, refPitch);
            if (Math.abs(yaw - baseYaw) > 100.0f) {
                logger.info("[Rot] jump base={} target={} out={} delta={} rotating={}",
                        baseYaw, targetRotation.getYaw(), yaw, yaw - baseYaw, isRotating);
            }
            if (!Float.isNaN(yaw) && !Float.isNaN(pitch) && !Float.isInfinite(yaw) && !Float.isInfinite(pitch)) {
                e.setYaw(yaw);
                e.setPitch(pitch);
                guardDuplicateRotation(e);
            }
            sentRotation = new Rotation(e.getYaw(), Mth.clamp(e.getPitch(), -90.0f, 90.0f));
            prevRotation = new Rotation(e.getYaw(), Mth.clamp(e.getPitch(), -90.0f, 90.0f));
            ClientBase.yaw = sentRotation.getYaw();
        }
    }

    /** Rotation deltas landing exactly on these steps are a machine fingerprint. */
    private static final double[] ROTATION_STEPS = new double[]{
            0.0, 5.625, 11.25, 16.875, 22.5, 28.125, 33.75, 39.375, 45.0,
            50.625, 56.25, 61.875, 67.5, 73.125, 78.75, 84.375, 90.0};
    private static final Random NATURAL_RANDOM = new Random();

    private static boolean landsOnKnownStep(double delta) {
        if (Double.isNaN(delta) || Double.isInfinite(delta)) {
            return false;
        }
        for (double step : ROTATION_STEPS) {
            if (step == 0.0) {
                if (Math.abs(delta) <= 1.0E-10) {
                    return true;
                }
                continue;
            }
            double ratio = delta / step;
            if (Math.abs(ratio - (double) Math.round(ratio)) <= 1.0E-10) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMultipleOf360(double delta) {
        if (Math.abs(delta) <= 1.0E-10) {
            return true;
        }
        double ratio = delta / 360.0;
        return Math.abs(ratio - (double) Math.round(ratio)) <= 1.0E-10;
    }

    /**
     * Adds a sub-perceptual nudge whenever the outgoing rotation looks quantised: the
     * delta hits a known step, the delta is an exact multiple of 360, or the value itself
     * sits on a 360 boundary. 0.005 deg is far below anything a player or a hitbox check
     * would notice, but it breaks the exact-match signatures.
     */
    private static float naturalizeYaw(float yaw, float reference) {
        double delta = Math.abs(Mth.wrapDegrees(yaw - reference));
        double mod = Math.abs(yaw % 360.0f);
        boolean quantised = landsOnKnownStep(delta)
                || Math.abs(mod) <= 1.0E-4
                || Math.abs(mod - 360.0f) <= 1.0E-4
                || delta > 1.0E-10 && isMultipleOf360(delta);
        return quantised ? yaw + (float) (NATURAL_RANDOM.nextGaussian() * 0.005) : yaw;
    }

    private static float naturalizePitch(float pitch, float reference) {
        double delta = Math.abs(pitch - reference);
        boolean quantised = landsOnKnownStep(delta) || Math.abs(Math.abs(pitch) - 90.0f) <= 1.0E-4;
        return quantised
                ? Mth.clamp(pitch + (float) (NATURAL_RANDOM.nextGaussian() * 0.005), -90.0f, 90.0f)
                : pitch;
    }

    /** Yaw/pitch of the last rotation packet that actually left the client. NaN = none yet. */
    private static float lastWireYaw = Float.NaN;
    private static float lastWirePitch = Float.NaN;

    /**
     * Hard invariant on top of the naturalisation: two consecutive rotation packets must
     * never carry the same yaw AND pitch. The naturalisation above only nudges when the
     * delta looks quantised, so a held aim can still leave the event carrying the previous
     * packet's value - which is exactly the signature the server's equalrotation part
     * reports. This runs on the value the packet leaves with, so it holds whichever branch
     * produced it.
     */
    private static void guardDuplicateRotation(MotionEvent e) {
        float yaw = e.getYaw();
        float pitch = Mth.clamp(e.getPitch(), -90.0f, 90.0f);
        if (yaw == lastWireYaw && pitch == lastWirePitch) {
            yaw = nudgeApart(yaw);
            e.setYaw(yaw);
            logger.info("[Rot] equalrotation guard {} -> {}", lastWireYaw, yaw);
        }
        lastWireYaw = yaw;
        lastWirePitch = pitch;
    }

    /** Moves the value by an imperceptible amount that is guaranteed to change the float. */
    private static float nudgeApart(float value) {
        float delta = 0.0025f + NATURAL_RANDOM.nextFloat() * 0.005f;
        if (NATURAL_RANDOM.nextBoolean()) {
            delta = -delta;
        }
        float nudged = value + delta;
        // At a large accumulated yaw the addend can sit below one ULP and vanish; widen it
        // until the float actually moves.
        for (int i = 0; nudged == value && i < 8; i++) {
            delta *= 2.0f;
            nudged = value + delta;
        }
        return nudged;
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