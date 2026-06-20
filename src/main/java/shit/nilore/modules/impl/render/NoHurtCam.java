package shit.nilore.modules.impl.render;

import shit.nilore.modules.Category;
import shit.nilore.modules.Module;

public class NoHurtCam
extends Module {
    public static NoHurtCam INSTANCE;
    public NoHurtCam() {
        super("NoHurtCam", Category.RENDER);
        INSTANCE = this;
    }
}