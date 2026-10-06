package client.nilore.hud.target;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import client.nilore.event.impl.Render2DEvent;
import client.nilore.render.GlHelper;
import client.nilore.render.Paint;
import client.nilore.render.Renderer;
import client.nilore.render.RoundedRectangle;
import client.nilore.utils.animation.SmoothAnimationTimer;

/**
 * Exhibition style.
 * Dark solid background + 1px grey border + left avatar + vanilla-font name /
 * HP / distance + segmented health bar. The left side uses a rounded player
 * head instead of the full player model (1.20.1 GUI-layer model rendering is
 * unreliable).
 */
public class ExhibitionTargetStyle
        extends TargetStyle {
    private static final float WIDTH = 120.0f;
    private static final float HEIGHT = 45.0f;
    private static final float AVATAR_SIZE = 38.0f;
    private static final float AVATAR_X = 2.5f;
    private static final int COLOR_BG = 0xFF0A0A0A;
    private static final int COLOR_BORDER = 0xFF282828;
    private static final int COLOR_BAR_BG = 0xFF282828;
    private static final int COLOR_BAR_DIVIDER = 0xFF0A0A0A;
    private static final float BAR_SEGMENT = 4.0f;
    private static final float BAR_DIVIDER = 1.0f;

    public ExhibitionTargetStyle() {
        super("Exhibition");
    }

    @Override
    public void render(Render2DEvent event, LivingEntity target, SmoothAnimationTimer healthAnim, SmoothAnimationTimer healthLagAnim, float healthPct, float x, float y) {
        if (target == null) {
            return;
        }
        GuiGraphics gui = event.guiGraphics();

        float infoX = x + AVATAR_X + AVATAR_SIZE + 2.0f;
        float infoWidth = WIDTH - (infoX - x) - 5.0f;
        float nameY = y + 5.0f;
        float infoY = nameY + 9.0f + 2.0f;
        float barY = infoY + 8.0f;
        float barHeight = 5.0f;

        // 背景 / 边框 / 头像 / 血条(都在渲染块内,GlHelper 需要 DrawContext)
        Renderer.renderConsumer(dc -> {
            // 背景
            dc.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, WIDTH, HEIGHT, 0.0f), new Paint().setColor(COLOR_BG));
            // 1f 内边框
            dc.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, WIDTH, 1.0f, 0.0f), new Paint().setColor(COLOR_BORDER));
            dc.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y + HEIGHT - 1.0f, WIDTH, 1.0f, 0.0f), new Paint().setColor(COLOR_BORDER));
            dc.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, 1.0f, HEIGHT, 0.0f), new Paint().setColor(COLOR_BORDER));
            dc.drawRoundedRect(RoundedRectangle.ofXYWHR(x + WIDTH - 1.0f, y, 1.0f, HEIGHT, 0.0f), new Paint().setColor(COLOR_BORDER));

            // 左侧头像(替代完整玩家模型)
            if (target instanceof AbstractClientPlayer player) {
                GlHelper.drawPlayerHeadRounded(player, x + AVATAR_X, y + (HEIGHT - AVATAR_SIZE) / 2.0f, AVATAR_SIZE, AVATAR_SIZE, 1.0f, 0.0f);
            }

            // 血条背景
            dc.drawRoundedRect(RoundedRectangle.ofXYWHR(infoX, barY, infoWidth, barHeight, 0.0f), new Paint().setColor(COLOR_BAR_BG));
            // 血条前景 - 按血量分段取色
            float ratio = Mth.clamp(healthAnim.getValueF(), 0.0f, 1.0f);
            int barColor = ratio > 2.0f / 3.0f ? 0xFF00B400 : (ratio > 1.0f / 3.0f ? 0xFFFFFF00 : 0xFFFF0000);
            float fill = infoWidth * ratio;
            if (fill > 0.0f) {
                dc.drawRoundedRect(RoundedRectangle.ofXYWHR(infoX, barY, fill, barHeight, 0.0f), new Paint().setColor(barColor));
            }
            // 分割线 - 每 4f 一个 1f 竖条
            float cursor = infoX + BAR_SEGMENT;
            while (cursor < infoX + infoWidth) {
                dc.drawRoundedRect(RoundedRectangle.ofXYWHR(cursor, barY, BAR_DIVIDER, barHeight, 0.0f), new Paint().setColor(COLOR_BAR_DIVIDER));
                cursor += BAR_SEGMENT + BAR_DIVIDER;
            }
        });

        // 名字 - 原版字体
        gui.drawString(mc.font, target.getName().getString(), (int) infoX, (int) nameY, 0xFFFFFFFF);

        // HP / Dist - 原版字体 9f, 缩放 0.7
        String infoText = "HP: " + String.format("%.1f", target.getHealth()) + " | Dist: " + String.format("%.1f", this.getDistance(target));
        PoseStack pose = gui.pose();
        pose.pushPose();
        pose.translate(infoX, infoY, 0.0f);
        pose.scale(0.8f, 0.8f, 1.0f);
        gui.drawString(mc.font, infoText, 0, 0, 0xFFFFFFFF);
        pose.popPose();
    }

    private double getDistance(LivingEntity target) {
        return mc.player != null ? mc.player.distanceTo(target) : 0.0;
    }
}
