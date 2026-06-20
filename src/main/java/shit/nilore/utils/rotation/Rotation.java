package shit.nilore.utils.rotation;

import java.util.concurrent.ThreadLocalRandom;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.RandomUtils;
import shit.nilore.ClientBase;
import shit.nilore.utils.math.Vector2f;

@EqualsAndHashCode
@ToString
public class Rotation {
    @Getter @Setter
    public float yaw;
    @Getter @Setter
    public float pitch;
    @Getter @Setter
    public double distanceSq;
    @Getter @Setter
    public Runnable task;
    @Getter @Setter
    public Runnable postTask;
    static final boolean ASSERTIONS_DISABLED = true;

    public Rotation() {
        this.yaw = 0.0f;
        this.pitch = 0.0f;
    }

    public Rotation(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public Rotation(Vector2f yawPitch) {
        this.yaw = yawPitch.getX();
        this.pitch = yawPitch.getY();
    }

    public Rotation(Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        this.yaw = Mth.wrapDegrees((float)Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0f);
        this.pitch = Mth.wrapDegrees((float)(-Math.toDegrees(Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z)))));
    }

    public Rotation clone() {
        return new Rotation(this.getYaw(), this.getPitch());
    }

    public Vector2f toVector2f() {
        return new Vector2f(this.yaw, this.pitch);
    }

    public Rotation subtract(Rotation rotation) {
        return new Rotation(this.yaw - rotation.yaw, this.pitch - rotation.pitch);
    }

    public Rotation negate() {
        return new Rotation(-this.yaw, -this.pitch);
    }

    public Rotation withTask(Runnable task) {
        this.task = task;
        return this;
    }

    public Rotation withPostTask(Runnable postTask) {
        this.postTask = postTask;
        return this;
    }

    public void apply() {
        ClientBase.mc.player.setYRot(this.yaw);
        ClientBase.mc.player.setXRot(this.pitch);
    }

    public void applyToPlayer(Player player) {
        if (Float.isNaN(this.yaw) || Float.isNaN(this.pitch)) {
            return;
        }
        this.snapToSensitivity(ClientBase.mc.options.sensitivity().get().floatValue());
        player.setYRot(this.yaw);
        player.setXRot(this.pitch);
    }

    public Rotation snapToSensitivity(Float sensitivity) {
        float scaled = sensitivity.floatValue() * 0.6f + 0.2f;
        float step = scaled * scaled * scaled * 1.2f;
        this.yaw -= this.yaw % step;
        this.pitch -= this.pitch % step;
        return this;
    }

    public static float moveTowards(float current, float target, float maxStep) {
        float diff = Mth.wrapDegrees(target - current);
        if (diff > maxStep) {
            diff = maxStep;
        }
        if (diff < -maxStep) {
            diff = -maxStep;
        }
        return current + diff;
    }

    public double distanceTo(Rotation other) {
        float thisYaw = Mth.wrapDegrees(this.yaw);
        float otherYaw = Mth.wrapDegrees(other.yaw);
        float yawDiff = Mth.wrapDegrees(thisYaw - otherYaw);
        float thisPitch = Mth.wrapDegrees(this.pitch);
        float otherPitch = Mth.wrapDegrees(other.pitch);
        float pitchDiff = Mth.wrapDegrees(thisPitch - otherPitch);
        return Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
    }

    public float smoothYaw(float speed, float current, float target) {
        float stepped = Rotation.moveTowards(current, target, speed + RandomUtils.nextFloat(0.0f, 15.0f));
        double diff = Mth.wrapDegrees(target - current);
        if ((double)(-speed) > diff || diff > (double)speed) {
            if (!ASSERTIONS_DISABLED && ClientBase.mc.player == null) {
                throw new AssertionError();
            }
            stepped += (float)((double)RandomUtils.nextFloat(1.0f, 2.0f) * Math.sin((double)ClientBase.mc.player.getXRot() * Math.PI));
        }
        if (stepped == current) {
            return current;
        }
        float sensitivity = ClientBase.mc.options.sensitivity().get().floatValue();
        if ((double)sensitivity == 0.5) {
            sensitivity = 0.47887325f;
        }
        float scaled = sensitivity * 0.6f + 0.2f;
        float gcd = scaled * scaled * scaled * 8.0f;
        int steps = (int)((6.667 * (double)stepped - 6.666666666666667 * (double)current) / (double)gcd);
        float snapped = (float)steps * gcd;
        stepped = (float)((double)current + (double)snapped * 0.15);
        return stepped;
    }

    public float smoothYawArray(float speed, float[] currentPair, float target) {
        float stepped = Rotation.moveTowards(currentPair[0], target, speed + RandomUtils.nextFloat(0.0f, 15.0f));
        if (stepped != target) {
            stepped += (float)((double)RandomUtils.nextFloat(1.0f, 2.0f) * Math.sin((double)currentPair[1] * Math.PI));
        }
        if (stepped == currentPair[0]) {
            return currentPair[0];
        }
        float sensitivity = ClientBase.mc.options.sensitivity().get().floatValue();
        stepped += (float)(ThreadLocalRandom.current().nextGaussian() * 0.2);
        if ((double)sensitivity == 0.5) {
            sensitivity = 0.47887325f;
        }
        float scaled = sensitivity * 0.6f + 0.2f;
        float gcd = scaled * scaled * scaled * 8.0f;
        int steps = (int)((6.667 * (double)stepped - 6.6666667 * (double)currentPair[0]) / (double)gcd);
        float snapped = (float)steps * gcd;
        stepped = (float)((double)currentPair[0] + (double)snapped * 0.15);
        return stepped;
    }

    public float smoothPitch(float speed, float current, float target) {
        float sensitivity;
        float stepped = Rotation.moveTowards(current, target, speed + RandomUtils.nextFloat(0.0f, 15.0f));
        if (stepped != target) {
            stepped += (float)((double)RandomUtils.nextFloat(1.0f, 2.0f) * Math.sin((double)ClientBase.mc.player.getYRot() * Math.PI));
        }
        if ((double)(sensitivity = ClientBase.mc.options.sensitivity().get().floatValue()) == 0.5) {
            sensitivity = 0.47887325f;
        }
        float scaled = sensitivity * 0.6f + 0.2f;
        float gcd = scaled * scaled * scaled * 8.0f;
        int steps = (int)((6.667 * (double)stepped - 6.666667 * (double)current) / (double)gcd) * -1;
        float snapped = (float)steps * gcd;
        float result = (float)((double)current - (double)snapped * 0.15);
        stepped = Mth.clamp(result, -90.0f, 90.0f);
        return stepped;
    }

    public float smoothPitchArray(float speed, float[] currentPair, float target) {
        float sensitivity;
        float stepped = Rotation.moveTowards(currentPair[1], target, speed + RandomUtils.nextFloat(0.0f, 15.0f));
        if (stepped != target) {
            stepped += (float)((double)RandomUtils.nextFloat(1.0f, 2.0f) * Math.sin((double)currentPair[0] * Math.PI));
        }
        if ((double)(sensitivity = ClientBase.mc.options.sensitivity().get().floatValue()) == 0.5) {
            sensitivity = 0.47887325f;
        }
        float scaled = sensitivity * 0.6f + 0.2f;
        float gcd = scaled * scaled * scaled * 8.0f;
        int steps = (int)((6.667 * (double)stepped - 6.666667 * (double)currentPair[1]) / (double)gcd) * -1;
        float snapped = (float)steps * gcd;
        float result = (float)((double)currentPair[1] - (double)snapped * 0.15);
        stepped = Mth.clamp(result, -90.0f, 90.0f);
        return stepped;
    }

    public void setYawPitch(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

}