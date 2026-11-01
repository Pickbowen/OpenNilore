package client.nilore.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import client.nilore.ClientBase;
import client.nilore.event.impl.GlRenderEvent;
import client.nilore.event.impl.ModuleToggleEvent;
import client.nilore.modules.Module;
import client.nilore.modules.impl.movement.Scaffold;
import client.nilore.network.PingTracker;
import client.nilore.render.DrawContext;
import client.nilore.render.FontPresets;
import client.nilore.render.FontRenderer;
import client.nilore.render.Paint;
import client.nilore.render.RoundedRectangle;
import client.nilore.utils.game.ItemUtil;
import client.nilore.utils.game.MovementUtil;
import client.nilore.utils.render.RenderUtil;

public class OpaiWatermark
extends ClientBase {
    private static final float FONT_SIZE = 22.5f;
    private static final float DETAIL_SIZE = 16.0f;
    private static final float ICON_SIZE = 25.0f;
    private static final float BRAND_SIZE = 24.0f;
    private static final float ROW_ICON_SIZE = 40.0f;
    private static final float INSET = 14.0f;
    private static final float RIGHT_INSET = 14.0f;
    private static final float ICON_GAP = 4.0f;
    private static final float SEPARATOR_LEAD = 1.0f;
    private static final float SEPARATOR_TRAIL = 1.0f;
    private static final float HEIGHT = 40.0f;
    private static final float TOP = 14.0f;
    private static final float ROW_RADIUS = 10.0f;
    private static final float TOGGLE_PADDING = 8.0f;
    private static final float SCAFFOLD_PADDING = 12.0f;
    private static final float ROW_GAP = 6.0f;
    private static final float TILE = 40.0f;
    private static final float TILE_RADIUS = 12.0f;
    private static final float ROW_HEIGHT = TILE + TOGGLE_PADDING * 2.0f;
    private static final float PROGRESS_HEIGHT = 10.0f;
    private static final float PROGRESS_GAP = 11.0f;
    private static final float SCAFFOLD_HEIGHT = TILE + PROGRESS_GAP + PROGRESS_HEIGHT + SCAFFOLD_PADDING * 2.0f;
    private static final float ROW_TEXT_X = SCAFFOLD_PADDING + TILE + 12.0f;
    private static final float ROW_TEXT_RIGHT = 15.0f;
    private static final float SCAFFOLD_TEXT_RIGHT = 8.0f;
    private static final float TOGGLE_LINE_GAP = 8.0f;
    private static final float SCAFFOLD_LINE_GAP = 8.0f;
    private static final float SLOT_SIZE = 20.0f;
    private static final float CHEST_PADDING = 12.0f;
    private static final float BLUR_RADIUS = 26.0f;
    private static final float BLUR_OPACITY = 0.9f;
    private static final float BASELINE_FIX = 1.0f;
    private static final float ICON_LIFT = 5.5f;
    private static final float MATERIAL_GLYPH_WIDTH = 0.668f;
    private static final float MATERIAL_GLYPH_INSET = 0.167f;
    private static final float MATERIAL_GLYPH_SHIFT = 0.2f;
    private static final float REFRESH_LIFT = 1.0f;
    private static final float LINK_SHIFT = 2.0f;
    private static final float USER_SHIFT = 1.0f;
    private static final float ROW_ICON_RIGHT = 10.0f;
    private static final float ROW_ICON_LIFT = 10.0f;
    private static final float BRAND_GLYPH_WIDTH = 0.512f;
    private static final float BRAND_GLYPH_INSET = 0.232f;
    private static final float BRAND_CENTER = 0.3f;
    private static final float BRAND_LIFT = 1.0f;
    private static final int MAX_ROWS = 3;
    private static final long TOGGLE_MS = 800L;
    private static final long PROGRESS_MS = 500L;
    private static final long LOOT_MS = 380L;
    private static final String BRAND = "Opai";
    private static final String SEPARATOR = " \u00b7 ";
    private static final String BRAND_GLYPH = "N";
    private static final int BODY_COLOR = 0x98000000;
    private static final int DEFAULT_FOREGROUND = 0xFFE4E1E6;
    private static final int DEFAULT_ACCENT = 0xFFBBC3FF;
    private static final int DEFAULT_DETAIL = 0xFFAAAAAA;
    private static final int ONLINE_COLOR = 0xFF80C810;
    private static final int PING_ORANGE_COLOR = 0xFFC87D28;
    private static final int PING_RED_COLOR = 0xFFCA3E2E;
    private static final FontRenderer TEXT_FONT = FontPresets.googleSansBold(FONT_SIZE);
    private static final FontRenderer DETAIL_FONT = FontPresets.googleSans(DETAIL_SIZE);
    private static final FontRenderer ICON_FONT = FontPresets.materialIcons(ICON_SIZE);
    private static final FontRenderer ROW_ICON_FONT = FontPresets.materialIcons(ROW_ICON_SIZE);
    private static final FontRenderer BRAND_FONT = FontPresets.niloreIcon(BRAND_SIZE);
    private int accentColor = DEFAULT_ACCENT;
    private final List<Notice> notices = new ArrayList<>(MAX_ROWS);
    private final Map<Integer, Long> looted = new HashMap<>();
    private final Morph width = new Morph();
    private final Morph height = new Morph();
    private final Morph idleFactor = new Morph();
    private int[] lastCounts;

    public void onModuleToggle(ModuleToggleEvent moduleToggleEvent) {
        Module module = moduleToggleEvent.module();
        if (module == null) {
            return;
        }
        String label = module.getName().replaceAll("(?<=[a-z])(?=[A-Z])", " ");
        this.post("module:" + module.getName(), "Module Toggled", label + " has been ",
                moduleToggleEvent.enabled() ? "Enabled" : "Disabled", Icon.TOGGLE, moduleToggleEvent.enabled(),
                TOGGLE_MS, 0.0f, System.nanoTime() / 1000000L);
    }

    public void onGlRender(GlRenderEvent glRenderEvent, int accent) {
        if (mc.player == null || mc.options.renderDebug) {
            return;
        }
        this.accentColor = accent;
        long now = System.nanoTime() / 1000000L;
        this.syncScaffold(now);
        this.notices.removeIf(notice -> now >= notice.expiresAt);
        float guiWidth = mc.getWindow().getGuiScaledWidth();
        float available = Math.max(1.0f, guiWidth - 32.0f);
        Layout idle = this.layout(this.view(), available);
        List<Row> rows = this.rows(now);
        Chest chest = this.chest(now);
        boolean resting = rows.isEmpty() && chest == null;
        float targetWidth = chest != null ? chest.width() : resting ? idle.width() : this.rowsWidth(rows, available);
        float targetHeight = chest != null ? chest.height() : resting ? HEIGHT : this.rowsHeight(rows);
        float animatedWidth = Math.min(available, Math.max(1.0f, this.width.value(targetWidth, now)));
        float animatedHeight = Math.max(HEIGHT, this.height.value(targetHeight, now));
        float rest = this.idleFactor.value(resting ? 1.0f : 0.0f, now);
        float radius = Mth.lerp(rest, ROW_RADIUS, Math.min(animatedHeight / 2.0f, HEIGHT / 2.0f));
        float x = (guiWidth - animatedWidth) / 2.0f;
        float y = TOP;
        DrawContext drawContext = glRenderEvent.drawContext();
        this.drawShell(drawContext, x, y, animatedWidth, animatedHeight, radius);
        drawContext.save();
        drawContext.clipRoundedRect(RoundedRectangle.ofXYWHR(x + 2.0f, y + 1.0f, Math.max(0.0f, animatedWidth - 4.0f), animatedHeight - 2.0f, radius), true);
        if (chest != null && animatedHeight >= chest.height() - 1.0f) {
            this.drawChest(drawContext, glRenderEvent.guiGraphics(), chest, x, y, now);
        } else if (chest == null) {
            try (Paint paint = new Paint()) {
                if (resting || rest > 0.5f) {
                    this.drawIdle(drawContext, paint, idle, x, y, animatedHeight);
                } else {
                    this.drawRows(drawContext, paint, rows, x, y, animatedWidth);
                }
            }
        }
        drawContext.restore();
    }

    private void drawShell(DrawContext drawContext, float x, float y, float width, float height, float radius) {
        PoseStack poseStack = drawContext.getPoseStack();
        try (Paint paint = new Paint()) {
            paint.setColor(0x14000000);
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(x - 3.0f, y - 2.0f, width + 6.0f, height + 4.0f, radius + 3.0f), paint);
            paint.setColor(0x1C000000);
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(x - 1.5f, y - 1.0f, width + 3.0f, height + 2.0f, radius + 1.5f), paint);
        }
        RenderUtil.drawBlurredRect(poseStack, x, y, width, height, radius, BLUR_RADIUS, BLUR_OPACITY, 0);
        try (Paint paint = new Paint()) {
            paint.setColor(BODY_COLOR);
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, width, height, radius), paint);
        }
    }

    private void drawIdle(DrawContext drawContext, Paint paint, Layout layout, float x, float y, float height) {
        float centerY = y + height / 2.0f;
        float textCap = TEXT_FONT.getMetrics().capHeight();
        for (Part part : layout.parts()) {
            float partX = x + part.x();
            switch (part.icon()) {
                case BRAND -> this.drawGlyph(drawContext, paint, BRAND_FONT, BRAND_GLYPH, partX, centerY + BRAND_SIZE * BRAND_CENTER - BRAND_LIFT, part.color());
                case USER, LINK -> this.drawGlyph(drawContext, paint, ICON_FONT, glyph(part.icon()), partX,
                        centerY + ICON_SIZE * 0.5f - ICON_LIFT - 1.0f, part.color());
                case REFRESH -> this.drawGlyph(drawContext, paint, ICON_FONT, glyph(part.icon()), partX,
                        centerY + ICON_SIZE * 0.5f - ICON_LIFT - REFRESH_LIFT, part.color());
                case NONE -> this.drawGlyph(drawContext, paint, TEXT_FONT, part.text(), partX, centerY + textCap * 0.5f, part.color());
            }
        }
    }

    private void drawRows(DrawContext drawContext, Paint paint, List<Row> rows, float x, float y, float width) {
        float rowY = y;
        for (Row row : rows) {
            this.drawRow(drawContext, paint, row, x, rowY, width);
            rowY += row.height() + ROW_GAP;
        }
    }

    private void drawRow(DrawContext drawContext, Paint paint, Row row, float x, float y, float width) {
        boolean progressRow = row.icon() == Icon.SCAFFOLD;
        float contentTop = y + row.padding();
        float textArea = TILE;
        float titleCap = TEXT_FONT.getMetrics().capHeight();
        float detailCap = DETAIL_FONT.getMetrics().capHeight();
        float blockHeight = titleCap + row.lineGap() + detailCap;
        float textTop = contentTop + (textArea - blockHeight) / 2.0f;
        float titleBaseline = textTop + titleCap;
        float detailBaseline = titleBaseline + row.lineGap() + detailCap;
        float tileX = x + SCAFFOLD_PADDING;
        float tileCenterY = contentTop + textArea / 2.0f;
        if (row.icon() == Icon.TOGGLE) {
            this.drawToggle(drawContext, paint, tileX + TILE / 2.0f, tileCenterY, row.toggle());
        } else {
            paint.setColor(0xC8101012);
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(tileX, tileCenterY - TILE / 2.0f, TILE, TILE, TILE_RADIUS), paint);
            this.drawGlyph(drawContext, paint, ROW_ICON_FONT, glyph(row.icon()),
                    tileX + (TILE - ROW_ICON_SIZE * MATERIAL_GLYPH_WIDTH) / 2.0f - ROW_ICON_SIZE * MATERIAL_GLYPH_INSET + ROW_ICON_RIGHT,
                    tileCenterY + ROW_ICON_SIZE * 0.5f - ROW_ICON_LIFT, DEFAULT_FOREGROUND);
        }
        float textX = x + ROW_TEXT_X;
        this.drawGlyph(drawContext, paint, TEXT_FONT, row.title(), textX, titleBaseline, DEFAULT_FOREGROUND);
        if (row.status().isEmpty()) {
            this.drawGlyph(drawContext, paint, DETAIL_FONT, row.detail(), textX, detailBaseline, DEFAULT_DETAIL);
        } else {
            this.drawGlyph(drawContext, paint, TEXT_FONT, row.detail(), textX, detailBaseline, DEFAULT_FOREGROUND);
            this.drawGlyph(drawContext, paint, TEXT_FONT, row.status(), textX + TEXT_FONT.getWidth(row.detail()), detailBaseline,
                    row.enabled() ? ONLINE_COLOR : PING_RED_COLOR);
        }
        if (progressRow) {
            float barX = x + SCAFFOLD_PADDING;
            float barWidth = Math.max(0.0f, width - SCAFFOLD_PADDING * 2.0f);
            float barY = contentTop + TILE + PROGRESS_GAP;
            paint.setColor(0xFF2C2C31);
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(barX, barY, barWidth, PROGRESS_HEIGHT, PROGRESS_HEIGHT / 2.0f), paint);
            float filled = barWidth * Mth.clamp(row.progress(), 0.0f, 1.0f);
            if (filled > 1.0f) {
                paint.setColor(this.accentColor);
                drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(barX, barY, filled, PROGRESS_HEIGHT, Math.min(PROGRESS_HEIGHT / 2.0f, filled / 2.0f)), paint);
            }
        }
    }

    private void drawToggle(DrawContext drawContext, Paint paint, float centerX, float centerY, float toggle) {
        float trackWidth = 32.0f;
        float trackHeight = 20.0f;
        float trackX = centerX - trackWidth / 2.0f;
        float trackY = centerY - trackHeight / 2.0f;
        paint.setColor(mix(0xFF3A3A42, this.accentColor, toggle));
        drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(trackX, trackY, trackWidth, trackHeight, trackHeight / 2.0f), paint);
        float knob = 15.0f;
        float knobX = trackX + 2.5f + (trackWidth - knob - 5.0f) * toggle;
        paint.setColor(mix(0xFF8A8A90, 0xFFFFFFFF, toggle));
        drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(knobX, trackY + (trackHeight - knob) / 2.0f, knob, knob, knob / 2.0f), paint);
    }

    private void drawChest(DrawContext drawContext, GuiGraphics guiGraphics, Chest chest, float x, float y, long now) {
        try (Paint paint = new Paint()) {
            for (int slot = 0; slot < chest.rows() * 9; slot++) {
                float slotX = x + CHEST_PADDING + slot % 9 * SLOT_SIZE;
                float slotY = y + CHEST_PADDING + slot / 9 * SLOT_SIZE;
                ItemStack stack = chest.menu().getSlot(slot).getItem();
                if (!stack.isEmpty()) {
                    guiGraphics.renderItem(stack, (int)slotX, (int)slotY);
                    guiGraphics.renderItemDecorations(mc.font, stack, (int)slotX, (int)slotY);
                }
                Long lootedAt = this.looted.get(slot);
                if (lootedAt == null) {
                    continue;
                }
                long age = now - lootedAt;
                if (age < 0L || age >= LOOT_MS) {
                    continue;
                }
                float growth = Mth.clamp(age / 120.0f, 0.0f, 1.0f);
                float size = SLOT_SIZE * growth;
                float corners = Mth.clamp((growth - 0.75f) / 0.25f, 0.0f, 1.0f);
                float lootRadius = size / 2.0f + (4.0f - size / 2.0f) * corners;
                float opacity = 0.34f * (float)Math.exp(-Math.max(0, age - 75) / 80.0);
                paint.setColor((Math.round(opacity * 255.0f) << 24) | 0xDCDCDC);
                drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(slotX + 8.0f - size / 2.0f, slotY + 8.0f - size / 2.0f,
                        size, size, lootRadius), paint);
            }
        }
        this.looted.entrySet().removeIf(entry -> now - entry.getValue() >= LOOT_MS);
    }

    private void drawGlyph(DrawContext drawContext, Paint paint, FontRenderer font, String text, float x, float baseline, int color) {
        paint.setColor(color);
        drawContext.drawString(text, x, baseline + BASELINE_FIX, font, paint);
    }

    private Layout layout(View view, float available) {
        List<Part> parts = new ArrayList<>();
        boolean singleplayer = view.singleplayer();
        float brandWidth = BRAND_SIZE * BRAND_GLYPH_WIDTH;
        float iconWidth = ICON_SIZE * MATERIAL_GLYPH_WIDTH;
        float fixed = INSET + RIGHT_INSET + brandWidth + 3.0f * iconWidth
                + 4.0f * ICON_GAP
                + 3.0f * (SEPARATOR_LEAD + TEXT_FONT.getWidth(SEPARATOR) + SEPARATOR_TRAIL)
                + TEXT_FONT.getWidth(BRAND)
                + TEXT_FONT.getWidth(Math.max(0, view.fps()) + " fps")
                + (singleplayer ? 0.0f : TEXT_FONT.getWidth(view.pingText() + " to "));
        String connection = singleplayer ? "singleplayer" : view.server();
        float remaining = Math.max(0.0f, available - fixed);
        float userWidth = TEXT_FONT.getWidth(view.user());
        float connectionWidth = TEXT_FONT.getWidth(connection);
        float userBudget = userWidth + connectionWidth <= remaining ? userWidth
                : Math.min(userWidth, Math.max(remaining - connectionWidth, remaining * 0.4f));
        String user = fit(view.user(), userBudget);
        connection = fit(connection, Math.max(0.0f, remaining - TEXT_FONT.getWidth(user)));
        float x = INSET;
        x = this.icon(parts, Icon.BRAND, this.accentColor, x, brandWidth, -BRAND_SIZE * BRAND_GLYPH_INSET + 2.0f);
        x = this.label(parts, BRAND, this.accentColor, x);
        x = this.separator(parts, x);
        x = this.icon(parts, Icon.USER, DEFAULT_FOREGROUND, x, iconWidth,
                -ICON_SIZE * (MATERIAL_GLYPH_INSET - MATERIAL_GLYPH_SHIFT) + USER_SHIFT);
        x = this.label(parts, user, DEFAULT_FOREGROUND, x);
        x = this.separator(parts, x);
        int pingColor = view.ping() < 0 ? DEFAULT_FOREGROUND : view.pingColor();
        x = this.icon(parts, Icon.LINK, singleplayer ? DEFAULT_FOREGROUND : pingColor, x, iconWidth,
                -ICON_SIZE * (MATERIAL_GLYPH_INSET - MATERIAL_GLYPH_SHIFT) + LINK_SHIFT);
        if (!singleplayer) {
            x = this.label(parts, view.pingText(), pingColor, x);
            x = this.label(parts, " to ", DEFAULT_FOREGROUND, x);
        }
        x = this.label(parts, connection, DEFAULT_FOREGROUND, x);
        x = this.separator(parts, x);
        x = this.icon(parts, Icon.REFRESH, DEFAULT_FOREGROUND, x, iconWidth, -ICON_SIZE * (MATERIAL_GLYPH_INSET - MATERIAL_GLYPH_SHIFT));
        x = this.label(parts, Math.max(0, view.fps()) + " fps", DEFAULT_FOREGROUND, x);
        return new Layout(List.copyOf(parts), x + RIGHT_INSET);
    }

    private List<Row> rows(long now) {
        List<Row> rows = new ArrayList<>(MAX_ROWS);
        for (Notice notice : this.notices) {
            boolean progressRow = notice.icon == Icon.SCAFFOLD;
            rows.add(new Row(notice.title, notice.detail, notice.status, notice.icon, notice.enabled,
                    notice.toggle.value(now), notice.progress, progressRow ? SCAFFOLD_HEIGHT : ROW_HEIGHT,
                    progressRow ? SCAFFOLD_LINE_GAP : TOGGLE_LINE_GAP,
                    progressRow ? SCAFFOLD_PADDING : TOGGLE_PADDING,
                    progressRow ? SCAFFOLD_TEXT_RIGHT : ROW_TEXT_RIGHT));
        }
        return rows;
    }

    private float rowsWidth(List<Row> rows, float available) {
        float widest = 0.0f;
        for (Row row : rows) {
            float titleWidth = TEXT_FONT.getWidth(row.title());
            float detailWidth = row.status().isEmpty() ? DETAIL_FONT.getWidth(row.detail())
                    : TEXT_FONT.getWidth(row.detail()) + TEXT_FONT.getWidth(row.status());
            widest = Math.max(widest, ROW_TEXT_X + Math.max(titleWidth, detailWidth) + row.rightPadding());
        }
        return Math.min(available, Math.max(240.0f, widest));
    }

    private float rowsHeight(List<Row> rows) {
        float total = 0.0f;
        for (Row row : rows) {
            total += row.height();
        }
        total += ROW_GAP * Math.max(0, rows.size() - 1);
        return total;
    }

    private Chest chest(long now) {
        if (!(mc.screen instanceof ContainerScreen screen)) {
            this.lastCounts = null;
            return null;
        }
        ChestMenu menu = screen.getMenu();
        if (menu == null) {
            this.lastCounts = null;
            return null;
        }
        int rows = menu.getRowCount();
        if (rows <= 0) {
            this.lastCounts = null;
            return null;
        }
        int size = rows * 9;
        if (this.lastCounts == null || this.lastCounts.length != size) {
            this.lastCounts = new int[size];
            this.looted.clear();
        }
        for (int slot = 0; slot < size; slot++) {
            int count = menu.getSlot(slot).getItem().getCount();
            if (this.lastCounts[slot] > count) {
                this.looted.put(slot, now);
            }
            this.lastCounts[slot] = count;
        }
        return new Chest(menu, rows, 9.0f * SLOT_SIZE + CHEST_PADDING * 2.0f - 4.0f, rows * SLOT_SIZE + CHEST_PADDING * 2.0f - 4.0f);
    }

    private void syncScaffold(long now) {
        if (Scaffold.INSTANCE == null || !Scaffold.INSTANCE.isEnabled() || mc.player == null || mc.level == null) {
            this.notices.removeIf(notice -> notice.key.equals("scaffold"));
            return;
        }
        int blocks = ItemUtil.countBlocks();
        String detail = String.format(Locale.ROOT, "%d blocks left \u00b7 %.1f block/s", blocks, MovementUtil.getSpeedBps());
        this.notices.removeIf(notice -> !notice.key.equals("scaffold"));
        this.post("scaffold", "Scaffold Toggled", detail, "", Icon.SCAFFOLD, true, PROGRESS_MS, Math.min(1.0f, blocks / 100.0f), now);
    }

    private void post(String key, String title, String detail, String status, Icon icon, boolean enabled, long duration, float progress, long now) {
        Notice notice = null;
        for (Notice candidate : this.notices) {
            if (candidate.key.equals(key)) {
                notice = candidate;
                break;
            }
        }
        if (notice == null) {
            notice = new Notice(key, now);
            this.notices.add(notice);
        }
        notice.title = title;
        notice.detail = detail;
        notice.status = status;
        notice.icon = icon;
        notice.enabled = enabled;
        notice.progress = progress;
        notice.expiresAt = now + duration;
        notice.toggle.target(enabled ? 1.0f : 0.0f, now);
        while (this.notices.size() > MAX_ROWS) {
            this.notices.remove(0);
        }
    }

    private View view() {
        String user = mc.player != null ? mc.player.getGameProfile().getName() : null;
        if (user == null || user.isEmpty()) {
            user = "Player";
        }
        String server = null;
        if (!mc.isSingleplayer()) {
            ServerData serverData = mc.getCurrentServer();
            server = serverData != null ? serverData.ip : "server";
        }
        return new View(user, server, PingTracker.getPing(), mc.getFps());
    }

    private float icon(List<Part> parts, Icon icon, int color, float x, float width, float drawOffset) {
        parts.add(new Part("", icon, color, x + drawOffset));
        return x + width + ICON_GAP;
    }

    private float label(List<Part> parts, String text, int color, float x) {
        float width = TEXT_FONT.getWidth(text);
        parts.add(new Part(text, Icon.NONE, color, x));
        return x + width;
    }

    private float separator(List<Part> parts, float x) {
        float width = TEXT_FONT.getWidth(SEPARATOR);
        parts.add(new Part(SEPARATOR, Icon.NONE, DEFAULT_FOREGROUND, x + SEPARATOR_LEAD));
        return x + SEPARATOR_LEAD + width + SEPARATOR_TRAIL;
    }

    private static String fit(String text, float width) {
        if (width <= 0.0f) {
            return "";
        }
        if (TEXT_FONT.getWidth(text) <= width + 0.01f) {
            return text;
        }
        if (TEXT_FONT.getWidth("...") > width) {
            return "";
        }
        int end = text.length();
        while (end > 0 && TEXT_FONT.getWidth(text.substring(0, end) + "...") > width) {
            end = text.offsetByCodePoints(end, -1);
        }
        return text.substring(0, end) + "...";
    }

    private static String glyph(Icon icon) {
        return switch (icon) {
            case USER -> "\ue7fd";
            case LINK -> "\ue157";
            case REFRESH -> "\ue5d5";
            case SUCCESS -> "\ue5ca";
            case WARNING -> "\ue002";
            case INFO -> "\ue88e";
            case SCAFFOLD -> "\ue53b";
            default -> "";
        };
    }

    private static int mix(int from, int to, float amount) {
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int fromChannel = from >>> shift & 0xFF;
            int toChannel = to >>> shift & 0xFF;
            result |= Math.round(fromChannel + (toChannel - fromChannel) * amount) << shift;
        }
        return result;
    }

    private record Part(String text, Icon icon, int color, float x) {
    }

    private record Layout(List<Part> parts, float width) {
    }

    private record Row(String title, String detail, String status, Icon icon, boolean enabled, float toggle, float progress, float height, float lineGap, float padding, float rightPadding) {
    }

    private record Chest(ChestMenu menu, int rows, float width, float height) {
    }

    private record View(String user, String server, int ping, int fps) {
        boolean singleplayer() {
            return this.server == null;
        }

        String pingText() {
            return this.ping < 0 ? "...ms" : this.ping + "ms";
        }

        int pingColor() {
            if (this.ping < 0) {
                return ONLINE_COLOR;
            }
            if (this.ping < 100) {
                return ONLINE_COLOR;
            }
            return this.ping <= 400 ? PING_ORANGE_COLOR : PING_RED_COLOR;
        }
    }

    private enum Icon {
        NONE, BRAND, USER, LINK, REFRESH, TOGGLE, SUCCESS, WARNING, INFO, SCAFFOLD
    }

    private static final class Notice {
        final String key;
        final Transition toggle;
        String title;
        String detail;
        String status;
        Icon icon;
        boolean enabled;
        float progress;
        long expiresAt;

        Notice(String key, long now) {
            this.key = key;
            this.toggle = new Transition(now);
        }
    }

    private static final class Transition {
        float from;
        float to;
        long started;

        Transition(long now) {
            this.started = now;
        }

        void target(float destination, long now) {
            if (destination != this.to) {
                this.from = this.value(now);
                this.to = destination;
                this.started = now;
            }
        }

        float value(long now) {
            float progress = Mth.clamp((now - this.started) / 200.0f, 0.0f, 1.0f);
            float eased = this.to < this.from ? progress : progress >= 1.0f ? 1.0f : 1.0f - (float)Math.pow(2.0, -10.0 * progress);
            return this.from + (this.to - this.from) * eased;
        }
    }

    private static final class Morph {
        private static final double DECAY = 8.7;
        private static final double FREQUENCY = 8.0;
        private static final double IMPULSE = 5.5;
        private double current;
        private double target;
        private double velocity;
        private long sampled = -1L;

        float value(float destination, long now) {
            if (this.sampled < 0L) {
                this.current = destination;
                this.target = destination;
                this.velocity = 0.0;
            } else {
                double seconds = Math.max(0L, now - this.sampled) / 1000.0;
                double displacement = this.current - this.target;
                double sineCoefficient = (this.velocity + DECAY * displacement) / FREQUENCY;
                double cosine = Math.cos(FREQUENCY * seconds);
                double sine = Math.sin(FREQUENCY * seconds);
                double decay = Math.exp(-DECAY * seconds);
                double offset = displacement * cosine + sineCoefficient * sine;
                this.current = this.target + decay * offset;
                this.velocity = decay * (-DECAY * offset + FREQUENCY * (-displacement * sine + sineCoefficient * cosine));
                if (destination != this.target) {
                    this.target = destination;
                    if (Math.abs(this.velocity) < Math.max(0.1, Math.abs(destination - this.current) * 0.1)) {
                        this.velocity = (destination - this.current) * IMPULSE;
                    }
                }
                if (Math.abs(this.current - this.target) < 0.01 && Math.abs(this.velocity) < 0.1) {
                    this.current = this.target;
                    this.velocity = 0.0;
                }
            }
            this.sampled = now;
            return (float)this.current;
        }
    }
}
