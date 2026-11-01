package client.nilore.utils.rotation;

import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 *
 *
 * <ol>
 * </ol>
 *
 */
public class RotationSmoother {

    private static final double TURN_RATIO = 0.5;
    public static final double RESP_UP = 0.8;
    public static final double RESP_DOWN = 0.4;
    public static final double WOBBLE = 0.2;
    public static final double PITCH_SPEED_SCALE = 0.65;
    private static final double ARRIVE_SOFT_ZONE = 1.2;
    private static final double DUPLICATE_EPSILON = 1.0E-4;

    private final Random random;
    private double velYaw;
    private double velPitch;
    private float lastEmittedYawDelta = Float.NaN;
    private float lastEmittedPitchDelta = Float.NaN;

    public RotationSmoother(Random random) {
        this.random = random;
    }

    public Rotation tick(Rotation from, Rotation to, double speed) {
        return this.tick(from, to, speed, 1.0, PITCH_SPEED_SCALE);
    }

    public Rotation tick(Rotation from, Rotation to, double speed, double yawScale, double pitchScale) {
        float yaw = from.getYaw() + (float) this.smoothAxisDelta(Mth.wrapDegrees(to.getYaw() - from.getYaw()), speed * yawScale, true);
        float pitch = Mth.clamp(from.getPitch() + (float) this.smoothAxisDelta(to.getPitch() - from.getPitch(), speed * pitchScale, false), -90.0f, 90.0f);
        return new Rotation(yaw, pitch);
    }

    public double smoothAxisDelta(double delta, double speed, boolean yawAxis) {
        double dist = Math.abs(delta);
        if (dist <= 0.0 || !Double.isFinite(dist)) {
            this.storeVelocity(yawAxis, 0.0);
            return 0.0;
        }
        double maxVel = Math.max(speed, 0.0);
        double targetVel;
        if (dist <= ARRIVE_SOFT_ZONE) {
            targetVel = Math.max(maxVel * 0.25, Math.sqrt(dist / ARRIVE_SOFT_ZONE) * maxVel);
        } else {
            targetVel = Math.max(maxVel, dist * TURN_RATIO);
        }

        double prevVel = yawAxis ? this.velYaw : this.velPitch;
        double resp = prevVel < targetVel ? RESP_UP : RESP_DOWN;
        double vel = prevVel + (targetVel - prevVel) * resp;
        vel *= 1.0 + (this.random.nextDouble() * 2.0 - 1.0) * WOBBLE;
        vel = Math.min(Math.max(vel, 0.0), dist);

        this.storeVelocity(yawAxis, vel);
        return delta < 0.0 ? -vel : vel;
    }

    private void storeVelocity(boolean yawAxis, double vel) {
        if (yawAxis) {
            this.velYaw = vel;
        } else {
            this.velPitch = vel;
        }
    }

    public void reset() {
        this.velYaw = 0.0;
        this.velPitch = 0.0;
        this.lastEmittedYawDelta = Float.NaN;
        this.lastEmittedPitchDelta = Float.NaN;
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    public static double sensitivityStep() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.options == null) {
            return 0.15;
        }
        double sensitivity = minecraft.options.sensitivity().get().floatValue() * 0.6 + 0.2;
        return sensitivity * sensitivity * sensitivity * 8.0 * 0.15;
    }

    public static float quantizeToStep(double delta, double step) {
        if (!Double.isFinite(delta) || step <= 0.0) {
            return 0.0f;
        }
        return (float) (Math.round(delta / step) * step);
    }

    public static Rotation patchConstantRotation(Rotation rotation, Rotation prevRotation) {
        double step = sensitivityStep();
        float yaw = prevRotation.getYaw() + quantizeToStep(rotation.getYaw() - prevRotation.getYaw(), step);
        float pitch = Mth.clamp(prevRotation.getPitch() + quantizeToStep(rotation.getPitch() - prevRotation.getPitch(), step), -90.0f, 90.0f);
        return new Rotation(yaw, pitch);
    }

    public Rotation breakDuplicateDelta(Rotation rotation, Rotation prev) {
        if (rotation == null || prev == null) {
            return rotation;
        }
        double step = sensitivityStep();
        if (step <= 0.0) {
            return rotation;
        }
        float yawDelta = rotation.getYaw() - prev.getYaw();
        float pitchDelta = rotation.getPitch() - prev.getPitch();

        if (Math.abs(yawDelta) > 1.0E-6F
                && !Float.isNaN(this.lastEmittedYawDelta)
                && Math.abs(yawDelta - this.lastEmittedYawDelta) < DUPLICATE_EPSILON) {
            float nudged = yawDelta + (yawDelta >= 0.0f ? (float) step : (float) -step);
            rotation.setYaw(prev.getYaw() + nudged);
        }
        if (Math.abs(pitchDelta) > 1.0E-6F
                && !Float.isNaN(this.lastEmittedPitchDelta)
                && Math.abs(pitchDelta - this.lastEmittedPitchDelta) < DUPLICATE_EPSILON) {
            float nudged = pitchDelta + (pitchDelta >= 0.0f ? (float) step : (float) -step);
            rotation.setPitch(Mth.clamp(prev.getPitch() + nudged, -90.0f, 90.0f));
        }

        this.lastEmittedYawDelta = rotation.getYaw() - prev.getYaw();
        this.lastEmittedPitchDelta = rotation.getPitch() - prev.getPitch();
        return rotation;
    }
}
