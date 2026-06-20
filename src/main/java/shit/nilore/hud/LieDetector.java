package shit.nilore.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.entity.LivingEntity;
import shit.nilore.event.impl.GlRenderEvent;
import shit.nilore.event.impl.Render2DEvent;
import shit.nilore.modules.impl.combat.KillAura;
import shit.nilore.settings.impl.BooleanSetting;
import shit.nilore.utils.animation.SmoothAnimationTimer;
import shit.nilore.utils.animation.Timer;
import shit.nilore.utils.math.Easings;
import shit.nilore.utils.misc.SoundUtil;
import shit.nilore.utils.render.RenderHelper;
import shit.nilore.utils.render.RenderUtil;
import shit.nilore.utils.render.TextureUtil;

public class LieDetector
extends HudElement {
    private final SmoothAnimationTimer needleAnim = new SmoothAnimationTimer();
    private DynamicTexture panelTexture;
    private DynamicTexture pointerTexture;
    float panelDisplayWidth = 124.5f;
    float panelDisplayHeight = 68.0f;
    float panelWidth = 50.0f;
    float pointerHeight = 16.5f;
    private final BooleanSetting soundSetting = new BooleanSetting("PlaySound", false);
    final Timer soundTimer = new Timer();

    public LieDetector() {
        super("LieDetector");
        this.setWidth(this.panelDisplayWidth);
        this.setHeight(this.panelDisplayHeight);
    }

    @Override
    public void onRender2D(Render2DEvent render2DEvent, float x, float y) {
    }

    @Override
    public void onGlRender(GlRenderEvent glRenderEvent, float x, float y) {
        this.loadTextures();
        this.needleAnim.animate(KillAura.aimingTarget == null ? 0.0 : (double)((LivingEntity)KillAura.aimingTarget).hurtTime, 0.32, Easings.BACK_OUT);
        this.needleAnim.tick();
        PoseStack poseStack = glRenderEvent.poseStack();
        RenderHelper.resetShaderColor();
        RenderUtil.drawTexture(this.panelTexture.getId(), poseStack, x, y, this.panelDisplayWidth, this.panelDisplayHeight, 1.0f, -1);
        RenderUtil.drawTexture(this.panelTexture.getId(), poseStack, x, y, this.panelDisplayWidth, this.panelDisplayHeight, 1.0f, -1);
        float pointerX = x + (this.panelDisplayWidth - this.panelWidth / 2.0f) / 2.0f + 2.0f;
        float pointerY = y + this.panelDisplayHeight - this.pointerHeight;
        RenderHelper.pushRotateAround(poseStack, pointerX + 9.0f, pointerY + 8.5f, this.needleAnim.getValueF() * -18.0f);
        RenderUtil.drawTexture(this.pointerTexture.getId(), poseStack, pointerX, pointerY, this.panelWidth, this.pointerHeight, 1.0f, -1);
        RenderUtil.drawTexture(this.pointerTexture.getId(), poseStack, pointerX, pointerY, this.panelWidth, this.pointerHeight, 1.0f, -1);
        RenderHelper.popPose(poseStack);
        this.setWidth(this.panelDisplayWidth);
        this.setHeight(this.panelDisplayHeight);
        if (this.soundTimer.hasPassed(2000L) && this.soundSetting.getValue()) {
            if (this.needleAnim.getValueF() * -18.0f < -90.0f) {
                SoundUtil.playSound("truth.wav", 0.1f);
            } else {
                SoundUtil.playSound("lie.wav", 0.1f);
            }
            this.soundTimer.reset();
        }
    }

    private void loadTextures() {
        if (this.panelTexture != null && this.pointerTexture != null) {
            return;
        }
        this.panelTexture = TextureUtil.loadTexture("panel.png");
        this.pointerTexture = TextureUtil.loadTexture("ptr.png");
    }

    @Override
    public void onSettings() {
    }
}