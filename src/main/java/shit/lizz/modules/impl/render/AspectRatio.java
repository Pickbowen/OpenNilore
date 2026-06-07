package shit.lizz.modules.impl.render;

import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.settings.impl.NumberSetting;

public class AspectRatio
extends Module {
    public static AspectRatio INSTANCE;
    public final NumberSetting ratioSetting = new NumberSetting("Ratio", 1.78f, 0.1f, 5.0f, 0.1f);

    public AspectRatio() {
        super("AspectRatio", Category.RENDER);
        INSTANCE = this;
    }
}