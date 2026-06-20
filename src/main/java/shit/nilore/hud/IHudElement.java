package shit.nilore.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import shit.nilore.render.DrawContext;

public interface IHudElement {
    enum Alignment {
        TOP, BOTTOM, LEFT, RIGHT, CENTER
    }

    record Size(float width, float height) {
    }

    default Alignment getHudSize() {
        return Alignment.TOP;
    }

    default boolean hasBackground() {
        return false;
    }

    void renderGui(GuiGraphics graphics, PoseStack stack, float x, float y, float w, float h, float partial);

    default boolean isVisible() {
        return true;
    }

    default Size getHudAlignment() {
        return new Size(240.0f, 25.0f);
    }

    default int colorWithAlpha(int color, float alpha) {
        float clamped = Math.max(0.0f, Math.min(1.0f, alpha));
        return color & 0xFFFFFF | (int) (255.0f * clamped) << 24;
    }

    default void render(DrawContext drawContext, float x, float y, float width, float height, float alpha) {
    }
}
