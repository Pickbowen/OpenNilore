package shit.lizz.modules.impl.render.esp;

import java.awt.Color;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.entity.projectile.ThrownPotion;
import shit.lizz.modules.impl.render.esp.ClassEspColor;

public class PotionEspColor
extends ClassEspColor {
    public PotionEspColor() {
        super(new HashSet<>(Collections.singleton(ThrownPotion.class)), new Color(255, 66, 249));
    }

    @Override
    public float getLineWidth() {
        return 0.05f;
    }
}