package shit.nilore.utils.render;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import lombok.Generated;
import net.minecraft.client.renderer.texture.DynamicTexture;
import shit.nilore.manager.ConfigManager;

public final class TextureUtil {
    private static final Map<String, DynamicTexture> textureCache = new HashMap<>();

    public static DynamicTexture loadTexture(String fileName) {
        File file = new File(ConfigManager.CONFIG_DIR, fileName);
        if (!file.exists()) {
            System.out.println("Failed to find target file!");
            return TextureUtil.getMissingTexture();
        }
        DynamicTexture dynamicTexture = textureCache.get(fileName);
        if (dynamicTexture == null) {
            try (FileInputStream fileInputStream = new FileInputStream(file)){
                NativeImage nativeImage = NativeImage.read(fileInputStream);
                dynamicTexture = new DynamicTexture(nativeImage);
                textureCache.put(fileName, dynamicTexture);
            } catch (java.io.IOException ioException) {
                return TextureUtil.getMissingTexture();
            }
        }
        return dynamicTexture;
    }

    private static DynamicTexture getMissingTexture() {
        DynamicTexture dynamicTexture = textureCache.get("__missing_texture__");
        if (dynamicTexture == null) {
            NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, 2, 2, false);
            nativeImage.setPixelRGBA(0, 0, -65281);
            nativeImage.setPixelRGBA(1, 0, -16777216);
            nativeImage.setPixelRGBA(0, 1, -16777216);
            nativeImage.setPixelRGBA(1, 1, -65281);
            dynamicTexture = new DynamicTexture(nativeImage);
            textureCache.put("__missing_texture__", dynamicTexture);
        }
        return dynamicTexture;
    }

    public static HashMap<String, String> parseQueryString(String queryString) {
        String[] pairs;
        HashMap<String, String> result = new HashMap<>();
        if (queryString == null) {
            return result;
        }
        for (String pair : pairs = queryString.split("&")) {
            int equalsIndex = pair.indexOf(61);
            if (equalsIndex != -1) {
                String key = pair.substring(0, equalsIndex);
                String value = pair.substring(equalsIndex + 1);
                result.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
                continue;
            }
            result.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
        }
        return result;
    }

    @Generated
    private TextureUtil() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    private static UnsupportedEncodingException wrapEncodingException(UnsupportedEncodingException unsupportedEncodingException) {
        return unsupportedEncodingException;
    }
}
