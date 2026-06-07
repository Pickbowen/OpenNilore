package shit.lizz.modules.impl.render;

import shit.lizz.modules.Category;
import shit.lizz.modules.Module;

public class XRay extends Module {
    public static XRay INSTANCE;

    public XRay() {
        super("XRay", Category.RENDER);
        INSTANCE = this;
    }
}
