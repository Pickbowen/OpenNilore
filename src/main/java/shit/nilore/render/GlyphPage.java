package shit.nilore.render;

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
import shit.nilore.ClientBase;
import shit.nilore.render.Glyph;
import shit.nilore.utils.misc.ReflectionUtil;

class GlyphPage {
    final char startChar;
    final char endChar;
    final Font font;
    final ResourceLocation textureLocation;
    final int padding;
    private final Char2ObjectArrayMap<Glyph> glyphMap = new Char2ObjectArrayMap();
    int imageWidth;
    int imageHeight;
    boolean uploaded = false;

    public GlyphPage(char startChar, char endChar, Font font, ResourceLocation textureLocation, int padding) {
        this.startChar = startChar;
        this.endChar = endChar;
        this.font = font;
        this.textureLocation = textureLocation;
        this.padding = padding;
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
            int width = (int)Math.ceil(bounds.getWidth());
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
            graphics.drawString(String.valueOf(glyph.value()), glyph.u(), glyph.v() + fontMetrics.getAscent());
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