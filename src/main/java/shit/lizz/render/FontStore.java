package shit.lizz.render;

import java.awt.Font;
import java.io.InputStream;
import org.jetbrains.annotations.NotNull;
import shit.lizz.utils.misc.Assets;

public class FontStore {
    public static CustomFont OPENSANS_16 = loadFont(16.0f, "opensans.ttf");
    public static CustomFont OPENSANS_18 = loadFont(18.0f, "opensans.ttf");
    public static CustomFont PINGFANG_16 = loadFont(16.0f, "pingfang_sc_regular.ttf");
    public static CustomFont PINGFANG_18 = loadFont(18.0f, "pingfang_sc_regular.ttf");
    public static CustomFont ICON_18 = loadFont(18.0f, "icon.ttf");
    public static CustomFont ICON_30 = loadFont(30.0f, "icon.ttf");
    public static CustomFont MATERIAL_20 = loadFontWithFallback(20.0f, "material.ttf");
    public static CustomFont MATERIAL_14 = loadFontWithFallback(14.0f, "material.ttf");
    public static CustomFont LIZZICON_28 = loadFont(28.0f, "lizzicon-Regular.ttf");
    public static CustomFont AXIFORMA_REGULAR_14 = loadFont(14.0f, "axiforma_regular.ttf");
    public static CustomFont AXIFORMA_REGULAR_16 = loadFont(16.0f, "axiforma_regular.ttf");
    public static CustomFont AXIFORMA_REGULAR_18 = loadFont(18.0f, "axiforma_regular.ttf");
    public static CustomFont AXIFORMA_BOLD_13 = loadFont(13.0f, "axiforma_bold.ttf");
    public static CustomFont AXIFORMA_BOLD_18 = loadFont(18.0f, "axiforma_bold.ttf");
    public static CustomFont AXIFORMA_EXTRABOLD_16 = loadFont(16.0f, "axiforma_extrabold.ttf");
    public static CustomFont AXIFORMA_EXTRABOLD_18 = loadFont(18.0f, "axiforma_extrabold.ttf");
    public static CustomFont AXIFORMA_BOLD_16 = loadFont(16.0f, "axiforma_bold.ttf");

    @NotNull
    public static CustomFont loadFont(float size, String name) {
        try (InputStream stream = Assets.open("/assets/lizz/fonts/" + name)) {
            if (stream == null) {
                throw new java.io.IOException("Font not found: " + name);
            }
            return new CustomFont(Font.createFont(Font.TRUETYPE_FONT, stream).deriveFont(0, size / 2.0f), size / 2.0f);
        } catch (Exception e) {
            return new CustomFont(new Font("SansSerif", 0, (int)(size / 2)), size / 2.0f);
        }
    }

    @NotNull
    private static CustomFont loadFontWithFallback(float size, String name) {
        try {
            return loadFont(size, name);
        } catch (Exception e) {
            System.err.println("Failed to load " + name + ", using fallback font.");
            return new CustomFont(new Font("SansSerif", 0, (int)(size / 2)), size / 2.0f);
        }
    }
}
