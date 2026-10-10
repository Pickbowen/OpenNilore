package client.nilore.modules.impl.render;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.ChatFormatting;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.ChatVisiblity;
import client.nilore.ClientBase;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.render.DrawContext;
import client.nilore.render.FontPresets;
import client.nilore.render.FontRenderer;
import client.nilore.render.Paint;
import client.nilore.render.RoundedRectangle;
import client.nilore.render.RoundedRectShader;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.misc.ReflectionUtil;
import client.nilore.utils.render.RenderUtil;

public class BetterChat extends Module {
    public static BetterChat INSTANCE;

    private static final float PADDING = 2.0f;
    /** Extra downward shift applied to every re-drawn chat line (PingFang font only). */
    public static final float LINE_DROP = 10.0f;

    private final BooleanSetting backgroundEnabled = new BooleanSetting("Background", true);
    private final NumberSetting backgroundAlpha = new NumberSetting("Background Alpha", 70, 0, 255, 1);
    private final NumberSetting backgroundRadius = new NumberSetting("Background Radius", 4.0f, 0.0f, 20.0f, 0.5f);
    private final BooleanSetting glowEnabled = new BooleanSetting("Glow", true);
    private final NumberSetting glowRadius = new NumberSetting("Glow Radius", 10.0f, 0.0f, 40.0f, 1.0f);
    private final NumberSetting glowAlpha = new NumberSetting("Glow Alpha", 170, 0, 255, 1);
    private final BooleanSetting blurEnabled = new BooleanSetting("Blur", false);
    private final NumberSetting blurStrength = new NumberSetting("Blur Strength", 15.0f, 1.0f, 30.0f, 1.0f);
    private final NumberSetting blurOpacity = new NumberSetting("Blur Opacity", 0.95f, 0.0f, 1.0f, 0.05f);
    private final ModeSetting fontMode = new ModeSetting("Font", "Minecraft", "Minecraft", "PingFang")
            .withDefault("Minecraft");
    private final NumberSetting yOffset = new NumberSetting("Y Offset", 0.0f, -60.0f, 60.0f, 1.0f);

    private static Field trimmedMessagesField;
    private static Field scrollPosField;
    private static boolean fieldsResolved;

    private boolean backgroundDrawn;

    public BetterChat() {
        super("BetterChat", Category.RENDER);
        INSTANCE = this;
    }

    @Override
    protected void onDisable() {
        this.backgroundDrawn = false;
    }

    @Override
    public String getSuffix() {
        if (this.blurEnabled.getValue()) {
            return "Blur";
        }
        return "PingFang".equals(this.fontMode.getValue()) ? "Font" : null;
    }

    private boolean active() {
        return this.isEnabled() && ClientBase.mc != null && ClientBase.mc.gui != null && ClientBase.mc.options != null;
    }

    /** The chat render pass is always taken over while the module is on. */
    public boolean replacesChatText() {
        return this.active();
    }

    public boolean usesMinecraftFont() {
        return this.fontMode == null || "Minecraft".equals(this.fontMode.getValue());
    }

    /** Screen-space downward shift of the re-drawn lines, used to keep click hit-testing aligned. */
    public float textYOffset() {
        return this.yOffset.getValue().floatValue();
    }

    /**
     * Whether the unified background actually went up this frame. The vanilla input-bar fill is
     * only masked while it did, so an empty chat keeps the vanilla bar instead of losing every
     * backdrop at once.
     */
    public boolean drawsBackgroundThisFrame() {
        return this.backgroundDrawn;
    }

    /**
     * Repaints the whole chat (background + every visible line) in our own pass. Both fonts go
     * through here: the Minecraft one is handed the original {@code FormattedCharSequence} so it
     * keeps every style, the custom one is flattened to legacy colour codes.
     */
    public void renderChat(GuiGraphics graphics, ChatComponent chat, int tickCount) {
        if (!this.canRenderChat()) {
            return;
        }
        float scale = (float) chat.getScale();
        if (scale <= 0.0f) {
            return;
        }
        int guiHeight = ClientBase.mc.getWindow().getGuiScaledHeight();
        int linesPerPage = chat.getLinesPerPage();
        int lineHeight = this.chatLineHeight();
        boolean focused = ClientBase.mc.screen instanceof ChatScreen;
        List<?> lines = this.trimmedMessages(chat);
        int scroll = this.chatScrollbarPos(chat);
        double opacityScale = ClientBase.mc.options.chatOpacity().get() * 0.9D + 0.1D;
        double lineSpacing = ClientBase.mc.options.chatLineSpacing().get();
        int baselineOffset = (int) Math.round(-8.0D * (lineSpacing + 1.0D) + 4.0D * lineSpacing);
        int chatBottom = Mth.floor((float) (guiHeight - 40) / scale);
        boolean useMcFont = this.usesMinecraftFont();

        this.renderBackground(graphics);

        FontRenderer font = FontPresets.pingfang(18.0f);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.scale(scale, scale, 1.0f);
        pose.translate(4.0f, this.textYOffset() / scale, 0.0f);
        DrawContext drawContext = new DrawContext(graphics, pose);
        int shown = 0;
        for (int row = 0; row < linesPerPage && row + scroll < lines.size(); ++row) {
            Object entry = lines.get(row + scroll);
            if (!(entry instanceof GuiMessage.Line line)) {
                break;
            }
            int age = tickCount - line.addedTime();
            if (age >= 200 && !focused) {
                continue;
            }
            double fade = focused ? 1.0D : BetterChat.timeFactor(age);
            int alpha = (int) (255.0D * fade * opacityScale);
            if (alpha <= 3) {
                continue;
            }
            ++shown;
            float y = chatBottom - row * lineHeight + baselineOffset + (useMcFont ? 0.0f : LINE_DROP);
            int color = alpha << 24 | 0xFFFFFF;
            if (useMcFont) {
                graphics.drawString(ClientBase.mc.font, line.content(), 0, (int) y, color);
            } else {
                try (Paint paint = new Paint()) {
                    paint.setColor(color);
                    drawContext.drawString(BetterChat.toLegacy(line.content()), 0.0f, y, font, paint);
                }
            }
        }
        if (focused) {
            this.drawScrollbar(graphics, chat, lines.size(), shown, scroll, chatBottom, lineHeight, scale);
        }
        pose.popPose();
    }

    /** Vanilla's scrollbar, so a scrolled chat still shows where it is. */
    private void drawScrollbar(GuiGraphics graphics, ChatComponent chat, int totalLines, int shown,
                               int scroll, int chatBottom, int lineHeight, float scale) {
        if (totalLines <= 0 || shown <= 0 || totalLines == shown) {
            return;
        }
        int trackHeight = totalLines * lineHeight;
        int thumbHeight = shown * lineHeight;
        int offset = scroll * thumbHeight / totalLines - chatBottom;
        int thumbScale = thumbHeight * thumbHeight / trackHeight;
        int accent = 3355562;
        int alpha = (offset > 0 ? 170 : 96) << 24;
        int x = Mth.ceil(chat.getWidth() / scale) + 4;
        graphics.fill(x, -offset, x + 2, -offset - thumbScale, alpha | accent);
        graphics.fill(x + 2, -offset, x + 1, -offset - thumbScale, alpha | 13421772);
    }

    public void renderBackground(GuiGraphics graphics) {
        this.backgroundDrawn = false;
        if (!this.canRenderChat()) {
            return;
        }
        ChatComponent chat = ClientBase.mc.gui.getChat();
        float scale = (float) chat.getScale();
        if (scale <= 0.0f) {
            return;
        }
        int guiHeight = ClientBase.mc.getWindow().getGuiScaledHeight();
        boolean focused = ClientBase.mc.screen instanceof ChatScreen;
        List<?> lines = this.trimmedMessages(chat);
        int scroll = this.chatScrollbarPos(chat);
        int tickCount = ClientBase.mc.gui.getGuiTicks();
        int shown = 0;
        for (int i = 0; i < chat.getLinesPerPage() && i + scroll < lines.size(); ++i) {
            Object entry = lines.get(i + scroll);
            if (!(entry instanceof GuiMessage.Line messageLine)) {
                break;
            }
            if (focused || tickCount - messageLine.addedTime() < 200) {
                ++shown;
            }
        }

        if (shown <= 0) {
            // Nothing visible to frame — leave the vanilla look completely untouched.
            return;
        }

        float offset = this.yOffset.getValue().floatValue();
        float inputTop = guiHeight - 14.0f;
        float messagesBottom = guiHeight - 40.0f;
        float messagesTop = messagesBottom - shown * this.chatLineHeight() * scale;
        float top = Math.min(messagesTop, inputTop) - PADDING + offset;
        float left = -PADDING;
        float right = (Mth.ceil(chat.getWidth() / scale) + 12) * scale + PADDING;
        float bottom = (guiHeight - 2.0f) + PADDING + offset;
        if (right - left < 1.0f || bottom - top < 1.0f) {
            return;
        }

        PoseStack pose = graphics.pose();
        float radius = this.backgroundRadius.getValue().floatValue();

        if (this.glowEnabled.getValue()) {
            int alpha = this.glowAlpha.getValue().intValue();
            float glowRadius = this.glowRadius.getValue().floatValue();
            if (alpha > 0 && glowRadius > 0.0f) {
                RoundedRectShader shader = DrawContext.getRoundedRectShader();
                shader.drawGlow(pose.last().pose(), left, top, right, bottom,
                        radius, radius, radius, radius, glowRadius, alpha << 24);
            }
        }

        if (this.blurEnabled.getValue()) {
            float opacity = this.blurOpacity.getValue().floatValue();
            if (opacity > 0.0f) {
                RenderUtil.drawBlurredRect(pose, left, top, right - left, bottom - top, radius,
                        this.blurStrength.getValue().floatValue(), opacity, 0);
            }
        }
        if (this.backgroundEnabled.getValue()) {
            int alpha = this.backgroundAlpha.getValue().intValue();
            if (alpha > 0) {
                try (Paint paint = new Paint()) {
                    paint.setColor(alpha << 24);
                    new DrawContext(graphics, pose)
                            .drawRoundedRect(RoundedRectangle.ofXYWHR(left, top, right - left, bottom - top, radius), paint);
                }
            }
        }
        this.backgroundDrawn = true;
    }

    /** Shared guard for both entry points: chat is only drawn when it is actually visible. */
    private boolean canRenderChat() {
        if (!this.active()) {
            return false;
        }
        if (ClientBase.mc.options.chatVisibility().get() == ChatVisiblity.HIDDEN) {
            return false;
        }
        return ClientBase.mc.gui.getChat() != null;
    }

    private int chatLineHeight() {
        return (int) (9.0D * (ClientBase.mc.options.chatLineSpacing().get() + 1.0D));
    }

    private static double timeFactor(int age) {
        double value = (double) age / 200.0D;
        value = 1.0D - value;
        value *= 10.0D;
        value = Mth.clamp(value, 0.0D, 1.0D);
        return value * value;
    }

    /**
     * Flattens a formatted line into legacy {@code §}-codes. Only colours survive: the
     * client font renderer understands single-character colour codes and ignores weight.
     */
    private static String toLegacy(FormattedCharSequence sequence) {
        StringBuilder result = new StringBuilder();
        int[] lastColor = {Integer.MIN_VALUE};
        sequence.accept((FormattedCharSink) (index, style, codePoint) -> {
            int rgb = BetterChat.colourOf(style);
            if (rgb != lastColor[0]) {
                lastColor[0] = rgb;
                ChatFormatting legacy = BetterChat.legacyColor(rgb);
                result.append('§').append(legacy == null ? 'f' : Character.toLowerCase(legacy.getChar()));
            }
            result.appendCodePoint(codePoint);
            return true;
        });
        return result.toString();
    }

    private static int colourOf(Style style) {
        TextColor textColor = style.getColor();
        return textColor == null ? 0xFFFFFF : textColor.getValue();
    }

    private static ChatFormatting legacyColor(int rgb) {
        for (ChatFormatting formatting : ChatFormatting.values()) {
            Integer color = formatting.getColor();
            if (color != null && color == rgb) {
                return formatting;
            }
        }
        return null;
    }

    private List<?> trimmedMessages(ChatComponent chat) {
        this.resolveFields(chat);
        if (trimmedMessagesField == null) {
            return Collections.emptyList();
        }
        try {
            Object value = trimmedMessagesField.get(chat);
            return value instanceof List<?> list ? list : Collections.emptyList();
        } catch (Throwable throwable) {
            return Collections.emptyList();
        }
    }

    private int chatScrollbarPos(ChatComponent chat) {
        this.resolveFields(chat);
        if (scrollPosField == null) {
            return 0;
        }
        try {
            return scrollPosField.getInt(chat);
        } catch (Throwable throwable) {
            return 0;
        }
    }

    private void resolveFields(ChatComponent chat) {
        if (fieldsResolved) {
            return;
        }
        fieldsResolved = true;
        trimmedMessagesField = BetterChat.resolve(chat.getClass(), "trimmedMessages");
        scrollPosField = BetterChat.resolve(chat.getClass(), "chatScrollbarPos");
    }

    private static Field resolve(Class<?> clazz, String name) {
        try {
            String mapped = ReflectionUtil.getMappedFieldName(clazz, name);
            try {
                Field field = clazz.getDeclaredField(mapped);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            }
        } catch (Throwable throwable) {
            return null;
        }
    }
}
