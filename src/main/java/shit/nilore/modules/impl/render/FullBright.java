package shit.nilore.modules.impl.render;

import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.settings.impl.NumberSetting;
import shit.nilore.utils.misc.Triple;
import shit.nilore.utils.misc.TripleProvider;

public class FullBright
extends Module
implements TripleProvider {
    public static FullBright INSTANCE;
    public final NumberSetting brightnessSetting = new NumberSetting("Brightness", 100.0f, 0.0f, 100.0f, 1.0f);

    public FullBright() {
        super("FullBright", Category.RENDER);
        INSTANCE = this;
    }

    @Override
    public Triple getTriple() {
        if (this.isEnabled()) {
            return new Triple(this.getName(), String.valueOf(this.brightnessSetting.getValue().intValue()), true);
        }
        return null;
    }
}