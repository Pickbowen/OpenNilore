package shit.nilore.modules.impl.render;

import shit.nilore.event.impl.GlRenderEvent;
import shit.nilore.event.impl.Render2DEvent;
import shit.nilore.hud.DynamicIsland;
import shit.nilore.hud.NeverloseWatermark;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.settings.impl.ModeSetting;
import shit.nilore.event.EventTarget;

public class Watermark extends Module {
    final ModeSetting styleSetting = new ModeSetting("Style", "Neverlose", "DynamicIsland").withDefault("DynamicIsland");
    private final DynamicIsland dynamicIsland = new DynamicIsland();
    private final NeverloseWatermark neverloseWatermark = new NeverloseWatermark();

    public Watermark() {
        super("Watermark", Category.RENDER);
    }

    @EventTarget
    public void onRender2D(Render2DEvent render2DEvent) {
        if (!this.isEnabled()) {
            return;
        }
        switch (this.styleSetting.getValue()) {
            case "Neverlose":
                this.neverloseWatermark.onRender2D(render2DEvent);
                break;
            case "DynamicIsland":
                this.dynamicIsland.onRender2D(render2DEvent);
                break;
        }
    }

    @EventTarget
    public void onGlRender(GlRenderEvent glRenderEvent) {
        if (!this.isEnabled()) {
            return;
        }
        if ("Neverlose".equals(this.styleSetting.getValue())) {
            this.neverloseWatermark.onGlRender(glRenderEvent);
        }
    }
}
