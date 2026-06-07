package shit.lizz.utils.misc;

import lombok.Generated;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.apache.commons.lang3.StringUtils;

public final class ComponentUtil {
    private static final String NATIVE_KEY = "This is a utility class and cannot be instantiated";

    public static MutableComponent replaceText(Component component, String string, String string2) {
        if (component == null) {
            return Component.empty();
        }
        String string3 = component.getString();
        if (!string3.contains(string)) {
            return component.copy();
        }
        String string4 = StringUtils.replace(string3, string, string2);
        return Component.literal(string4).withStyle(component.getStyle());
    }

    @Generated
    private ComponentUtil() {
        throw new UnsupportedOperationException(NATIVE_KEY);
    }
}