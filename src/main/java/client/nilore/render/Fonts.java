package client.nilore.render;

import java.awt.Font;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import client.nilore.utils.misc.Assets;

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
        return getCustomFont(name, size, false);
    }

    /**
     * 西文字体画不出来的字（主要是中文）统一落到它上面。
     *
     * <p>这样就能让英语/数字用 Apple 味的西文字体、中文仍然走苹方——
     * 字形光栅化时逐字符挑字体，不需要调用方自己分组。
     */
    private static final String CJK_FALLBACK = "pingfang_sc_regular.ttf";

    /**
     * @param bold 是否合成粗体。中文字体基本都没有 Bold 变体，只能靠字形重复绘制撑粗。
     *             粗体和常规是两份独立缓存，不会互相污染。
     */
    public static synchronized CustomFont getCustomFont(String name, float size, boolean bold) {
        String key = name + "-" + size + (bold ? "-bold" : "");
        CustomFont customFont = customFontCache.get(key);
        if (customFont != null) {
            return customFont;
        }
        try {
            Font font = awtFontCache.computeIfAbsent(name, Fonts::loadAwtFont);
            if (font == null) return null;
            // 主字体自己就是中文字体时不用兜底，省一份字形图集
            Font[] fallbacks = new Font[0];
            if (!CJK_FALLBACK.equals(name)) {
                Font cjk = awtFontCache.computeIfAbsent(CJK_FALLBACK, Fonts::loadAwtFont);
                if (cjk != null) {
                    fallbacks = new Font[]{cjk};
                }
            }
            Font derived = font.deriveFont(0, size / 2.0f);
            CustomFont cf = new CustomFont(derived, size / 2.0f, bold, fallbacks);
            customFontCache.put(key, cf);
            return cf;
        } catch (Exception exception) {
            exception.printStackTrace();
            return null;
        }
    }

    private static Font loadAwtFont(String name) {
        try (InputStream stream = Assets.open("/assets/nilore/fonts/" + name)) {
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
