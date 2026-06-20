package shit.nilore.modules.impl.render;

import shit.nilore.modules.Category;
import shit.nilore.modules.Module;

public class ItemTags extends Module {
    public static ItemTags INSTANCE;

    public ItemTags() {
        super("ItemTags", Category.RENDER);
        INSTANCE = this;
    }
}
