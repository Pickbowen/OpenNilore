package shit.lizz.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import shit.lizz.ClientBase;
import shit.lizz.render.DrawContext;
import shit.lizz.render.FontPresets;
import shit.lizz.render.FontRenderer;
import shit.lizz.render.GlHelper;
import shit.lizz.render.Paint;
import shit.lizz.utils.render.ColorUtil;

public class TabListHud
extends ClientBase
implements IHudElement {
    private static final FontRenderer nameFont = FontPresets.pingfang(12.0f);
    private static final FontRenderer headerFont = FontPresets.poppinsBold(20.0f);
    private static final FontRenderer titleFont = FontPresets.pingfang(16.0f);

    @Override
    public boolean hasBackground() {
        return true;
    }

    @Override
    public void renderGui(GuiGraphics guiGraphics, PoseStack poseStack, float x, float y, float width, float height, float alpha) {
        float rowWidth;
        float maxRowWidth;
        if (mc == null || mc.player == null || mc.getConnection() == null || alpha <= 0.01f) {
            return;
        }
        ArrayList<PlayerInfo> players = new ArrayList<>(mc.getConnection().getOnlinePlayers());
        players.sort(Comparator.comparing(p -> p.getProfile().getName()));
        Component header = TabListInfo.header;
        float headerHeight = header != null && !header.getString().isEmpty() ? (float)header.getString().split("\n").length * 11.0f : 20.0f;
        int rowsPerColumn = 20;
        int columnCount = Math.max(1, (int)Math.ceil((double)players.size() / (double)rowsPerColumn));
        float centerOffset = 0.0f;
        if (columnCount == 1) {
            float available;
            maxRowWidth = 0.0f;
            if (!players.isEmpty()) {
                for (PlayerInfo player : players) {
                    String name = player.getProfile().getName();
                    String ping = player.getLatency() + "ms";
                    rowWidth = 12.0f + nameFont.getWidth(name) + 5.0f + nameFont.getWidth(ping);
                    maxRowWidth = Math.max(maxRowWidth, rowWidth);
                }
            }
            if (maxRowWidth < (available = width - 20.0f)) {
                centerOffset = (available - maxRowWidth) / 2.0f;
            }
        }
        maxRowWidth = y + 10.0f + headerHeight + 10.0f;
        for (int i = 0; i < players.size(); ++i) {
            int column = i / rowsPerColumn;
            int row = i % rowsPerColumn;
            float entryX = x + 10.0f + (float)column * 150.0f;
            if (columnCount == 1) {
                entryX += centerOffset;
            }
            rowWidth = maxRowWidth + (float)row * 11.0f;
            PlayerInfo player = players.get(i);
            poseStack.pushPose();
            poseStack.translate(entryX, rowWidth, 0.0f);
            guiGraphics.blit(player.getSkinLocation(), 0, 0, 8, 8, 8.0f, 8.0f, 8, 8, 64, 64);
            guiGraphics.blit(player.getSkinLocation(), 0, 0, 8, 8, 40.0f, 8.0f, 8, 8, 64, 64);
            poseStack.popPose();
        }
    }

    @Override
    public void render(DrawContext drawContext, float x, float y, float width, float height, float alpha) {
        if (mc == null || mc.player == null || mc.getConnection() == null || alpha <= 0.01f) {
            return;
        }
        ArrayList<PlayerInfo> players = new ArrayList<>(mc.getConnection().getOnlinePlayers());
        players.sort(Comparator.comparing(p -> p.getProfile().getName()));
        Component header = TabListInfo.header;
        Component footer = TabListInfo.footer;
        try (Paint paint = new Paint()){
            int i;
            float rowWidth;
            float maxRowWidth;
            float headerHeight;
            paint.setColor(this.colorWithAlpha(Color.WHITE.getRGB(), alpha));
            if (header != null && !header.getString().isEmpty()) {
                headerHeight = (float)header.getString().split("\n").length * 11.0f;
                float lineY = y + 10.0f;
                for (String line : header.getString().split("\n")) {
                    maxRowWidth = x + width / 2.0f;
                    this.drawFormattedComponent(drawContext, Component.literal(line), maxRowWidth, lineY + titleFont.getMetrics().ascent() / 2.0f + 9.0f, titleFont, paint, alpha);
                    lineY += 11.0f;
                }
            } else {
                headerHeight = 20.0f;
                String title = "Player List (" + players.size() + ")";
                float titleX = x + (width - headerFont.getWidth(title)) / 2.0f;
                GlHelper.drawTextShadowLegacy(title, titleX, y + 10.0f + headerFont.getMetrics().ascent() + 15.0f, headerFont, -1);
            }
            if (players.isEmpty() && (footer == null || footer.getString().isEmpty())) {
                return;
            }
            int playerCount = players.size();
            int rowsPerColumn = 20;
            int columnCount = Math.max(1, (int)Math.ceil((double)playerCount / (double)rowsPerColumn));
            float rowsStartY = y + 10.0f + headerHeight + 10.0f;
            float centerOffset = 0.0f;
            maxRowWidth = 0.0f;
            if (columnCount == 1) {
                float available;
                if (!players.isEmpty()) {
                    for (PlayerInfo player : players) {
                        String name = player.getProfile().getName();
                        String ping = player.getLatency() + "ms";
                        rowWidth = 12.0f + nameFont.getWidth(name) + 5.0f + nameFont.getWidth(ping);
                        maxRowWidth = Math.max(maxRowWidth, rowWidth);
                    }
                }
                if (maxRowWidth < (available = width - 20.0f)) {
                    centerOffset = (available - maxRowWidth) / 2.0f;
                }
            }
            for (i = 0; i < players.size(); ++i) {
                int column = i / rowsPerColumn;
                int row = i % rowsPerColumn;
                float entryX = x + 10.0f + (float)column * 150.0f;
                if (columnCount == 1) {
                    entryX += centerOffset;
                }
                rowWidth = rowsStartY + (float)row * 11.0f;
                PlayerInfo player = players.get(i);
                paint.setColor(this.colorWithAlpha(Color.WHITE.getRGB(), alpha));
                String displayName = player.getProfile().getName();
                String ping = player.getLatency() + "ms";
                float pingWidth = nameFont.getWidth(ping);
                float nameMaxWidth = columnCount > 1 ? 128.0f - pingWidth - 5.0f : maxRowWidth - 8.0f - 4.0f - pingWidth - 5.0f;
                if (nameFont.getWidth(displayName) > nameMaxWidth && nameMaxWidth > 0.0f) {
                    while (nameFont.getWidth(displayName) > nameMaxWidth && !displayName.isEmpty()) {
                        displayName = displayName.substring(0, displayName.length() - 1);
                    }
                }
                drawContext.drawString(displayName, entryX + 8.0f + 4.0f + 0.5f, rowWidth + nameFont.getMetrics().ascent() / 2.0f + 11.0f + 0.5f, nameFont, paint.setColor(ColorUtil.fromARGB(0, 0, 0, (int)(alpha * 0.65f * 255.0f))));
                drawContext.drawString(displayName, entryX + 8.0f + 4.0f, rowWidth + nameFont.getMetrics().ascent() / 2.0f + 11.0f, nameFont, paint.setColor(-1));
                paint.setColor(this.colorWithAlpha(Color.GRAY.getRGB(), alpha));
                float pingX = columnCount > 1 ? entryX + 140.0f - pingWidth : entryX + maxRowWidth - pingWidth;
                drawContext.drawString(ping, pingX, rowWidth + nameFont.getMetrics().ascent() / 2.0f + 11.0f, nameFont, paint);
            }
            if (footer != null && !footer.getString().isEmpty()) {
                i = players.isEmpty() ? 0 : Math.min(players.size(), rowsPerColumn);
                float footerY = rowsStartY + (float)i * 11.0f + 10.0f;
                for (String line : footer.getString().split("\n")) {
                    float centerX = x + width / 2.0f;
                    this.drawFormattedComponent(drawContext, Component.literal(line), centerX, footerY, titleFont, paint, alpha);
                    footerY += 11.0f;
                }
            }
        }
    }

    private void drawFormattedComponent(DrawContext drawContext, Component component, float centerX, float startY, FontRenderer fontRenderer, Paint paint, float alpha) {
        float lineY = startY;
        for (String line : component.getString().split("\n")) {
            float drawX = centerX;
            ArrayList<Component> segments = new ArrayList<>();
            String[] parts = ("§r" + line).split("§");
            Style style = Style.EMPTY;
            for (int i = 0; i < parts.length; ++i) {
                if (parts[i].isEmpty()) continue;
                String segment = parts[i];
                if (i > 0) {
                    char c = segment.charAt(0);
                    ChatFormatting fmt = ChatFormatting.getByCode(c);
                    if (fmt != null) {
                        style = style.applyLegacyFormat(fmt);
                    }
                    segment = segment.substring(1);
                }
                if (segment.isEmpty()) continue;
                segments.add(Component.literal(segment).withStyle(style));
            }
            float totalWidth = 0.0f;
            for (Component seg : segments) {
                totalWidth += fontRenderer.getWidth(seg.getString());
            }
            drawX = centerX - totalWidth / 2.0f;
            for (Component seg : segments) {
                String text = seg.getString();
                Style segStyle = seg.getStyle();
                TextColor textColor = segStyle.getColor();
                int color = textColor != null ? textColor.getValue() : Color.WHITE.getRGB();
                paint.setColor(this.colorWithAlpha(color, alpha));
                drawContext.drawString(text, drawX, lineY, fontRenderer, paint);
                drawX += fontRenderer.getWidth(text);
            }
            lineY += 11.0f;
        }
    }

    private float getComponentWidth(Component component, FontRenderer fontRenderer) {
        float maxWidth = 0.0f;
        for (String line : component.getString().split("\n")) {
            String stripped = ChatFormatting.stripFormatting(line);
            if (stripped == null) continue;
            maxWidth = Math.max(maxWidth, fontRenderer.getWidth(stripped));
        }
        return maxWidth;
    }

    @Override
    public boolean isVisible() {
        return mc != null && mc.options.keyPlayerList.isDown();
    }

    @Override
    public IHudElement.Size getHudAlignment() {
        float screenHeight;
        float rowWidth;
        float maxRowWidth;
        float listWidth;
        int visibleRows;
        if (mc == null || mc.getConnection() == null) {
            return new IHudElement.Size(200.0f, 30.0f);
        }
        int playerCount = mc.getConnection().getOnlinePlayers().size();
        Component header = TabListInfo.header;
        Component footer = TabListInfo.footer;
        float headerHeight = 0.0f;
        headerHeight = header != null && !header.getString().isEmpty() ? (float)header.getString().split("\n").length * 11.0f : 20.0f;
        float footerHeight = 0.0f;
        if (footer != null && !footer.getString().isEmpty()) {
            footerHeight = (float)footer.getString().split("\n").length * 11.0f + 10.0f;
        }
        int rowsPerColumn = 20;
        int columnCount = Math.max(1, (int)Math.ceil((double)playerCount / (double)rowsPerColumn));
        int rowsShown = visibleRows = playerCount > 0 ? Math.min(playerCount, rowsPerColumn) : 0;
        if (columnCount > 1) {
            listWidth = (float)columnCount * 140.0f + (float)(columnCount - 1) * 10.0f;
        } else {
            maxRowWidth = 0.0f;
            if (mc.getConnection() != null) {
                for (PlayerInfo player : mc.getConnection().getOnlinePlayers()) {
                    String name = player.getProfile().getName();
                    String ping = player.getLatency() + "ms";
                    rowWidth = 12.0f + nameFont.getWidth(name) + 5.0f + nameFont.getWidth(ping);
                    maxRowWidth = Math.max(maxRowWidth, rowWidth);
                }
            }
            listWidth = maxRowWidth;
        }
        maxRowWidth = 0.0f;
        if (header != null) {
            maxRowWidth = this.getComponentWidth(header, titleFont);
        }
        float footerWidth = 0.0f;
        if (footer != null) {
            footerWidth = this.getComponentWidth(footer, titleFont);
        }
        float totalWidth = Math.max(listWidth, Math.max(maxRowWidth, footerWidth)) + 20.0f;
        float totalHeight = headerHeight + (float)visibleRows * 11.0f + footerHeight + 20.0f;
        if (playerCount == 0 && footer == null) {
            totalHeight += 10.0f;
        }
        if (playerCount > 0 && header != null) {
            totalHeight += 10.0f;
        }
        float screenWidth = mc.getWindow().getGuiScaledWidth();
        screenHeight = mc.getWindow().getGuiScaledHeight();
        totalWidth = Math.min(totalWidth, screenWidth * 0.9f);
        totalHeight = Math.min(totalHeight, screenHeight * 0.9f);
        return new IHudElement.Size(totalWidth, totalHeight);
    }
}
