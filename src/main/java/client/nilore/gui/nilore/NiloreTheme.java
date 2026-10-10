package client.nilore.gui.nilore;

/**
 * Colour tokens of the Nilore click GUI.
 *
 * <p>Both palettes are the literal custom properties of the reference stylesheet, including the
 * translucency, so the frosted panes composite the same way over the blurred backdrop.
 */
public final class NiloreTheme {
    public static final class Palette {
        public final int window;
        public final int sidebar;
        public final int list;
        public final int detail;
        public final int card;
        public final int popover;
        public final int segmentActive;
        public final int field;
        public final int rowHover;
        public final int rowSelected;
        public final int navHover;
        public final int switchOff;
        public final int separator;
        public final int text;
        public final int secondary;
        public final int tertiary;
        public final int accent;
        public final int accentSoft;
        public final int blue;
        public final int green;
        public final int red;
        public final int shadow;
        public final int hairline;

        private Palette(int window, int sidebar, int list, int detail, int card, int popover, int segmentActive,
                        int field, int rowHover, int rowSelected, int navHover, int switchOff, int separator,
                        int text, int secondary, int tertiary, int accent, int accentSoft, int blue, int green,
                        int red, int shadow, int hairline) {
            this.window = window;
            this.sidebar = sidebar;
            this.list = list;
            this.detail = detail;
            this.card = card;
            this.popover = popover;
            this.segmentActive = segmentActive;
            this.field = field;
            this.rowHover = rowHover;
            this.rowSelected = rowSelected;
            this.navHover = navHover;
            this.switchOff = switchOff;
            this.separator = separator;
            this.text = text;
            this.secondary = secondary;
            this.tertiary = tertiary;
            this.accent = accent;
            this.accentSoft = accentSoft;
            this.blue = blue;
            this.green = green;
            this.red = red;
            this.shadow = shadow;
            this.hairline = hairline;
        }
    }

    public static final Palette LIGHT = new Palette(
            0xB8FFFFFF, 0x9EE8E8ED, 0x57FFFFFF, 0xBDF9F9FB, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
            0x1F767680, 0x12787880, 0x33FFD60A, 0x1A787880, 0x52787880, 0x213C3C43,
            0xFF111114, 0xFF6E6E73, 0xFFAEAEB2, 0xFFFFD60A, 0x38FFD60A, 0xFF0A84FF, 0xFF34C759, 0xFFFF3B30,
            0x380F172A, 0x59FFFFFF);

    public static final Palette DARK = new Palette(
            0xB81E1E22, 0xC718181A, 0x731E1E22, 0xC71C1C1E, 0xFF1C1C1E, 0xFF2C2C2E, 0xFF636366,
            0x3D767680, 0x0BFFFFFF, 0x1AFFFFFF, 0x1A787880, 0x52787880, 0x8F545458,
            0xFFF2F2F7, 0xFF98989F, 0xFF636366, 0xFFFFD60A, 0x2EFFD60A, 0xFF0A84FF, 0xFF30D158, 0xFFFF453A,
            0x94000000, 0x59FFFFFF);

    private NiloreTheme() {
    }

    public static Palette of(boolean dark) {
        return dark ? DARK : LIGHT;
    }
}
