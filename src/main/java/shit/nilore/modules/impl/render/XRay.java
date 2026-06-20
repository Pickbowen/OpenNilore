package shit.nilore.modules.impl.render;

import shit.nilore.modules.Category;
import shit.nilore.modules.Module;

public class XRay extends Module {
    public static XRay INSTANCE;

    public XRay() {
        super("XRay", Category.RENDER);
        INSTANCE = this;
    }
}
