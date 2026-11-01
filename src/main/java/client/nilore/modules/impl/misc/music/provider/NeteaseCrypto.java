package client.nilore.modules.impl.misc.music.provider;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class NeteaseCrypto {

    private static final String PRESET_KEY = "0CoJUm6Qyw8W8jud";
    private static final byte[] IV = "0102030405060708".getBytes(StandardCharsets.UTF_8);
    private static final BigInteger RSA_EXPONENT = new BigInteger("010001", 16);
    private static final BigInteger RSA_MODULUS = new BigInteger(
            "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e41"
                    + "7629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee25593"
                    + "2575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7", 16);
    private static final char[] BASE62 =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private NeteaseCrypto() {
    }

    static String encrypt(String json) {
        String secret = randomBase62(16);
        String params = aesCbcBase64(aesCbcBase64(json, PRESET_KEY), secret);
        String encSecKey = rsaHex(secret);
        return "params=" + urlEncode(params) + "&encSecKey=" + urlEncode(encSecKey);
    }

    private static String aesCbcBase64(String plain, String key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                    new IvParameterSpec(IV));
            return Base64.getEncoder().encodeToString(
                    cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("weapi AES 加密失败", e);
        }
    }

    private static String rsaHex(String secret) {
        String reversed = new StringBuilder(secret).reverse().toString();
        BigInteger value = new BigInteger(1, reversed.getBytes(StandardCharsets.UTF_8));
        String hex = value.modPow(RSA_EXPONENT, RSA_MODULUS).toString(16);
        if (hex.length() >= 256) {
            return hex;
        }
        StringBuilder sb = new StringBuilder(256);
        for (int i = hex.length(); i < 256; i++) {
            sb.append('0');
        }
        return sb.append(hex).toString();
    }

    private static String randomBase62(int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = BASE62[RANDOM.nextInt(BASE62.length)];
        }
        return new String(out);
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String md5Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit(b >> 4 & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 失败", e);
        }
    }
}
