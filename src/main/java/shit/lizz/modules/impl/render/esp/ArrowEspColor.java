package shit.lizz.modules.impl.render.esp;

import java.awt.Color;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.entity.projectile.Arrow;
import shit.lizz.modules.impl.render.esp.ClassEspColor;

public class ArrowEspColor
extends ClassEspColor {
    public ArrowEspColor() {
        super(new HashSet<>(Collections.singletonList(Arrow.class)), new Color(255, 0, 0));
    }

    @Override
    public float getFillAlpha() {
        return 0.25f;
    }

    @Override
    public float getOutlineAlpha() {
        return 0.5f;
    }

    @Override
    public float getLineWidth() {
        return 0.05f;
    }
}