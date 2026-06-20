package shit.nilore.modules.impl.misc.ai.path;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import shit.nilore.modules.impl.misc.ai.Blackboard;

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

        // Skip ahead: if player already passed waypoints (e.g. jumped over them),
        // advance to the first unvisited one. Prevents 180° turning.
        // Baritone approach: check destination reached = player is AT the block.
        int playerY = (int) Math.floor(player.getY());
        while (pathPosition < path.length()) {
            BetterBlockPos wp = path.get(pathPosition);
            double wpDx = wp.x + 0.5 - player.getX();
            double wpDz = wp.z + 0.5 - player.getZ();
            double wpDist = Math.sqrt(wpDx * wpDx + wpDz * wpDz);
            boolean wpAscending = wp.y > playerY;

            boolean reached;
            if (wpAscending) {
                // Ascending: player at/above target Y AND close in XZ
                // OR player above target Y AND very close (overshot jump landing nearby)
                reached = (playerY >= wp.y && wpDist < 0.6)
                       || (playerY > wp.y && wpDist < 1.5);
            } else {
                reached = wpDist < 0.6 && Math.abs(wp.y - playerY) <= 1;
            }

            if (reached) {
                pathPosition++;
                ticksOnCurrent = 0;
            } else {
                break;
            }
        }

        if (pathPosition >= path.length()) {
            complete = true;
            clearMovement();
            return true;
        }

        BetterBlockPos target = path.get(pathPosition);
        double dx = target.x + 0.5 - player.getX();
        double dz = target.z + 0.5 - player.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        boolean ascending = target.y > playerY;

        ticksOnCurrent++;
        if (ticksOnCurrent > 100) {
            failed = true;
            clearMovement();
            return true;
        }

        moveToward(dx, dz, ascending);
        return false;
    }

    private void moveToward(double dx, double dz, boolean needJump) {
        if (mc.player == null) return;

        double len = Math.sqrt(dx * dx + dz * dz);

        // Ascending + very close in XZ: use path direction instead of dx/dz
        // (dx/dz is nearly zero when standing right below the target block)
        if (needJump && len < 0.5) {
            float pathYaw = getPathDirectionYaw();
            if (!Float.isNaN(pathYaw)) {
                dx = -Math.sin(Math.toRadians(pathYaw));
                dz = Math.cos(Math.toRadians(pathYaw));
                len = Math.sqrt(dx * dx + dz * dz);
            }
        }

        if (len < 0.01) {
            clearMovement();
            return;
        }

        movementYaw = (float) (-Math.toDegrees(Math.atan2(dx, dz)));

        if (needJump) {
            // Ascending: directly set player yaw and force forward + jump
            // Don't use smoothYaw + strafe calculation — it causes 180° oscillation
            // when player hasn't rotated yet and forward component is negative
            mc.player.setYRot(movementYaw);
            mc.options.keyUp.setDown(true);
            mc.options.keyDown.setDown(false);
            mc.options.keyRight.setDown(false);
            mc.options.keyLeft.setDown(false);
            mc.options.keySprint.setDown(true);
            mc.options.keyJump.setDown(true);
            if (mc.player.onGround()) {
                mc.player.jumpFromGround();
            }
            return;
        }

        // Flat movement: smooth rotation + strafe-based movement
        Blackboard.smoothYaw(movementYaw, 30f);

        float yawRad = (float) Math.toRadians(mc.player.getYRot());
        float forwardX = (float) -Math.sin(yawRad);
        float forwardZ = (float) Math.cos(yawRad);
        float strafeX = forwardZ;
        float strafeZ = -forwardX;

        double normDx = dx / len;
        double normDz = dz / len;
        double forward = normDx * forwardX + normDz * forwardZ;
        double strafe = normDx * strafeX + normDz * strafeZ;

        mc.options.keyUp.setDown(forward > 0.15);
        mc.options.keyDown.setDown(forward < -0.15);
        mc.options.keyRight.setDown(strafe > 0.15);
        mc.options.keyLeft.setDown(strafe < -0.15);
        mc.options.keySprint.setDown(forward > 0.5);
        mc.options.keyJump.setDown(false);
    }

    /**
     * Get path direction yaw from the previous node to the current target.
     */
    private float getPathDirectionYaw() {
        if (pathPosition <= 0 || pathPosition >= path.length()) return Float.NaN;
        BetterBlockPos prev = path.get(pathPosition - 1);
        BetterBlockPos curr = path.get(pathPosition);
        double pdx = curr.x - prev.x;
        double pdz = curr.z - prev.z;
        double pdLen = Math.sqrt(pdx * pdx + pdz * pdz);
        if (pdLen < 0.01) return Float.NaN;
        return (float) (-Math.toDegrees(Math.atan2(pdx, pdz)));
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
