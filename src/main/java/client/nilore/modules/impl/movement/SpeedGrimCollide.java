package client.nilore.modules.impl.movement;

final class SpeedGrimCollide {
    private static final double MAX_BOOST = 0.16;
    private static final double MAX_LIMIT = 1.0;
    private static final int MAX_CONTACTS = 4;

    private SpeedGrimCollide() { }

    private static final double EPSILON = 1.0E-9;

    static double increment(SpeedAccess.Collision collision, float radians, double boost, double limit) {
        if (collision == null || collision.contacts() <= 0 || !Float.isFinite(radians)
                || !Double.isFinite(collision.motionX()) || !Double.isFinite(collision.motionZ())
                || !Double.isFinite(boost) || boost <= 0 || boost > MAX_BOOST + EPSILON
                || !Double.isFinite(limit) || limit <= 0 || limit > MAX_LIMIT + EPSILON) return 0;
        double x = collision.motionX(), z = collision.motionZ();
        double squared = x * x + z * z;
        if (squared >= limit * limit) return 0;
        double along = -Math.sin(radians) * x + Math.cos(radians) * z;
        double remaining = -along + Math.sqrt(along * along + limit * limit - squared);
        return Math.max(0, Math.min(boost * Math.min(MAX_CONTACTS, collision.contacts()), remaining));
    }
}
