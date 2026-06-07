package shit.lizz.modules.impl.movement;

import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.settings.impl.BooleanSetting;

public class NoDelay
extends Module {
    public static NoDelay INSTANCE;
    public final BooleanSetting fastDig = new BooleanSetting("No Jump Delay", true);

    public NoDelay() {
        super("NoDelay", Category.MOVEMENT);
        INSTANCE = this;
    }
}