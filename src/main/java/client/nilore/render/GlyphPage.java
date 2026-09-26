package client.nilore.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.chars.Char2ObjectArrayMap;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;
import java.nio.IntBuffer;
import java.util.ArrayList;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import client.nilore.ClientBase;
import client.nilore.render.Glyph;
import client.nilore.utils.misc.ReflectionUtil;

class GlyphPage {
    final char startChar;
    final char endChar;
    final Font font;
    final ResourceLocation textureLocation;
    final int padding;
    /**
     * 伪粗体的水平偏移量，单位是纹理像素；0 表示按普通字重渲染。
     *
     * <p>光栅化是在放大 {@code scale} 倍之后做的，所以这个值由调用方按 scale 折算，
     * 保证缩回屏幕上的加粗量是一致的。
     */
    final int boldPx;
    /**
     * 主字体画不出来的字符交给它们兜底，按顺序试。
     *
     * <p>典型用法是主字体选 Apple 风格的西文字体、中文字符落到苹方——
     * 之前这里两个分支返回的是同一个字体，等于 fallback 从来没生效过。
     */
    private final Font[] fallbacks;
    private final Char2ObjectArrayMap<Glyph> glyphMap = new Char2ObjectArrayMap();
    int imageWidth;
    int imageHeight;
    boolean uploaded = false;

    public GlyphPage(char startChar, char endChar, Font font, ResourceLocation textureLocation, int padding) {
        this(startChar, endChar, font, textureLocation, padding, 0, null);
    }

    public GlyphPage(char startChar, char endChar, Font font, ResourceLocation textureLocation, int padding, int boldPx) {
        this(startChar, endChar, font, textureLocation, padding, boldPx, null);
    }

    public GlyphPage(char startChar, char endChar, Font font, ResourceLocation textureLocation, int padding,
                     int boldPx, Font[] fallbacks) {
        this.startChar = startChar;
        this.endChar = endChar;
        this.font = font;
        this.textureLocation = textureLocation;
        this.padding = padding;
        this.boldPx = Math.max(0, boldPx);
        this.fallbacks = fallbacks == null ? new Font[0] : fallbacks;
    }

    public Glyph getGlyph(char c) {
        if (!this.uploaded) {
            this.buildAtlas();
        }
        return this.glyphMap.get(c);
    }

    public void reset() {
        ClientBase.mc.getTextureManager().release(this.textureLocation);
        this.glyphMap.clear();
        this.imageWidth = -1;
        this.imageHeight = -1;
        this.uploaded = false;
    }

    public boolean contains(char c) {
        return c >= this.startChar && c < this.endChar;
    }

    private Font getFontForChar(char c) {
        if (this.font.canDisplay(c)) {
            return this.font;
        }
        for (Font fallback : this.fallbacks) {
            if (fallback.canDisplay(c)) {
                return fallback;
            }
        }
        return this.font;
    }

    public void buildAtlas() {
        if (this.uploaded) {
            return;
        }
        int total = this.endChar - this.startChar - 1;
        int columns = (int)(Math.ceil(Math.sqrt(total)) * 1.5);
        this.glyphMap.clear();
        int index = 0;
        int colCount = 0;
        int maxWidth = 0;
        int maxHeight = 0;
        int curX = 0;
        int curY = 0;
        int rowHeight = 0;
        ArrayList<Glyph> glyphs = new ArrayList<>();
        AffineTransform affineTransform = new AffineTransform();
        FontRenderContext fontRenderContext = new FontRenderContext(affineTransform, true, false);
        while (index <= total) {
            char c = (char)(this.startChar + index);
            Font font = this.getFontForChar(c);
            Rectangle2D bounds = font.getStringBounds(String.valueOf(c), fontRenderContext);
            // 伪粗体要往右多占一点，不然加粗出来的那半边会被字形格子裁掉
            int width = (int)Math.ceil(bounds.getWidth()) + this.boldPx;
            int height = (int)Math.ceil(bounds.getHeight());
            ++index;
            maxWidth = Math.max(maxWidth, curX + width);
            maxHeight = Math.max(maxHeight, curY + height);
            if (colCount >= columns) {
                curX = 0;
                curY += rowHeight + this.padding;
                colCount = 0;
                rowHeight = 0;
            }
            rowHeight = Math.max(rowHeight, height);
            glyphs.add(new Glyph(curX, curY, width, height, c, this));
            curX += width + this.padding;
            ++colCount;
        }
        BufferedImage atlasImage = new BufferedImage(
                Math.max(maxWidth + this.padding, 1),
                Math.max(maxHeight + this.padding, 1), 2);
        this.imageWidth = atlasImage.getWidth();
        this.imageHeight = atlasImage.getHeight();
        java.awt.Graphics2D graphics = atlasImage.createGraphics();
        graphics.setColor(new Color(255, 255, 255, 1));
        graphics.fillRect(0, 0, this.imageWidth, this.imageHeight);
        graphics.setColor(Color.WHITE);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        for (Glyph glyph : glyphs) {
            graphics.setFont(this.getFontForChar(glyph.value()));
            FontMetrics fontMetrics = graphics.getFontMetrics();
            String text = String.valueOf(glyph.value());
            int baseline = glyph.v() + fontMetrics.getAscent();
            graphics.drawString(text, glyph.u(), baseline);
            if (this.boldPx > 0) {
                // 伪粗体：同一个字形往右再画一遍，笔画就撑粗了。
                // 真正的 Bold 变体要有对应的 ttf 才有用，而中文字体只有一个 Regular，
                // 所以只能这样合成（和 STB 那套 fauxBold 是同一个思路）。
                graphics.drawString(text, glyph.u() + this.boldPx, baseline);
            }
            this.glyphMap.put(glyph.value(), glyph);
        }
        GlyphPage.uploadTexture(this.textureLocation, atlasImage);
        this.uploaded = true;
    }

    public static void uploadTexture(ResourceLocation resourceLocation, BufferedImage source) {
        try {
            int width = source.getWidth();
            int height = source.getHeight();
            NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, width, height, false);
            long pixelsPtr = (Long)ReflectionUtil.getStaticField(nativeImage, "pixels", "com/mojang/blaze3d/platform/NativeImage");
            IntBuffer intBuffer = MemoryUtil.memIntBuffer(pixelsPtr, nativeImage.getWidth() * nativeImage.getHeight());
            boolean unused = false;
            WritableRaster raster = source.getRaster();
            ColorModel colorModel = source.getColorModel();
            int numBands = raster.getNumBands();
            int dataType = raster.getDataBuffer().getDataType();
            Object pixelData = switch (dataType) {
                case 0 -> new byte[numBands];
                case 1 -> new short[numBands];
                case 3 -> new int[numBands];
                case 4 -> new float[numBands];
                case 5 -> new double[numBands];
                default -> throw new IllegalArgumentException("Unknown data buffer type: " + dataType);
            };
            for (int i = 0; i < height; ++i) {
                for (int j = 0; j < width; ++j) {
                    raster.getDataElements(j, i, pixelData);
                    int a = colorModel.getAlpha(pixelData);
                    int r = colorModel.getRed(pixelData);
                    int g = colorModel.getGreen(pixelData);
                    int b = colorModel.getBlue(pixelData);
                    int abgr = a << 24 | b << 16 | g << 8 | r;
                    intBuffer.put(abgr);
                }
            }
            DynamicTexture dynamicTexture = new DynamicTexture(nativeImage);
            dynamicTexture.upload();
            RenderSystem.bindTexture(dynamicTexture.getId());
            GL11.glTexParameteri(3553, 10241, 9729);
            GL11.glTexParameteri(3553, 10240, 9729);
            if (RenderSystem.isOnRenderThread()) {
                ClientBase.mc.getTextureManager().register(resourceLocation, dynamicTexture);
            } else {
                RenderSystem.recordRenderCall(() -> ClientBase.mc.getTextureManager().register(resourceLocation, dynamicTexture));
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }
}