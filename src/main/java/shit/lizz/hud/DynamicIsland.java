package shit.lizz.hud;

import java.awt.Color;
import java.util.Arrays;
import java.util.List;

import net.minecraft.util.Mth;
import shit.lizz.ClientBase;
import shit.lizz.event.impl.Render2DEvent;
import shit.lizz.render.Paint;
import shit.lizz.render.Renderer;
import shit.lizz.render.RoundedRectangle;
import shit.lizz.utils.animation.SpringAnimation;
import shit.lizz.utils.render.RenderUtil;

public class DynamicIsland {
    public static final class ActiveElementSelector {
        private final DynamicIsland owner;

        public ActiveElementSelector(DynamicIsland owner) {
            this.owner = owner;
        }

        public IHudElement visible() {
            for (IHudElement element : this.owner.elements) {
                if (element.isVisible()) return element;
            }
            return null;
        }
    }

    final List<IHudElement> elements = Arrays.asList(new TabListHud(), new ScaffoldHud(), new EventAlertHud(), new AutoPlayHud(), new WatermarkHud());
    private final DynamicIsland.ActiveElementSelector activeElementSelector = new DynamicIsland.ActiveElementSelector(this);
    private final SpringAnimation widthAnim = new SpringAnimation(300.0f, 1.2f, 20.0f, 170.0f);
    private final SpringAnimation heightAnim = new SpringAnimation(300.0f, 1.2f, 20.0f, 18.0f);
    private final SpringAnimation transitionAnim = new SpringAnimation(250.0f, 1.0f, 22.0f, 1.0f);
    private IHudElement activeElement = null;
    private IHudElement outgoingElement = null;
    private final long lastFrameTime = 0L;
    private long lastFrameTimestamp = 0L;

    public void onRender2D(Render2DEvent render2DEvent) {
        float islandY;
        float outgoingY;
        IHudElement.Size size;
        if (ClientBase.mc.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (this.lastFrameTimestamp == 0L) {
            this.lastFrameTimestamp = now;
        }
        float deltaSec = (float)(now - this.lastFrameTimestamp) / 1000.0f;
        this.lastFrameTimestamp = now;
        deltaSec = Math.min(deltaSec, 0.033333335f);
        IHudElement visibleElement = this.activeElementSelector.visible();
        if (this.activeElement != visibleElement) {
            this.outgoingElement = this.activeElement;
            this.activeElement = visibleElement;
            this.transitionAnim.reset(0.0f);
            this.transitionAnim.setTargetValue(1.0f);
            if (this.outgoingElement == null) {
                size = this.activeElement.getHudAlignment();
                this.widthAnim.reset(size.width());
                this.heightAnim.reset(size.height());
                this.transitionAnim.reset(1.0f);
            }
        }
        size = this.activeElement.getHudAlignment();
        float targetWidth = size.width();
        float targetHeight = size.height();
        float progress = this.transitionAnim.getValue();
        if (this.outgoingElement != null && progress < 1.0f) {
            IHudElement.Size outgoingSize = this.outgoingElement.getHudAlignment();
            targetWidth = Mth.lerp(progress, outgoingSize.width(), size.width());
            targetHeight = Mth.lerp(progress, outgoingSize.height(), size.height());
        }
        this.widthAnim.setTargetValue(targetWidth);
        this.heightAnim.setTargetValue(targetHeight);
        this.widthAnim.update(deltaSec);
        this.heightAnim.update(deltaSec);
        this.transitionAnim.update(deltaSec);
        float islandWidth = Math.max(0.0f, this.widthAnim.getValue() + 30.0f);
        float islandHeight = Math.max(0.0f, this.heightAnim.getValue() + 3.0f);
        float islandX = ((float)ClientBase.mc.getWindow().getGuiScaledWidth() - islandWidth) / 2.0f;
        float anchorSize = 25.0f;
        float topMargin = 25.0f;
        float anchorCenterY = topMargin + anchorSize / 2.0f;
        float activeY = this.activeElement.getHudSize() == IHudElement.Alignment.CENTER ? anchorCenterY - islandHeight / 2.0f : topMargin;
        if (this.outgoingElement != null && progress < 1.0f) {
            outgoingY = this.outgoingElement.getHudSize() == IHudElement.Alignment.CENTER ? anchorCenterY - islandHeight / 2.0f : topMargin;
            islandY = Mth.lerp(progress, outgoingY, activeY);
        } else {
            islandY = activeY;
        }
        outgoingY = 12.0f;
        final float finalY = islandY;
        final float finalCornerRadius = outgoingY;
        if (this.activeElement.hasBackground()) {
            Renderer.renderConsumer((drawContext -> {
                try (Paint paint = new Paint()){
                    paint.setColor(new Color(0, 0, 0, 40).getRGB());
                    drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(islandX, finalY, islandWidth, islandHeight, finalCornerRadius), paint);
                }
                drawContext.save();
                drawContext.clipRoundedRect(RoundedRectangle.ofXYWHR(islandX, finalY, islandWidth, islandHeight, finalCornerRadius), true);
                this.activeElement.render(drawContext, islandX, finalY, islandWidth, islandHeight, progress);
                drawContext.restore();
            }));
        }
        RenderUtil.pushScissor((int)islandX, (int)islandY, (int)islandWidth, (int)islandHeight);
        this.activeElement.renderGui(render2DEvent.guiGraphics(), render2DEvent.poseStack(), islandX, islandY, islandWidth, islandHeight, progress);
        RenderUtil.popScissor();
        if (progress >= 1.0f) {
            this.outgoingElement = null;
        }
    }
}