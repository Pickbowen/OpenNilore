package shit.lizz.hud;

import lombok.Getter;
import lombok.Setter;
import shit.lizz.event.impl.GlRenderEvent;
import shit.lizz.event.impl.Render2DEvent;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.utils.render.RenderUtil;

public abstract class HudElement
extends Module {
    @Getter @Setter
    protected float x;
    @Getter @Setter
    protected float y;
    @Getter @Setter
    protected float width;
    @Getter @Setter
    protected float height;
    @Getter @Setter
    private boolean dragging = false;
    @Getter @Setter
    private float dragOffsetX;
    @Getter @Setter
    private float dragOffsetY;

    public HudElement(String string) {
        super(string, Category.RENDER);
    }

    public abstract void onRender2D(Render2DEvent var1, float var2, float var3);

    public abstract void onGlRender(GlRenderEvent var1, float var2, float var3);

    public abstract void onSettings();

    public boolean mousePressed(int mouseX, int mouseY, int button) {
        if (this.isHovered(mouseX, mouseY) && button == 0) {
            this.dragging = true;
            this.dragOffsetX = (float)mouseX - this.getX();
            this.dragOffsetY = (float)mouseY - this.getY();
            return true;
        }
        return false;
    }

    public void mouseDragged(int mouseX, int mouseY) {
        this.x = (float)mouseX - this.dragOffsetX;
        this.y = (float)mouseY - this.dragOffsetY;
    }

    public boolean isHovered(int mouseX, int mouseY) {
        return RenderUtil.isHovered(this.x, this.y, this.width, this.height, mouseX, mouseY);
    }

    public void stopDragging() {
        this.dragging = false;
    }
}