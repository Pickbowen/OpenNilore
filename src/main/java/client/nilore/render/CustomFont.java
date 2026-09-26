package client.nilore.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.chars.Char2IntArrayMap;
import it.unimi.dsi.fastutil.chars.Char2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.*;

import java.awt.Color;
import java.awt.Font;
import java.io.Closeable;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.Setter;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import client.nilore.ClientBase;
import client.nilore.utils.math.MathUtil;

public class CustomFont
implements Closeable {

    public record GlyphEntry(float atX, float atY, float r, float g, float b, Glyph toDraw) {
    }

    static class MinecraftColorMap extends Char2IntArrayMap {
        MinecraftColorMap() {
            this.put('0', 0);
            this.put('1', 170);
            this.put('2', 43520);
            this.put('3', 43690);
            this.put('4', 0xAA0000);
            this.put('5', 0xAA00AA);
            this.put('6', 0xFFAA00);
            this.put('7', 0xAAAAAA);
            this.put('8', 0x555555);
            this.put('9', 0x5555FF);
            this.put('A', 0x55FF55);
            this.put('B', 0x55FFFF);
            this.put('C', 0xFF5555);
            this.put('D', 0xFF55FF);
            this.put('E', 0xFFFF55);
            this.put('F', 0xFFFFFF);
        }
    }

    private static final Char2IntArrayMap MC_COLOR_CODES = new CustomFont.MinecraftColorMap();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private final Object2ObjectMap<ResourceLocation, ObjectList<CustomFont.GlyphEntry>> glyphPageMap = new Object2ObjectOpenHashMap();
    private final float fontSize;
    /** 是否用合成粗体。字体文件没有 Bold 变体时（中文基本都没有）靠它把笔画撑粗。 */
    private final boolean bold;
    /** 合成粗体在纹理里的水平偏移量。随 guiScale 变化，所以每次 initFont 重算。 */
    private int boldPx;
    /** 主字体画不出来的字符交给它们兜底（西文字体 + 中文字体混排就靠这个）。 */
    private final Font[] fallbackFonts;
    /** 按当前 guiScale 派生过的兜底字体，字形光栅化用的是这一组。 */
    private Font[] scaledFallbacks = new Font[0];
    private final ObjectList<GlyphPage> glyphPages = new ObjectArrayList();
    private final Char2ObjectArrayMap<Glyph> glyphCache = new Char2ObjectArrayMap();
    private final int pageSize;
    private final int charsPerPage;
    private final String preloadChars;
    private int scale = 0;
    private Font scaledFont;
    private int guiScaleCache = -1;
    private Future<Void> preloadFuture;
    private boolean initialized;
    private static final Color SHADOW_COLOR = new Color(26, 26, 26, 160);
    @Setter
    private float letterSpacing = 0.0f;
    private FontMetricsImpl fontMetrics;

    public CustomFont(Font font, float fontSize, int pageSize, int charsPerPage, @Nullable String preloadChars) {
        this(font, fontSize, pageSize, charsPerPage, preloadChars, false, null);
    }

    public CustomFont(Font font, float fontSize, int pageSize, int charsPerPage, @Nullable String preloadChars, boolean bold) {
        this(font, fontSize, pageSize, charsPerPage, preloadChars, bold, null);
    }

    public CustomFont(Font font, float fontSize, int pageSize, int charsPerPage, @Nullable String preloadChars,
                      boolean bold, @Nullable Font[] fallbacks) {
        this.bold = bold;
        this.fallbackFonts = fallbacks == null ? new Font[0] : fallbacks;
        this.fontSize = fontSize;
        this.pageSize = pageSize;
        this.charsPerPage = charsPerPage;
        this.preloadChars = preloadChars;
        this.fontMetrics = new FontMetricsImpl(font);
        this.initFont(font, fontSize);
    }

    /**
     * 字形图集的分页大小。
     *
     * <p>光栅化是**整页**做的：第一次用到某页里任何一个字符，就会把这一页的字符全部渲染出来。
     * 原来取 256，碰上中文歌词（每个字都可能落在不同的 256 区间）一次要渲染 256 个字形，
     * 表现就是歌词一出来卡一下。降到 64 之后单次渲染量减少到四分之一，
     * 而一行歌词通常还挤在同一页里，页数不会涨太多。
     *
     * <p>彻底不卡得改成逐字形按需光栅化（像 STB 那样），那是另一个量级的改动。
     */
    private static final int DEFAULT_PAGE_SIZE = 64;

    public CustomFont(Font font, float fontSize) {
        this(font, fontSize, DEFAULT_PAGE_SIZE, 5, null, false, null);
    }

    public CustomFont(Font font, float fontSize, boolean bold) {
        this(font, fontSize, DEFAULT_PAGE_SIZE, 5, null, bold, null);
    }

    public CustomFont(Font font, float fontSize, boolean bold, @Nullable Font[] fallbacks) {
        this(font, fontSize, DEFAULT_PAGE_SIZE, 5, null, bold, fallbacks);
    }

    private static int alignToPageBoundary(int value, int pageSize) {
        return pageSize * (int)Math.floor((double)value / (double)pageSize);
    }

    public static String stripFormatting(String text) {
        char[] chars = text.toCharArray();
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < chars.length; ++i) {
            char c = chars[i];
            if (c == '§') {
                ++i;
                continue;
            }
            result.append(c);
        }
        return result.toString();
    }

    private void checkGuiScaleChanged() {
        int guiScale = (int)ClientBase.mc.getWindow().getGuiScale();
        if (guiScale != this.guiScaleCache) {
            this.close();
            this.initFont(this.scaledFont, this.fontSize);
        }
    }

    private void initFont(Font font, float fontSize) {
        if (this.initialized) {
            throw new IllegalStateException("Double call to init()");
        }
        this.initialized = true;
        this.guiScaleCache = (int)ClientBase.mc.getWindow().getGuiScale();
        this.scale = Math.max(2, this.guiScaleCache * 2);
        // 屏幕上想粗大约 0.5px，但字形是按 scale 倍光栅化进纹理的，
        // 所以偏移量要按同一个倍率折算成纹理像素。
        this.boldPx = this.bold ? Math.max(1, Math.round(this.scale * 0.5f)) : 0;
        // 兜底字体也要按同一个倍率派生，否则中文和西文的笔画粗细、大小会对不上
        this.scaledFallbacks = new Font[this.fallbackFonts.length];
        for (int i = 0; i < this.fallbackFonts.length; i++) {
            this.scaledFallbacks[i] = this.fallbackFonts[i].deriveFont(fontSize * (float) this.scale);
        }
        this.scaledFont = font.deriveFont(fontSize * (float)this.scale);
        this.fontMetrics = new FontMetricsImpl(this.scaledFont);
        if (this.preloadChars != null && !this.preloadChars.isEmpty()) {
            this.preloadFuture = this.startPreload();
        }
    }

    private Future<Void> startPreload() {
        return EXECUTOR.submit(() -> {
            for (char c : this.preloadChars.toCharArray()) {
                if (Thread.interrupted()) break;
                this.getOrLoadGlyph(c);
            }
            return null;
        });
    }

    private GlyphPage createGlyphPage(char startChar, char endChar) {
        GlyphPage glyphPage = new GlyphPage(startChar, endChar, this.scaledFont, CustomFont.getTempResourceLocation(),
                this.charsPerPage, this.boldPx, this.scaledFallbacks);
        this.glyphPages.add(glyphPage);
        return glyphPage;
    }

    private Glyph loadGlyph(char c) {
        for (GlyphPage existing : this.glyphPages) {
            if (!existing.contains(c)) continue;
            return existing.getGlyph(c);
        }
        int pageStart = CustomFont.alignToPageBoundary(c, this.pageSize);
        GlyphPage page = this.createGlyphPage((char)pageStart, (char)(pageStart + this.pageSize));
        return page.getGlyph(c);
    }

    @Nullable
    private Glyph getOrLoadGlyph(char c) {
        return this.glyphCache.computeIfAbsent(c, this::loadGlyph);
    }

    public void drawString(PoseStack poseStack, String text, double x, double y, int color) {
        float r = (float)(color >> 16 & 0xFF) / 255.0f;
        float g = (float)(color >> 8 & 0xFF) / 255.0f;
        float b = (float)(color & 0xFF) / 255.0f;
        float a = (float)(color >> 24 & 0xFF) / 255.0f;
        this.drawStringRGB(poseStack, text, (float)x, (float)y, r, g, b, a);
    }

    public void drawStringShadow(PoseStack poseStack, String text, double x, double y, int color) {
        float r = (float)(color >> 16 & 0xFF) / 255.0f;
        float g = (float)(color >> 8 & 0xFF) / 255.0f;
        float b = (float)(color & 0xFF) / 255.0f;
        float a = (float)(color >> 24 & 0xFF) / 255.0f;
        this.drawStringRGB(poseStack, text, (float)x, (float)y, r, g, b, a);
    }

    public void drawStringWithShadow(PoseStack poseStack, String text, double x, double y, int color) {
        this.drawStringColor(poseStack, text, (double)((float)x) + 0.5, (double)((float)y) + 0.5, SHADOW_COLOR);
        this.drawString(poseStack, text, (float)x, (float)y, color);
    }

    public void drawStringColor(PoseStack poseStack, String text, double x, double y, Color color) {
        this.drawStringRGB(poseStack, text, (float)x, (float)y, (float)color.getRed() / 255.0f, (float)color.getGreen() / 255.0f, (float)color.getBlue() / 255.0f, color.getAlpha());
    }

    public void drawStringRGB(PoseStack poseStack, String text, float x, float y, float r, float g, float b, float a) {
        this.drawStringRGBFull(poseStack, text, x, y, r, g, b, a, false, 0);
    }
    public void drawStringRGBFull(PoseStack poseStack, String text, float x, float y, float baseR, float baseG, float baseB, float alpha, boolean rainbow, int rainbowOffset) {
        if (this.preloadFuture != null && !this.preloadFuture.isDone()) {
            try {
                this.preloadFuture.get();
            } catch (ExecutionException | InterruptedException ex) {
            }
        }

        this.checkGuiScaleChanged();
        float curR = baseR;
        float curG = baseG;
        float curB = baseB;
        poseStack.pushPose();
        poseStack.translate(MathUtil.round(x, 1), MathUtil.round(--y, 1), 0.0);
        poseStack.scale(1.0F / this.scale, 1.0F / this.scale, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        Matrix4f pose = poseStack.last().pose();
        char[] chars = text.toCharArray();
        float penX = 0.0F;
        float penY = 0.0F;
        boolean inFormatting = false;
        int lineStart = 0;
        synchronized (this.glyphPageMap) {
            for (int i = 0; i < chars.length; i++) {
                char ch = chars[i];
                if (inFormatting) {
                    inFormatting = false;
                    char upper = Character.toUpperCase(ch);
                    if (MC_COLOR_CODES.containsKey(upper)) {
                        int packed = MC_COLOR_CODES.get(upper);
                        int[] rgb = colorToRGB(packed);
                        curR = rgb[0] / 255.0F;
                        curG = rgb[1] / 255.0F;
                        curB = rgb[2] / 255.0F;
                    } else if (upper == 'R') {
                        curR = baseR;
                        curG = baseG;
                        curB = baseB;
                    }
                } else if (ch == 167) {
                    inFormatting = true;
                } else if (ch == '\n') {
                    penY += this.getStringHeight(text.substring(lineStart, i)) * this.scale;
                    penX = 0.0F;
                    lineStart = i + 1;
                } else {
                    Glyph glyph = this.getOrLoadGlyph(ch);
                    if (glyph != null) {
                        if (glyph.value() != ' ') {
                            ResourceLocation textureLocation = glyph.owner().textureLocation;
                            GlyphEntry entry = new GlyphEntry(penX, penY, curR, curG, curB, glyph);
                            this.glyphPageMap.computeIfAbsent(textureLocation, key -> new ObjectArrayList<>()).add(entry);
                        }

                        penX += glyph.width() + this.letterSpacing;
                    }
                }
            }

            for (ResourceLocation textureLocation : this.glyphPageMap.keySet()) {
                RenderSystem.setShaderTexture(0, textureLocation);
                List<GlyphEntry> entries = this.glyphPageMap.get(textureLocation);
                Tesselator tesselator = Tesselator.getInstance();
                BufferBuilder bufferBuilder = tesselator.getBuilder();
                bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

                for (GlyphEntry entry : entries) {
                    float atX = entry.atX;
                    float atY = entry.atY;
                    float r = entry.r;
                    float g = entry.g;
                    float b = entry.b;
                    Glyph glyph = entry.toDraw;
                    GlyphPage page = glyph.owner();
                    float glyphWidth = glyph.width();
                    float glyphHeight = glyph.height();
                    float u1 = (float) glyph.u() / page.imageWidth;
                    float v1 = (float) glyph.v() / page.imageHeight;
                    float u2 = (float) (glyph.u() + glyph.width()) / page.imageWidth;
                    float v2 = (float) (glyph.v() + glyph.height()) / page.imageHeight;
                    bufferBuilder.vertex(pose, atX + 0.0F, atY + glyphHeight, 0.0F).uv(u1, v2).color(r, g, b, alpha).endVertex();
                    bufferBuilder.vertex(pose, atX + glyphWidth, atY + glyphHeight, 0.0F).uv(u2, v2).color(r, g, b, alpha).endVertex();
                    bufferBuilder.vertex(pose, atX + glyphWidth, atY + 0.0F, 0.0F).uv(u2, v1).color(r, g, b, alpha).endVertex();
                    bufferBuilder.vertex(pose, atX + 0.0F, atY + 0.0F, 0.0F).uv(u1, v1).color(r, g, b, alpha).endVertex();
                }

                tesselator.end();
            }

            this.glyphPageMap.clear();
        }

        poseStack.popPose();
        RenderSystem.disableBlend();
    }

    public void drawStringGradient(PoseStack poseStack, String text, float x, float y, int topColor, int bottomColor) {
        if (this.preloadFuture != null && !this.preloadFuture.isDone()) {
            try {
                this.preloadFuture.get();
            } catch (ExecutionException | InterruptedException ignored) {
            }
        }
        this.checkGuiScaleChanged();
        float topR = (topColor >> 16 & 0xFF) / 255.0f;
        float topG = (topColor >> 8 & 0xFF) / 255.0f;
        float topB = (topColor & 0xFF) / 255.0f;
        float topA = (topColor >>> 24 & 0xFF) / 255.0f;
        float bottomR = (bottomColor >> 16 & 0xFF) / 255.0f;
        float bottomG = (bottomColor >> 8 & 0xFF) / 255.0f;
        float bottomB = (bottomColor & 0xFF) / 255.0f;
        float bottomA = (bottomColor >>> 24 & 0xFF) / 255.0f;
        poseStack.pushPose();
        poseStack.translate(MathUtil.round(x, 1), MathUtil.round(--y, 1), 0.0);
        poseStack.scale(1.0f / this.scale, 1.0f / this.scale, 1.0f);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        Matrix4f pose = poseStack.last().pose();
        float penX = 0.0f;
        synchronized (this.glyphPageMap) {
            for (char ch : text.toCharArray()) {
                Glyph glyph = this.getOrLoadGlyph(ch);
                if (glyph == null) continue;
                if (glyph.value() != ' ') {
                    this.glyphPageMap.computeIfAbsent(glyph.owner().textureLocation, key -> new ObjectArrayList<>())
                            .add(new GlyphEntry(penX, 0.0f, 0.0f, 0.0f, 0.0f, glyph));
                }
                penX += glyph.width() + this.letterSpacing;
            }
            for (ResourceLocation textureLocation : this.glyphPageMap.keySet()) {
                RenderSystem.setShaderTexture(0, textureLocation);
                BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
                bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
                for (GlyphEntry entry : this.glyphPageMap.get(textureLocation)) {
                    Glyph glyph = entry.toDraw;
                    GlyphPage page = glyph.owner();
                    float u1 = (float) glyph.u() / page.imageWidth;
                    float v1 = (float) glyph.v() / page.imageHeight;
                    float u2 = (float) (glyph.u() + glyph.width()) / page.imageWidth;
                    float v2 = (float) (glyph.v() + glyph.height()) / page.imageHeight;
                    float right = entry.atX + glyph.width();
                    float bottom = entry.atY + glyph.height();
                    bufferBuilder.vertex(pose, entry.atX, bottom, 0.0f).uv(u1, v2).color(bottomR, bottomG, bottomB, bottomA).endVertex();
                    bufferBuilder.vertex(pose, right, bottom, 0.0f).uv(u2, v2).color(bottomR, bottomG, bottomB, bottomA).endVertex();
                    bufferBuilder.vertex(pose, right, entry.atY, 0.0f).uv(u2, v1).color(topR, topG, topB, topA).endVertex();
                    bufferBuilder.vertex(pose, entry.atX, entry.atY, 0.0f).uv(u1, v1).color(topR, topG, topB, topA).endVertex();
                }
                Tesselator.getInstance().end();
            }
            this.glyphPageMap.clear();
        }
        poseStack.popPose();
        RenderSystem.disableBlend();
    }

    public void drawStringCentered(PoseStack poseStack, String text, double x, double y, int color) {
        float r = (float)(color >> 16 & 0xFF) / 255.0f;
        float g = (float)(color >> 8 & 0xFF) / 255.0f;
        float b = (float)(color & 0xFF) / 255.0f;
        float a = (float)(color >> 24 & 0xFF) / 255.0f;
        this.drawStringRGB(poseStack, text, (float)(x - (double)(this.getStringWidth(text) / 2.0f)), (float)y, r, g, b, a);
    }

    public void drawStringCenteredColor(PoseStack poseStack, String text, double x, double y, Color color) {
        this.drawStringRGB(poseStack, text, (float)(x - (double)(this.getStringWidth(text) / 2.0f)), (float)y, (float)color.getRed() / 255.0f, (float)color.getGreen() / 255.0f, (float)color.getBlue() / 255.0f, (float)color.getAlpha() / 255.0f);
    }

    public void drawStringCenteredRGB(PoseStack poseStack, String text, float x, float y, float r, float g, float b, float a) {
        this.drawStringRGB(poseStack, text, x - this.getStringWidth(text) / 2.0f, y, r, g, b, a);
    }

    public float getStringWidth(String text) {
        char[] chars = CustomFont.stripFormatting(text).toCharArray();
        float lineWidth = 0.0f;
        float maxWidth = 0.0f;
        for (char c : chars) {
            if (c == '\n') {
                maxWidth = Math.max(lineWidth, maxWidth);
                lineWidth = 0.0f;
                continue;
            }
            Glyph glyph = this.getOrLoadGlyph(c);
            lineWidth += (glyph == null ? 0.0f : (float)glyph.width() / (float)this.scale) + this.letterSpacing;
        }
        return Math.max(lineWidth, maxWidth);
    }

    public float getStringHeight(String text) {
        char[] chars = CustomFont.stripFormatting(text).toCharArray();
        if (chars.length == 0) {
            chars = new char[]{' '};
        }
        float lineHeight = 0.0f;
        float totalHeight = 0.0f;
        for (char c : chars) {
            if (c == '\n') {
                if (lineHeight == 0.0f) {
                    lineHeight = this.getOrLoadGlyph(' ') == null ? 0.0f : (float)((Glyph)(Objects.requireNonNull((Object)(this.getOrLoadGlyph(' '))))).height() / (float)this.scale;
                }
                totalHeight += lineHeight;
                lineHeight = 0.0f;
                continue;
            }
            Glyph glyph = this.getOrLoadGlyph(c);
            lineHeight = Math.max(glyph == null ? 0.0f : (float)glyph.height() / (float)this.scale, lineHeight);
        }
        return lineHeight + totalHeight;
    }

    public float getFontHeight() {
        return (float)(this.fontMetrics.getLeading() + this.fontMetrics.getAscent() + this.fontMetrics.getDescent()) / (float)this.scale;
    }

    public FontMetricsImpl getFontMetrics() {
        return this.fontMetrics;
    }

    public int getScale() {
        return this.scale;
    }

    public void close() {
        try {
            if (this.preloadFuture != null && !this.preloadFuture.isDone() && !this.preloadFuture.isCancelled()) {
                this.preloadFuture.cancel(true);
                this.preloadFuture.get();
                this.preloadFuture = null;
            }
            for (GlyphPage glyphPage : this.glyphPages) {
                glyphPage.reset();
            }
            this.glyphPages.clear();
            this.glyphCache.clear();
            this.initialized = false;
        } catch (Exception exception) {
            // empty catch block
        }
    }

    @Contract(value="-> new", pure=true)
    @NotNull
    public static ResourceLocation getTempResourceLocation() {
        return ResourceLocation.tryParse("nilore:temp/" + CustomFont.generateRandomName());
    }

    private static String generateRandomName() {
        return IntStream.range(0, 32).mapToObj(i -> String.valueOf((char)new Random().nextInt(97, 123))).collect(Collectors.joining());
    }

    public static int @NotNull [] colorToRGB(int color) {
        int r = color >> 16 & 0xFF;
        int g = color >> 8 & 0xFF;
        int b = color & 0xFF;
        return new int[]{r, g, b};
    }

    public float getStringHeightAlias(String text) {
        return this.getStringHeight(text);
    }

    public void drawStringRainbow(PoseStack poseStack, String text, float x, float y, int offset) {
        this.drawStringRGBFull(poseStack, text, x, y, 255.0f, 255.0f, 255.0f, 255.0f, true, offset);
    }

    public void drawStringCenteredRainbow(PoseStack poseStack, String text, float x, float y, int offset) {
        this.drawStringRainbow(poseStack, text, x - this.getStringWidth(text) / 2.0f, y, offset);
    }

    public void resetLetterSpacing() {
        this.letterSpacing = 0.0f;
    }

    }