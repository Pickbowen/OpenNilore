package client.nilore.protocol.heypixel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 布吉岛(HeyPixel) Id1 签名的还原实现（Id1SignatureProvider 接口）。
 *
 * 算法套件：MD5 / SHA-1 / SHA-256 / SHA-512 / CRC32。映射：
 *   signString     = SHA-256
 *   digestPathLike = MD5
 */
public final class HeyPixelSignature implements Id1PacketBuilder.Id1SignatureProvider {

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String digestPathLike(String path) {
        try {
            return hex(MessageDigest.getInstance("MD5")
                    .digest(path.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String signString(String value) {
        try {
            return hex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    private static String hex(byte[] data) {
        StringBuilder s = new StringBuilder(data.length * 2);
        for (byte b : data) {
            s.append(Character.forDigit((b >> 4) & 0xF, 16));
            s.append(Character.forDigit(b & 0xF, 16));
        }
        return s.toString();
    }
}
