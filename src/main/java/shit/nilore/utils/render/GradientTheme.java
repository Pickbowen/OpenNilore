package shit.nilore.utils.render;

import java.awt.Color;

/**
 * Gradient theme presets for ModuleList and other HUD elements.
 * Uses HSB color space for smoother interpolation.
 */
public enum GradientTheme {
    RAINBOW("Rainbow"),
    AURORA("Aurora"),
    SUNSET("Sunset"),
    OCEAN("Ocean"),
    COTTON("Cotton Candy"),
    LAVENDER("Lavender"),
    PEACH("Peach"),
    MINT("Mint"),
    CYBER("Cyberpunk"),
    DRIFT("Drift");

    private final String displayName;

    GradientTheme(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Get HSB color arrays [hue, saturation, brightness] for smooth interpolation.
     */
    public float[][] getHSBColors() {
        return switch (this) {
            case RAINBOW -> new float[][] {
                {0.00f, 0.85f, 1.0f},  // Red
                {0.08f, 0.85f, 1.0f},  // Orange
                {0.15f, 0.85f, 1.0f},  // Yellow
                {0.33f, 0.85f, 1.0f},  // Green
                {0.55f, 0.85f, 1.0f},  // Cyan
                {0.67f, 0.85f, 1.0f},  // Blue
                {0.78f, 0.85f, 1.0f},  // Purple
                {0.92f, 0.85f, 1.0f},  // Pink
                {1.00f, 0.85f, 1.0f},  // Back to Red
            };
            case AURORA -> new float[][] {
                {0.45f, 0.70f, 1.0f},  // Teal
                {0.52f, 0.60f, 1.0f},  // Cyan
                {0.72f, 0.55f, 0.95f},  // Soft Blue
                {0.82f, 0.50f, 0.90f},  // Lavender
                {0.90f, 0.55f, 0.95f},  // Pink
                {0.45f, 0.70f, 1.0f},  // Back to Teal
            };
            case SUNSET -> new float[][] {
                {0.98f, 0.70f, 1.0f},  // Coral
                {0.05f, 0.75f, 1.0f},  // Orange
                {0.12f, 0.65f, 1.0f},  // Gold
                {0.15f, 0.50f, 1.0f},  // Soft Yellow
                {0.95f, 0.55f, 0.95f},  // Rose
                {0.98f, 0.70f, 1.0f},  // Back to Coral
            };
            case OCEAN -> new float[][] {
                {0.57f, 0.80f, 0.90f},  // Deep Blue
                {0.53f, 0.70f, 0.95f},  // Blue
                {0.50f, 0.60f, 1.0f},  // Cyan
                {0.47f, 0.55f, 0.95f},  // Teal
                {0.43f, 0.50f, 0.90f},  // Aqua
                {0.57f, 0.80f, 0.90f},  // Back to Deep Blue
            };
            case COTTON -> new float[][] {
                {0.92f, 0.40f, 1.0f},  // Pink
                {0.88f, 0.35f, 1.0f},  // Soft Pink
                {0.80f, 0.30f, 1.0f},  // Rose
                {0.70f, 0.35f, 1.0f},  // Lavender
                {0.60f, 0.40f, 1.0f},  // Light Purple
                {0.92f, 0.40f, 1.0f},  // Back to Pink
            };
            case LAVENDER -> new float[][] {
                {0.75f, 0.50f, 0.90f},  // Deep Lavender
                {0.78f, 0.45f, 0.95f},  // Lavender
                {0.82f, 0.40f, 1.0f},  // Soft Purple
                {0.85f, 0.35f, 1.0f},  // Light Purple
                {0.80f, 0.45f, 0.95f},  // Mauve
                {0.75f, 0.50f, 0.90f},  // Back to Deep Lavender
            };
            case PEACH -> new float[][] {
                {0.05f, 0.50f, 1.0f},  // Peach
                {0.08f, 0.45f, 1.0f},  // Soft Orange
                {0.10f, 0.40f, 1.0f},  // Light Orange
                {0.98f, 0.45f, 1.0f},  // Coral
                {0.95f, 0.50f, 1.0f},  // Salmon
                {0.05f, 0.50f, 1.0f},  // Back to Peach
            };
            case MINT -> new float[][] {
                {0.42f, 0.55f, 0.95f},  // Mint
                {0.45f, 0.50f, 1.0f},  // Soft Green
                {0.48f, 0.45f, 1.0f},  // Light Green
                {0.50f, 0.40f, 0.95f},  // Seafoam
                {0.47f, 0.50f, 1.0f},  // Aqua Mint
                {0.42f, 0.55f, 0.95f},  // Back to Mint
            };
            case CYBER -> new float[][] {
                {0.85f, 0.75f, 1.0f},  // Magenta
                {0.90f, 0.70f, 1.0f},  // Hot Pink
                {0.95f, 0.65f, 0.95f},  // Pink
                {0.55f, 0.75f, 1.0f},  // Cyan
                {0.50f, 0.70f, 1.0f},  // Electric Blue
                {0.85f, 0.75f, 1.0f},  // Back to Magenta
            };
            case DRIFT -> new float[][] {
                {0.60f, 0.65f, 0.90f},  // Steel Blue
                {0.65f, 0.55f, 0.95f},  // Cool Blue
                {0.70f, 0.50f, 1.0f},  // Periwinkle
                {0.75f, 0.55f, 0.95f},  // Soft Violet
                {0.68f, 0.60f, 0.90f},  // Dusty Blue
                {0.60f, 0.65f, 0.90f},  // Back to Steel Blue
            };
        };
    }

    /**
     * Smoothstep interpolation for smoother transitions.
     */
    private static double smoothstep(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * Get color at a specific position in the gradient (0.0 to 1.0).
     * Uses HSB interpolation for smoother color transitions.
     */
    public Color getColorAt(double position, float saturationScale, float brightnessScale) {
        float[][] colors = getHSBColors();
        if (colors.length == 0) return Color.WHITE;

        // Normalize position to 0.0 - 1.0
        position = position % 1.0;
        if (position < 0) position += 1.0;

        // Find the two colors to interpolate between
        double scaledPos = position * (colors.length - 1);
        int index = (int) Math.floor(scaledPos);
        double fraction = scaledPos - index;

        // Apply smoothstep for smoother transitions
        fraction = smoothstep(fraction);

        if (index >= colors.length - 1) {
            float[] c = colors[colors.length - 1];
            return Color.getHSBColor(c[0], c[1] * saturationScale / 100.0f, c[2] * brightnessScale / 100.0f);
        }

        // Interpolate in HSB space
        float[] c1 = colors[index];
        float[] c2 = colors[index + 1];

        // Handle hue wrapping (shortest path around the color wheel)
        float hue1 = c1[0];
        float hue2 = c2[0];
        float hueDiff = hue2 - hue1;
        if (Math.abs(hueDiff) > 0.5f) {
            if (hueDiff > 0) {
                hue1 += 1.0f;
            } else {
                hue2 += 1.0f;
            }
        }

        float hue = (float) (hue1 + (hue2 - hue1) * fraction) % 1.0f;
        float sat = (float) (c1[1] + (c2[1] - c1[1]) * fraction);
        float bri = (float) (c1[2] + (c2[2] - c1[2]) * fraction);

        // Apply user adjustments
        sat = Math.min(1.0f, sat * saturationScale / 100.0f);
        bri = Math.min(1.0f, bri * brightnessScale / 100.0f);

        return Color.getHSBColor(hue, sat, bri);
    }

    /**
     * Get a theme by its display name.
     */
    public static GradientTheme fromName(String name) {
        for (GradientTheme theme : values()) {
            if (theme.getDisplayName().equalsIgnoreCase(name)) {
                return theme;
            }
        }
        return RAINBOW;
    }

    /**
     * Get all theme names.
     */
    public static String[] getNames() {
        GradientTheme[] themes = values();
        String[] names = new String[themes.length];
        for (int i = 0; i < themes.length; i++) {
            names[i] = themes[i].getDisplayName();
        }
        return names;
    }
}
