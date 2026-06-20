package shit.nilore.modules.impl.render;

import shit.nilore.event.impl.PacketEvent;
import shit.nilore.event.impl.Render2DEvent;
import shit.nilore.event.impl.RenderEvent;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.modules.impl.render.nametag.NameTagStyle;
import shit.nilore.settings.impl.BooleanSetting;
import shit.nilore.settings.impl.ModeSetting;
import shit.nilore.settings.impl.NumberSetting;
import shit.nilore.event.EventTarget;

public class NameTags
extends Module {
    public static NameTags INSTANCE;
    public final ModeSetting styleSetting = new ModeSetting("Style", "Opal", "Simple").withDefault("Opal");
    public final NumberSetting scaleSetting = new NumberSetting("Scale", 0.3, 0.1, 1.0, 0.01);
    public final NumberSetting distanceSetting = new NumberSetting("Max Distance", 64.0, 8.0, 256.0, 1.0);
    public final BooleanSetting showHealthSetting = new BooleanSetting("Invisibles", false);
    public final BooleanSetting showArmorSetting = new BooleanSetting("Show Artifacts", true);
    public final BooleanSetting showPingSetting = new BooleanSetting("Hide Teammates", false);

    public NameTags() {
        super("NameTags", Category.RENDER);
        INSTANCE = this;
        NameTagStyle.registerStyles();
    }

    @Override
    public void onEnable() {
        NameTagStyle nameTagStyle = NameTagStyle.getByName(this.styleSetting.getValue());
        if (nameTagStyle != null) {
            nameTagStyle.onEnable();
        }
    }

    @Override
    public void onDisable() {
        NameTagStyle nameTagStyle = NameTagStyle.getByName(this.styleSetting.getValue());
        if (nameTagStyle != null) {
            nameTagStyle.onDisable();
        }
    }

    @EventTarget
    public void onRender(RenderEvent renderEvent) {
        NameTagStyle nameTagStyle = NameTagStyle.getByName(this.styleSetting.getValue());
        if (nameTagStyle != null) {
            nameTagStyle.onRender(renderEvent);
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent render2DEvent) {
        NameTagStyle nameTagStyle = NameTagStyle.getByName(this.styleSetting.getValue());
        if (nameTagStyle != null) {
            nameTagStyle.onRender2D(render2DEvent);
        }
    }

    @EventTarget
    public void onPacket(PacketEvent packetEvent) {
        NameTagStyle nameTagStyle = NameTagStyle.getByName(this.styleSetting.getValue());
        if (nameTagStyle != null) {
            nameTagStyle.onPacket(packetEvent);
        }
    }
}