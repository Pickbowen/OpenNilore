package shit.lizz.modules.impl.render.esp;

import java.awt.Color;
import net.minecraft.world.entity.Entity;

public interface EspColorProvider {
    Color getColor(Object var1);

    default float getFillAlpha() {
        return 0.125f;
    }

    boolean shouldHighlight(Entity var1);

    default float getOutlineAlpha() {
        return 0.25f;
    }

    default float getLineWidth() {
        return 0.03f;
    }
}