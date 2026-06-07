package shit.lizz.render;

import java.awt.Font;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import shit.lizz.utils.misc.Assets;

public final class Fonts {
    private static final Map<String, FontRenderer> fontRendererCache = new HashMap<>();
    private static final Map<String, CustomFont> customFontCache = new HashMap<>();
    private static final Map<String, Font> awtFontCache = new HashMap<>();

    public static FontRenderer getRenderer(String name, float size, FontFormat format) {
        String key = name + "-" + size;
        return fontRendererCache.computeIfAbsent(key, k -> new FontRenderer(name, size));
    }

    public static FontRenderer getRenderer(String name, float size) {
        return getRenderer(name, size, detectFormat(name));
    }

    public static synchronized CustomFont getCustomFont(String name, float size) {
        String key = name + "-" + size;
        CustomFont customFont = customFontCache.get(key);
        if (customFont != null) {
            return customFont;
        }
        try {
            Font font = awtFontCache.computeIfAbsent(name, Fonts::loadAwtFont);
            if (font == null) return null;
            Font derived = font.deriveFont(0, size / 2.0f);
            CustomFont cf = new CustomFont(derived, size / 2.0f);
            customFontCache.put(key, cf);
            return cf;
        } catch (Exception exception) {
            exception.printStackTrace();
            return null;
        }
    }

    private static Font loadAwtFont(String name) {
        try (InputStream stream = Assets.open("/assets/lizz/fonts/" + name)) {
            if (stream == null) {
                return null;
            }
            return Font.createFont(Font.TRUETYPE_FONT, stream);
        } catch (Exception exception) {
            exception.printStackTrace();
            return null;
        }
    }

    private static FontFormat detectFormat(String name) {
        int dotIndex = name.lastIndexOf(46);
        if (dotIndex < 0) {
            return FontFormat.UNKNOWN;
        }
        return FontFormat.fromExtension(name.substring(dotIndex + 1));
    }
}
