package shit.nilore.render.shader;

import io.netty.util.collection.IntObjectHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public class ShaderFormats {
    public static final Supplier<Map<Integer, String>> POSITION_UV_COLOR = () -> {
        IntObjectHashMap<String> intObjectHashMap = new IntObjectHashMap<>();
        intObjectHashMap.put(0, "Position");
        intObjectHashMap.put(1, "UV0");
        intObjectHashMap.put(2, "Color");
        return intObjectHashMap;
    };
    public static final Supplier<Map<Integer, String>> POSITION_UV = () -> {
        IntObjectHashMap<String> intObjectHashMap = new IntObjectHashMap<>();
        intObjectHashMap.put(0, "Position");
        intObjectHashMap.put(1, "UV0");
        return intObjectHashMap;
    };
    public static final Pattern IMPORT_PATTERN = Pattern.compile("(#(?:/\\*(?:[^*]|\\*+[^*/])*\\*+/|\\h)*import(?:/\\*(?:[^*]|\\*+[^*/])*\\*+/|\\h)*(?:\"(.*)\"|<(.*)>))");

    private static /* synthetic */ Map<Integer, String> lambda$POSITION_COLOR$2() {
        IntObjectHashMap<String> intObjectHashMap = new IntObjectHashMap<>();
        intObjectHashMap.put(0, "Position");
        intObjectHashMap.put(1, "Color");
        return intObjectHashMap;
    }
}