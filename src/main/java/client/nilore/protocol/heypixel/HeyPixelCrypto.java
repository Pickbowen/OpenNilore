package client.nilore.protocol.heypixel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 布吉岛(HeyPixel) Id1 挑战应答加密 —— Id1CryptoTransform 完整实现。
 *
 * 算法 AES/GCM/NoPadding；密钥 AES-128 = MD5(keyString) hex 子串 [8:24] 的
 * 16 个 ASCII 字节；IV = keyString 的字节。keyString 已验证为挑战的
 * challengeValue 字段。
 */
public final class HeyPixelCrypto implements Id1PacketBuilder.Id1CryptoTransform {

    private volatile String keyString;

    public HeyPixelCrypto(String keyString) {
        this.keyString = keyString;
    }

    public void setKeyString(String keyString) {
        this.keyString = keyString;
    }

    @Override
    public boolean available() {
        return keyString != null && !keyString.isEmpty();
    }

    @Override
    public byte[] transform(byte[] preCrypto) {
        try {
            String keyHex = md5Hex(keyString.getBytes(StandardCharsets.UTF_8)).substring(8, 24);
            SecretKeySpec key = new SecretKeySpec(keyHex.getBytes(StandardCharsets.US_ASCII), "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key,
                    new IvParameterSpec(keyString.getBytes(StandardCharsets.UTF_8)));
            return cipher.doFinal(preCrypto);
        } catch (Exception e) {
            throw new IllegalStateException("ID1 crypto failed", e);
        }
    }

    private static String md5Hex(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(data);
        StringBuilder s = new StringBuilder(d.length * 2);
        for (byte b : d) {
            s.append(Character.forDigit((b >> 4) & 0xF, 16));
            s.append(Character.forDigit(b & 0xF, 16));
        }
        return s.toString();
    }
}
