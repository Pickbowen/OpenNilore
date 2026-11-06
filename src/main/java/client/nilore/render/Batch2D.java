package client.nilore.render;

/**
 * Coordinates the batched 2D primitives this client owns.
 *
 * <p>Text is deliberately <b>not</b> batched here: glyph quads have to be submitted in call
 * order relative to everything else, and deferring them shifted their brightness/blend state
 * relative to the immediate path. Only the rounded-rect batch is kept.
 */
public final class Batch2D {
    private static boolean roundedRectOpen;

    private Batch2D() {
    }

    /** Marks the rounded-rect batch as the one currently accepting quads. */
    public static void useRoundedRect() {
        roundedRectOpen = true;
    }

    /** Flushes whatever is open. Call before anything this client does not batch itself. */
    public static void flushAll() {
        roundedRectOpen = false;
        RoundedRectShader.flushBatch();
    }

    public static boolean isRoundedRectOpen() {
        return roundedRectOpen;
    }
}
