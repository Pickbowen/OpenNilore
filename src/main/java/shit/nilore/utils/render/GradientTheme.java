package shit.nilore.utils.render;

import java.awt.Color;

/**
 * Gradient theme presets for ModuleList and other HUD elements.
 */
public enum GradientTheme {
    RAINBOW("Rainbow"),
    AURORA("Aurora"),
    SUNSET("Sunset"),
    OCEAN("Ocean"),
    NEON("Neon"),
    PASTEL("Pastel"),
    FIRE("Fire"),
    FOREST("Forest"),
    GALAXY("Galaxy"),
    CHERRY("Cherry");

    private final String displayName;

    GradientTheme(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Get the gradient colors for this theme.
     * Returns an array of RGB colors that form the gradient.
     */
    public int[] getColors() {
        return switch (this) {
            case RAINBOW -> new int[]{
                0xFF0000, 0xFF8000, 0xFFFF00, 0x00FF00, 0x0080FF, 0x8000FF, 0xFF00FF
            };
            case AURORA -> new int[]{
                0x00FF87, 0x00E5FF, 0x7C4DFF, 0xFF4081
            };
            case SUNSET -> new int[]{
                0xFF6B6B, 0xFF8E53, 0xFFD93D, 0xFF6B9D
            };
            case OCEAN -> new int[]{
                0x006994, 0x0099CC, 0x00CED1, 0x48D1CC, 0x40E0D0
            };
            case NEON -> new int[]{
                0xFF00FF, 0x00FFFF, 0xFF00FF, 0x00FF00, 0xFFFF00
            };
            case PASTEL -> new int[]{
                0xFFB3BA, 0xFFDFBA, 0xFFFFBA, 0xBAFFC9, 0xBAE1FF
            };
            case FIRE -> new int[]{
                0xFF0000, 0xFF4500, 0xFF8C00, 0xFFD700, 0xFFFF00
            };
            case FOREST -> new int[]{
                0x004D00, 0x008000, 0x00CC00, 0x66FF66, 0x99FF99
            };
            case GALAXY -> new int[]{
                0x0D0221, 0x150734, 0x261447, 0x3A1C71, 0x5C2D91, 0x7B2FBE
            };
            case CHERRY -> new int[]{
                0xFF1744, 0xFF4081, 0xFF80AB, 0xFF80AB, 0xFF4081, 0xFF1744
            };
        };
    }

    /**
     * Get color at a specific position in the gradient (0.0 to 1.0).
     */
    public Color getColorAt(double position, float saturation, float brightness) {
        int[] colors = getColors();
        if (colors.length == 0) return Color.WHITE;

        // Normalize position to 0.0 - 1.0
        position = position % 1.0;
        if (position < 0) position += 1.0;

        // Find the two colors to interpolate between
        double scaledPos = position * (colors.length - 1);
        int index = (int) Math.floor(scaledPos);
        double fraction = scaledPos - index;

        if (index >= colors.length - 1) {
            return new Color(colors[colors.length - 1]);
        }

        // Interpolate between the two colors
        int color1 = colors[index];
        int color2 = colors[index + 1];

        int r1 = (color1 >> 16) & 0xFF;
        int g1 = (color1 >> 8) & 0xFF;
        int b1 = color1 & 0xFF;

        int r2 = (color2 >> 16) & 0xFF;
        int g2 = (color2 >> 8) & 0xFF;
        int b2 = color2 & 0xFF;

        int r = (int) (r1 + (r2 - r1) * fraction);
        int g = (int) (g1 + (g2 - g1) * fraction);
        int b = (int) (b1 + (b2 - b1) * fraction);

        // Adjust saturation and brightness
        float[] hsb = Color.RGBtoHSB(r, g, b, null);
        return Color.getHSBColor(hsb[0], saturation / 100.0f, brightness / 100.0f);
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
