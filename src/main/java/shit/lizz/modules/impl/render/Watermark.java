package shit.lizz.modules.impl.render;

import shit.lizz.event.impl.GlRenderEvent;
import shit.lizz.event.impl.Render2DEvent;
import shit.lizz.hud.DynamicIsland;
import shit.lizz.hud.NeverloseWatermark;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.settings.impl.ModeSetting;
import shit.lizz.event.EventTarget;

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
