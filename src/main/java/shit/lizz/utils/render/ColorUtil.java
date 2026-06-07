package shit.lizz.utils.render;

import java.awt.Color;
import lombok.Generated;
import net.minecraft.world.entity.player.Player;
import shit.lizz.ClientBase;

public final class ColorUtil
extends ClientBase {
    private static final String UTILITY_CLASS_MSG = "This is a utility class and cannot be instantiated";

    public static Color getPlayerColor(Player player) {
        int hash = player.getName().getString().hashCode();
        int red = (hash & 0xFF0000) >> 16;
        int green = (hash & 0xFF00) >> 8;
        int blue = hash & 0xFF;
        return new Color(red, green, blue);
    }

    public static int animateColor(int colorA, int colorB, double progress) {
        if (progress > 1.0) {
            progress = 1.0 - progress % 1.0;
        }
        return ColorUtil.interpolateColor(colorA, colorB, progress);
    }

    public static int animateColorOffset(int colorA, int colorB, long offsetMs) {
        return ColorUtil.animateColor(colorA, colorB, (double)((System.currentTimeMillis() + offsetMs) % 4000L) / 2000.0);
    }

    public static int interpolateColor(int colorA, int colorB, double progress) {
        double inverse = 1.0 - progress;
        int red = (int)((double)(colorA >> 16 & 0xFF) * inverse + (double)(colorB >> 16 & 0xFF) * progress);
        int green = (int)((double)(colorA >> 8 & 0xFF) * inverse + (double)(colorB >> 8 & 0xFF) * progress);
        int blue = (int)((double)(colorA & 0xFF) * inverse + (double)(colorB & 0xFF) * progress);
        int alpha = (int)((double)(colorA >> 24 & 0xFF) * inverse + (double)(colorB >> 24 & 0xFF) * progress);
        return (alpha & 0xFF) << 24 | (red & 0xFF) << 16 | (green & 0xFF) << 8 | blue & 0xFF;
    }

    public static Color getRainbowColor(int speed, int offset) {
        int hueDegrees = (int)((System.currentTimeMillis() / (long)speed + (long)offset) % 360L);
        float hue = (float)hueDegrees / 360.0f;
        return new Color(Color.HSBtoRGB(hue, 0.5f, 1.0f));
    }

    public static int fromRGB(int red, int green, int blue) {
        return ColorUtil.fromARGB(red, green, blue, 255);
    }

    public static int fromARGB(int red, int green, int blue, int alpha) {
        return (alpha & 0xFF) << 24 | (red & 0xFF) << 16 | (green & 0xFF) << 8 | blue & 0xFF;
    }

    public static int withAlpha(int color, float alphaScale) {
        Color colorObj = new Color(color);
        return ColorUtil.withAlphaColor(colorObj, alphaScale).getRGB();
    }

    public static Color withAlphaColor(Color color, float alphaScale) {
        alphaScale = Math.min(1.0f, Math.max(0.0f, alphaScale));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), (int)((float)color.getAlpha() * alphaScale));
    }

    public static int getAlpha(int color) {
        return color >> 24 & 0xFF;
    }

    public static int getRed(int color) {
        return color >> 16 & 0xFF;
    }

    public static int getGreen(int color) {
        return color >> 8 & 0xFF;
    }

    public static int getBlue(int color) {
        return color & 0xFF;
    }

    @Generated
    private ColorUtil() {
        throw new UnsupportedOperationException(UTILITY_CLASS_MSG);
    }
}
