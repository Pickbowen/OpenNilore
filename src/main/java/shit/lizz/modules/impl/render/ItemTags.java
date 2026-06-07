package shit.lizz.modules.impl.render;

import shit.lizz.modules.Category;
import shit.lizz.modules.Module;

public class ItemTags extends Module {
    public static ItemTags INSTANCE;

    public ItemTags() {
        super("ItemTags", Category.RENDER);
        INSTANCE = this;
    }
}
