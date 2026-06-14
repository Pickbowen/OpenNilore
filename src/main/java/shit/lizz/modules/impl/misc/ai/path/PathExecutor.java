package shit.lizz.modules.impl.misc.ai.path;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import shit.lizz.modules.impl.misc.ai.Blackboard;
import shit.lizz.modules.impl.movement.Scaffold;

public class PathExecutor {
    private static final Minecraft mc = Minecraft.getInstance();

    private final Path path;
    private int pathPosition = 0;
    private int ticksOnCurrent = 0;
    private boolean failed = false;
    private boolean complete = false;
    private float movementYaw = Float.NaN;

    public PathExecutor(Path path) {
        this.path = path;
    }

    public boolean onTick() {
        if (complete || failed) return true;
        if (mc.player == null) { failed = true; return true; }

        Player player = mc.player;

        if (pathPosition >= path.length()) {
            complete = true;
            clearMovement();
            return true;
        }

        BetterBlockPos target = path.get(pathPosition);
        double dx = target.x + 0.5 - player.getX();
        double dz = target.z + 0.5 - player.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        double distY = Math.abs(target.y - (int) Math.floor(player.getY()));

        // Reached this position
        if (distXZ < 0.4 && distY <= 1) {
            pathPosition++;
            ticksOnCurrent = 0;
            if (pathPosition >= path.length()) {
                complete = true;
                clearMovement();
                return true;
            }
            return false;
        }

        ticksOnCurrent++;
        if (ticksOnCurrent > 100) {
            failed = true;
            clearMovement();
            return true;
        }

        moveToward(dx, dz, target.y > (int) Math.floor(player.getY()));
        return false;
    }

    private void moveToward(double dx, double dz, boolean needJump) {
        if (mc.player == null) return;

        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) {
            clearMovement();
            return;
        }

        movementYaw = (float) (-Math.toDegrees(Math.atan2(dx, dz)));

        // Don't override rotation when Scaffold is active (it controls its own rotation for block placement)
        if (Scaffold.INSTANCE == null || !Scaffold.INSTANCE.isEnabled()) {
            Blackboard.smoothYaw(movementYaw, 30f);
        }

        mc.options.keyUp.setDown(true);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(true);

        if (needJump) {
            mc.options.keyJump.setDown(true);
        } else {
            mc.options.keyJump.setDown(false);
        }
    }

    private void clearMovement() {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
        movementYaw = Float.NaN;
    }

    public boolean isFailed() { return failed; }
    public boolean isComplete() { return complete; }
    public float getMovementYaw() { return movementYaw; }
    public Path getPath() { return path; }
    public int getPathPosition() { return pathPosition; }
}
