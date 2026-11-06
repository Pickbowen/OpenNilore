package client.nilore.render;

import java.awt.Font;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import client.nilore.ClientBase;
import client.nilore.utils.misc.Assets;

public final class Fonts {
    private static final Map<String, FontRenderer> fontRendererCache = new HashMap<>();
    private static final Map<String, CustomFont> customFontCache = new HashMap<>();
    /** Concurrent: the preload thread fills it while the render thread reads it. */
    private static final Map<String, Font> awtFontCache = new ConcurrentHashMap<>();
    private static final Set<String> awtFontMissing = ConcurrentHashMap.newKeySet();
    private static final Object AWT_LOCK = new Object();

    private static final int MAX_RASTER_VARIANTS = 24;
    private static final Map<String, CustomFont> scaledFontCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CustomFont> eldest) {
            if (this.size() <= MAX_RASTER_VARIANTS) {
                return false;
            }
            eldest.getValue().close();
            return true;
        }
    };

    public static FontRenderer getRenderer(String name, float size, FontFormat format) {
        return getRenderer(name, size, format, 0f, true);
    }

    public static FontRenderer getRenderer(String name, float size) {
        return getRenderer(name, size, detectFormat(name));
    }

    public static FontRenderer getRenderer(String name, float size, float rasterScale, boolean hinted) {
        return getRenderer(name, size, detectFormat(name), rasterScale, hinted);
    }

    public static FontRenderer getRenderer(String name, float size, FontFormat format,
                                           float rasterScale, boolean hinted) {
        String key = name + "-" + size;
        if (rasterScale > 0f || !hinted) {
            key = key + "@" + rasterScale + (hinted ? "" : "-raw");
        }
        String cacheKey = key;
        return fontRendererCache.computeIfAbsent(cacheKey,
                k -> new FontRenderer(name, size, false, rasterScale, hinted));
    }

    public static float deviceScale(float poseScale) {
        return Math.max(0.25f, (float)ClientBase.mc.getWindow().getGuiScale() * poseScale);
    }

    public static float supersampledScale() {
        return Math.max(2f, (float)ClientBase.mc.getWindow().getGuiScale() * 2f);
    }

    public static synchronized CustomFont getCustomFont(String name, float size) {
        return getCustomFont(name, size, false, 0f, true);
    }

    private static final String CJK_FALLBACK = "pingfang_sc_regular.ttf";

    public static synchronized CustomFont getCustomFont(String name, float size, boolean bold) {
        return getCustomFont(name, size, bold, 0f, true);
    }

    public static synchronized CustomFont getCustomFont(String name, float size, boolean bold,
                                                        float rasterScale, boolean hinted) {
        boolean explicit = rasterScale > 0f || !hinted;
        String key = name + "-" + size + (bold ? "-bold" : "")
                + (explicit ? "@" + rasterScale + (hinted ? "" : "-raw") : "");
        Map<String, CustomFont> cache = explicit ? scaledFontCache : customFontCache;
        CustomFont customFont = cache.get(key);
        if (customFont != null) {
            return customFont;
        }
        try {
            Font font = awtFont(name);
            if (font == null) return null;
            Font[] fallbacks = new Font[0];
            if (!CJK_FALLBACK.equals(name)) {
                Font cjk = awtFont(CJK_FALLBACK);
                if (cjk != null) {
                    fallbacks = new Font[]{cjk};
                }
            }
            Font derived = font.deriveFont(0, size / 2.0f);
            CustomFont cf = new CustomFont(derived, size / 2.0f, bold, fallbacks, rasterScale, hinted);
            cache.put(key, cf);
            return cf;
        } catch (Exception exception) {
            exception.printStackTrace();
            return null;
        }
    }

    private static Font awtFont(String name) {
        Font cached = awtFontCache.get(name);
        if (cached != null) {
            return cached;
        }
        if (awtFontMissing.contains(name)) {
            return null;
        }
        synchronized (AWT_LOCK) {
            cached = awtFontCache.get(name);
            if (cached != null) {
                return cached;
            }
            Font loaded = loadAwtFont(name);
            if (loaded == null) {
                awtFontMissing.add(name);
            } else {
                awtFontCache.put(name, loaded);
            }
            return loaded;
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
