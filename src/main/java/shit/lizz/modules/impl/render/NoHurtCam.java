package shit.lizz.modules.impl.render;

import shit.lizz.modules.Category;
import shit.lizz.modules.Module;

public class NoHurtCam
extends Module {
    public static NoHurtCam INSTANCE;
    public NoHurtCam() {
        super("NoHurtCam", Category.RENDER);
        INSTANCE = this;
    }
}