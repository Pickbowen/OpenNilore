package client.nilore.gui.material3;

import java.util.HashMap;
import java.util.Map;
import client.nilore.modules.Category;
import client.nilore.render.FontPresets;
import client.nilore.render.FontRenderer;
import client.nilore.render.GlHelper;

public final class MD3Theme {

    // ── Surface (dark purple, lighter) ──
    public static final int SCRIM             = 0xCC12101C;
    public static final int SURFACE           = 0xFF1E1A2E;
    public static final int SURFACE_DIM       = 0xFF221E34;
    public static final int SIDEBAR           = 0xFF262040;
    public static final int SURFACE_CONTAINER = 0xFF2C2648;
    public static final int SURFACE_HIGH      = 0xFF362F56;
    public static final int SURFACE_HIGHEST   = 0xFF423A66;

    // ── Primary (light purple / violet) ──
    public static final int PRIMARY           = 0xFFD0BCFF;
    public static final int PRIMARY_CONTAINER = 0xFF4A3880;
    public static final int ON_PRIMARY        = 0xFF381E72;

    // ── Secondary (muted purple) ──
    public static final int SECONDARY         = 0xFFCCC2DC;
    public static final int SECONDARY_CONTAINER = 0xFF4A4458;

    // ── Text ──
    public static final int TEXT_HIGH         = 0xFFF4EFFA;
    public static final int TEXT_MED          = 0xFFCAC4D6;
    public static final int TEXT_LOW          = 0xFF938F9E;
    public static final int TEXT_DISABLED     = 0xFF5C586A;

    // ── Outline ──
    public static final int OUTLINE           = 0xFF494558;
    public static final int OUTLINE_VARIANT   = 0xFF3B384C;

    // ── Category [primary, container] ──
    private static final Map<Category, int[]> CAT = new HashMap<>();
    static {
        CAT.put(Category.COMBAT,   new int[]{0xFFE8B4FF, 0xFF3A1858});
        CAT.put(Category.MOVEMENT, new int[]{0xFFD0BCFF, 0xFF302060});
        CAT.put(Category.PLAYER,   new int[]{0xFFCFBCFF, 0xFF2E1A58});
        CAT.put(Category.RENDER,   new int[]{0xFFE0C8FF, 0xFF342068});
        CAT.put(Category.EXPLOIT,  new int[]{0xFFC8AAFF, 0xFF2A1450});
        CAT.put(Category.WORLD,    new int[]{0xFFD4C0FF, 0xFF321C5A});
        CAT.put(Category.MISC,     new int[]{0xFFC0A8E8, 0xFF281848});
    }

    // Material Icons
    private static final Map<Category, String> ICONS = new HashMap<>();
    static {
        ICONS.put(Category.COMBAT,   "");
        ICONS.put(Category.MOVEMENT, "");
        ICONS.put(Category.PLAYER,   "");
        ICONS.put(Category.RENDER,   "");
        ICONS.put(Category.EXPLOIT,  "");
        ICONS.put(Category.WORLD,    "");
        ICONS.put(Category.MISC,     "");
    }

    // Material icon codepoints for special glyphs
    public static final String ICON_CLOSE   = "";
    public static final String ICON_PERSON  = "";
    public static final String ICON_MODULE  = "";

    private static final Map<Category, String> LABELS = new HashMap<>();
    static {
        LABELS.put(Category.COMBAT,   "Combat");
        LABELS.put(Category.MOVEMENT, "Movement");
        LABELS.put(Category.PLAYER,   "Player");
        LABELS.put(Category.RENDER,   "Render");
        LABELS.put(Category.EXPLOIT,  "Exploit");
        LABELS.put(Category.WORLD,    "World");
        LABELS.put(Category.MISC,     "Ghost");
    }

    private MD3Theme() {}

    // ── Fonts ──
    public static FontRenderer fontDisplay(float s)     { return FontPresets.axiformaExtraBold(24 * s); }
    public static FontRenderer fontHeadline(float s)    { return FontPresets.axiformaBold(18 * s); }
    public static FontRenderer fontTitle(float s)       { return FontPresets.axiformaBold(15 * s); }
    public static FontRenderer fontTitleMedium(float s) { return FontPresets.axiformaBold(13 * s); }
    public static FontRenderer fontBody(float s)        { return FontPresets.axiformaRegular(13 * s); }
    public static FontRenderer fontBodyLarge(float s)   { return FontPresets.axiformaRegular(14 * s); }
    public static FontRenderer fontLabel(float s)       { return FontPresets.axiformaRegular(11 * s); }
    public static FontRenderer fontLabelLarge(float s)  { return FontPresets.axiformaRegular(12 * s); }
    public static FontRenderer fontMaterial(float sz)   { return FontPresets.materialIcons(sz); }

    // ── Category ──
    public static String icon(Category c)    { return ICONS.getOrDefault(c, ""); }
    public static String label(Category c)   { return LABELS.getOrDefault(c, c.displayName); }
    public static int primary(Category c)    { int[] v = CAT.get(c); return v != null ? v[0] : PRIMARY; }
    public static int container(Category c)  { int[] v = CAT.get(c); return v != null ? v[1] : PRIMARY_CONTAINER; }

    // ── Color helpers ──
    public static int withAlpha(int c, float a) {
        return ((int)(((c >> 24) & 0xFF) * a) << 24) | (c & 0x00FFFFFF);
    }

    public static int argb(int a, int r, int g, int b) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    public static int lerpColor(int from, int to, float t) {
        float inv = 1f - t;
        int a = (int)(((from >> 24) & 0xFF) * inv + ((to >> 24) & 0xFF) * t);
        int r = (int)(((from >> 16) & 0xFF) * inv + ((to >> 16) & 0xFF) * t);
        int g = (int)(((from >> 8) & 0xFF) * inv + ((to >> 8) & 0xFF) * t);
        int b = (int)((from & 0xFF) * inv + (to & 0xFF) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int brighten(int c, float f) {
        int a = (c >> 24) & 0xFF;
        int r = Math.min(255, (int)(((c >> 16) & 0xFF) * f));
        int g = Math.min(255, (int)(((c >> 8) & 0xFF) * f));
        int b = Math.min(255, (int)((c & 0xFF) * f));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static void text(String t, float x, float y, FontRenderer f, int c, float a) {
        GlHelper.drawText(t, x, y, f, withAlpha(c, a));
    }
}
