package shit.nilore.gui.newclickgui;

import com.mojang.blaze3d.vertex.PoseStack;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.gui.GuiGraphics;
import shit.nilore.gui.NewClickGui;
import shit.nilore.utils.animation.SmoothAnimationTimer;

public abstract class UIElement {
    @Getter @Setter
    protected float x;
    @Getter @Setter
    protected float y;
    @Getter @Setter
    protected float width;
    @Getter @Setter
    protected float height;
    @Getter
    protected final SmoothAnimationTimer animTimer = new SmoothAnimationTimer();

    public abstract void render(NewClickGui clickGui, GuiGraphics guiGraphics, PoseStack poseStack, int mouseX, int mouseY, float alpha, float partialTicks);

    public void reset() {
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
        return false;
    }

    public float getAnimatedHeight() {
        return this.getHeight();
    }

    }
