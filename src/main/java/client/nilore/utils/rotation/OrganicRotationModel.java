package client.nilore.utils.rotation;

import net.minecraft.util.Mth;
import java.util.Random;

public class OrganicRotationModel {

    private double speed;
    private final double driftIntensity;
    private final double jitterIntensity;

    private final double freqYaw1, freqYaw2, freqPitch1, freqPitch2;
    private final double phaseYaw1, phaseYaw2, phasePitch1, phasePitch2;
    private double timeAccumulator;

    private final Random random;

    public OrganicRotationModel(final double speed, final double driftIntensity, final double jitterIntensity) {
        this.speed = speed;
        this.driftIntensity = driftIntensity;
        this.jitterIntensity = jitterIntensity;

        this.random = new Random(System.nanoTime());
        this.freqYaw1 = random.nextDouble() * 0.3 + 0.1;
        this.freqYaw2 = random.nextDouble() * 0.5 + 0.5;
        this.freqPitch1 = random.nextDouble() * 0.3 + 0.1;
        this.freqPitch2 = random.nextDouble() * 0.5 + 0.5;
        this.phaseYaw1 = random.nextDouble() * Math.PI * 2;
        this.phaseYaw2 = random.nextDouble() * Math.PI * 2;
        this.phasePitch1 = random.nextDouble() * Math.PI * 2;
        this.phasePitch2 = random.nextDouble() * Math.PI * 2;
        this.timeAccumulator = 0.0;
    }

    public Rotation tick(Rotation from, Rotation to, float timeDelta) {
        final float rawYaw = Mth.wrapDegrees(to.getYaw() - from.getYaw());
        final float rawPitch = to.getPitch() - from.getPitch();
        float deltaYaw = rawYaw * timeDelta;
        float deltaPitch = rawPitch * timeDelta;

        final double distance = Math.hypot(deltaYaw, deltaPitch);
        if (distance < driftIntensity) {
            return new Rotation(from.getYaw() + deltaYaw, from.getPitch() + deltaPitch);
        }

        if (distance > 0) {
            final double ratioYaw = Math.abs(deltaYaw) / distance;
            final double ratioPitch = Math.abs(deltaPitch) / distance;
            final double maxYaw = speed * ratioYaw * timeDelta;
            final double maxPitch = speed * ratioPitch * timeDelta;
            deltaYaw = Mth.clamp(deltaYaw, (float) -maxYaw, (float) maxYaw);
            deltaPitch = Mth.clamp(deltaPitch, (float) -maxPitch, (float) maxPitch);
        }

        timeAccumulator += timeDelta;

        final double sinYaw = Math.sin(timeAccumulator * freqYaw1 + phaseYaw1)
                + (random.nextDouble() * 0.1 + 0.45) * Math.sin(timeAccumulator * freqYaw2 + phaseYaw2);
        final double sinPitch = Math.sin(timeAccumulator * freqPitch1 + phasePitch1)
                + (random.nextDouble() * 0.1 + 0.45) * Math.sin(timeAccumulator * freqPitch2 + phasePitch2);
        final double driftYaw = sinYaw * driftIntensity * timeDelta;
        final double driftPitch = sinPitch * driftIntensity * timeDelta;

        final double jitterYaw = (random.nextDouble() * 2 - 1) * jitterIntensity * timeDelta;
        final double jitterPitch = (random.nextDouble() * 2 - 1) * jitterIntensity * timeDelta;

        final float moveYaw = (float) (deltaYaw + driftYaw + jitterYaw);
        final float movePitch = (float) (deltaPitch + driftPitch + jitterPitch);

        final float finalYaw = from.getYaw() + moveYaw;
        final float finalPitch = Mth.clamp(from.getPitch() + movePitch, -90.0f, 90.0f);

        final Rotation result = new Rotation(finalYaw, finalPitch);
        return patchConstantRotation(result, from);
    }

    private static Rotation patchConstantRotation(Rotation rotation, Rotation prevRotation) {
        final double sensitivity = net.minecraft.client.Minecraft.getInstance().options.sensitivity().get().floatValue() * 0.6 + 0.2;
        final double multiplier = (sensitivity * sensitivity * sensitivity) * 8.0;
        final double divisor = multiplier * 0.15;

        final float yawDelta = rotation.getYaw() - prevRotation.getYaw();
        final float pitchDelta = rotation.getPitch() - prevRotation.getPitch();
        final float yaw = prevRotation.getYaw() + (float) (Math.round(yawDelta / divisor) * divisor);
        final float pitch = prevRotation.getPitch() + (float) (Math.round(pitchDelta / divisor) * divisor);
        return new Rotation(yaw, pitch);
    }

    public double getSpeed() {
        return speed;
    }

    public void setSpeed(double speed) {
        this.speed = speed;
    }

    public double getDriftIntensity() {
        return driftIntensity;
    }

    public double getJitterIntensity() {
        return jitterIntensity;
    }
}
