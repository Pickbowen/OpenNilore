package shit.nilore.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import shit.nilore.ClientBase;
import shit.nilore.modules.impl.movement.Scaffold;
import shit.nilore.render.DrawContext;
import shit.nilore.render.FontPresets;
import shit.nilore.render.FontRenderer;
import shit.nilore.render.Paint;
import shit.nilore.render.RoundedRectangle;
import shit.nilore.utils.animation.SpringAnimation;
import shit.nilore.utils.game.MovementUtil;

public class ScaffoldHud
extends ClientBase
implements IHudElement {
    private static final FontRenderer blockCountFont = FontPresets.poppinsBold(14.0f);
    private static final FontRenderer speedFont = FontPresets.poppinsMedium(10.0f);
    private final SpringAnimation progressAnim = new SpringAnimation(250.0f, 1.0f, 22.0f, 0.0f);
    private long lastUpdateTime = 0L;

    @Override
    public boolean hasBackground() {
        return true;
    }

    @Override
    public IHudElement.Alignment getHudSize() {
        return IHudElement.Alignment.CENTER;
    }

    @Override
    public void renderGui(GuiGraphics guiGraphics, PoseStack poseStack, float x, float y, float width, float height, float alpha) {
        if (mc == null || mc.player == null || alpha <= 0.01f) {
            return;
        }
        ItemStack blockItem = this.getBlockItem();
        if (blockItem.isEmpty()) {
            return;
        }
        float iconSize = height - 16.0f;
        float iconX = x + 8.0f;
        float iconY = y + 8.0f;
        if (alpha > 0.1f && iconSize - 4.0f > 0.0f) {
            guiGraphics.renderItem(blockItem, (int)iconX + 2, (int)iconY + 2, 0, (int)iconSize - 4);
        }
    }

    @Override
    public void render(DrawContext drawContext, float x, float y, float width, float height, float alpha) {
        if (mc == null || mc.player == null || alpha <= 0.01f) {
            return;
        }
        ItemStack blockItem = this.getBlockItem();
        if (blockItem.isEmpty()) {
            return;
        }
        float iconSize = height - 16.0f;
        int blockCount = blockItem.getCount();
        String countText = blockCount + " blocks";
        double speedBps = MovementUtil.getSpeedBps();
        String speedText = String.format("%.2fb/s", new Object[]{speedBps});
        float countWidth = blockCountFont.getWidth(countText);
        float speedWidth = speedFont.getWidth(speedText);
        float maxTextWidth = Math.max(countWidth, speedWidth);
        float barWidth = width - iconSize - maxTextWidth - 32.0f;
        float barHeight = 6.0f;
        float barX = x + 8.0f + iconSize + 8.0f;
        float barY = y + height / 2.0f - barHeight / 2.0f;
        float progressPct = Math.min(1.0f, (float)blockCount / 64.0f);
        this.setX(progressPct);
        try (Paint paint = new Paint()){
            paint.setColor(this.colorWithAlpha(new Color(30, 30, 30).getRGB(), alpha));
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(barX, barY, barWidth, barHeight, barHeight / 2.0f), paint);
            if (this.progressAnim.getValue() > 0.0f) {
                paint.setColor(this.colorWithAlpha(new Color(153, 0, 255).getRGB(), alpha));
                drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(barX, barY, barWidth * this.progressAnim.getValue(), barHeight, barHeight / 2.0f), paint);
            }
        }
        float textX = barX + barWidth + 8.0f;
        float centerY = y + height / 2.0f;
        float countX = textX + (maxTextWidth - countWidth) / 2.0f;
        float speedX = textX + (maxTextWidth - speedWidth) / 2.0f;
        try (Paint paint = new Paint()){
            paint.setColor(this.colorWithAlpha(Color.WHITE.getRGB(), alpha));
            drawContext.drawString(countText, countX, centerY - blockCountFont.getMetrics().capHeight() / 2.0f + 2.0f, blockCountFont, paint);
            paint.setColor(this.colorWithAlpha(Color.GRAY.getRGB(), alpha));
            drawContext.drawString(speedText, speedX, centerY + speedFont.getMetrics().capHeight() / 2.0f + 8.0f, speedFont, paint);
        }
    }

    private ItemStack getBlockItem() {
        if (mc.player == null) {
            return ItemStack.EMPTY;
        }
        ItemStack mainHand = mc.player.getMainHandItem();
        if (mainHand.getItem() instanceof BlockItem) {
            return mainHand;
        }
        for (int i = 0; i < 9; ++i) {
            ItemStack slotItem = mc.player.getInventory().getItem(i);
            if (!(slotItem.getItem() instanceof BlockItem)) continue;
            return slotItem;
        }
        return ItemStack.EMPTY;
    }

    private void setX(float progressPct) {
        long now = System.currentTimeMillis();
        if (this.lastUpdateTime == 0L || now - this.lastUpdateTime > 1000L) {
            this.lastUpdateTime = now;
            this.progressAnim.setValue(progressPct);
            this.progressAnim.setTargetValue(progressPct);
            return;
        }
        float deltaSec = (float)(now - this.lastUpdateTime) / 1000.0f;
        if (deltaSec <= 0.0f) {
            return;
        }
        this.lastUpdateTime = now;
        this.progressAnim.setTargetValue(progressPct);
        this.progressAnim.update(deltaSec);
    }

    @Override
    public boolean isVisible() {
        return Scaffold.INSTANCE != null && Scaffold.INSTANCE.isEnabled();
    }

    @Override
    public IHudElement.Size getHudAlignment() {
        return new IHudElement.Size(260.0f, 30.0f);
    }
}