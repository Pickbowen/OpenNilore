package shit.lizz.render;

public enum FontFormat {
    TTF,
    OTF,
    UNKNOWN;

    public static FontFormat fromExtension(String string) {
        if (string == null) {
            return UNKNOWN;
        }
        switch (string.toLowerCase()) {
            case "ttf":
                return TTF;
            case "otf":
                return OTF;
            default:
                return UNKNOWN;
        }
    }
}
