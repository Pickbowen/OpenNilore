package client.nilore.modules.impl.misc.music.provider;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class QrCode {

    private QrCode() {
    }

    public static RenderableQr render(String content, int size) {
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(
                    new String(content.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8),
                    BarcodeFormat.QR_CODE, size, size);
            int w = matrix.getWidth();
            int h = matrix.getHeight();
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, w, h, false);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    image.setPixelRGBA(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(
                    "nilore", "qr_" + UUID.randomUUID().toString().replace("-", ""));
            DynamicTexture texture = new DynamicTexture(image);
            texture.upload();
            return new RenderableQr(loc, texture, size, size);
        } catch (WriterException e) {
            throw new IllegalStateException("二维码生成失败", e);
        }
    }

    public record RenderableQr(ResourceLocation location, DynamicTexture texture, int width, int height) {
    }
}