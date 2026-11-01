package client.nilore.utils.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.util.FastColor;

public final class MonetPalette {

    private static final int HUE_BUCKETS = 30;

    private static final int GAMUT_STEPS = 20;

    private static final float MIN_CHROMA = 20.0f;

    private static final float NEUTRAL_CHROMA = 4.0f;
    private static final float NEUTRAL_VARIANT_CHROMA = 8.0f;

    private MonetPalette() {
    }

    public record Lch(float l, float c, float h) {
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    public static final class TonalPalette {
        private final float hue;
        private final float chroma;

        public TonalPalette(float hue, float chroma) {
            this.hue = normalizeHue(hue);
            this.chroma = Math.max(0.0f, chroma);
        }

        public float hue() {
            return this.hue;
        }

        public float chroma() {
            return this.chroma;
        }

        public int at(float tone) {
            float l = clamp(tone, 0.0f, 100.0f);
            float low = 0.0f;
            float high = this.chroma;
            for (int i = 0; i < GAMUT_STEPS; i++) {
                float mid = (low + high) * 0.5f;
                if (inGamut(l, mid, this.hue)) {
                    low = mid;
                } else {
                    high = mid;
                }
            }
            return lchToArgb(l, low, this.hue);
        }
    }

    public static final class Scheme {
        public final TonalPalette primary;
        public final TonalPalette secondary;
        public final TonalPalette tertiary;
        public final TonalPalette neutral;
        public final TonalPalette neutralVariant;

        public Scheme(TonalPalette primary, TonalPalette secondary, TonalPalette tertiary,
                      TonalPalette neutral, TonalPalette neutralVariant) {
            this.primary = primary;
            this.secondary = secondary;
            this.tertiary = tertiary;
            this.neutral = neutral;
            this.neutralVariant = neutralVariant;
        }

        public int primary() { return this.primary.at(80); }
        public int primaryContainer() { return this.primary.at(30); }
        public int primaryBright() { return this.primary.at(90); }

        public int secondaryContainer() { return this.secondary.at(30); }
        public int secondaryBright() { return this.secondary.at(90); }

        public int tertiaryContainer() { return this.tertiary.at(30); }
        public int tertiaryBright() { return this.tertiary.at(90); }

        public int surfaceLowest() { return this.neutral.at(5); }
        public int surfaceContainer() { return this.neutral.at(11); }
        public int surfaceHigh() { return this.neutral.at(16); }
        public int surfaceHighest() { return this.neutral.at(21); }

        public int onSurface() { return this.neutral.at(92); }
        public int onSurfaceVariant() { return this.neutralVariant.at(80); }
        public int outline() { return this.neutralVariant.at(60); }
        public int outlineVariant() { return this.neutralVariant.at(30); }
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    public static Scheme fromImage(NativeImage image) {
        if (image == null) {
            return fallback();
        }
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0) {
            return fallback();
        }
        int step = Math.max(1, Math.min(width, height) / 48);

        float[] hueWeighted = new float[HUE_BUCKETS];
        float[] chromaWeighted = new float[HUE_BUCKETS];
        float[] totalWeight = new float[HUE_BUCKETS];

        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                int abgr = image.getPixelRGBA(x, y);
                if (FastColor.ABGR32.alpha(abgr) < 128) {
                    continue;
                }
                Lch lch = srgbToLch(
                        FastColor.ABGR32.red(abgr),
                        FastColor.ABGR32.green(abgr),
                        FastColor.ABGR32.blue(abgr));
                if (lch.l() < 15.0f || lch.l() > 95.0f || lch.c() < 5.0f) {
                    continue;
                }
                int bucket = (int) (lch.h() / 360.0f * HUE_BUCKETS) % HUE_BUCKETS;
                float weight = lch.c() * (lch.l() / 100.0f);
                hueWeighted[bucket] += lch.h() * weight;
                chromaWeighted[bucket] += lch.c() * weight;
                totalWeight[bucket] += weight;
            }
        }

        int best = -1;
        float bestScore = 0.0f;
        for (int i = 0; i < HUE_BUCKETS; i++) {
            if (totalWeight[i] <= 0.0f) {
                continue;
            }
            float averageChroma = chromaWeighted[i] / totalWeight[i];
            float score = totalWeight[i] * (0.8f + 0.2f * Math.min(1.0f, averageChroma / 40.0f));
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best < 0 || bestScore < 0.1f) {
            return fallback();
        }

        float hue = hueWeighted[best] / totalWeight[best];
        float chroma = Math.max(MIN_CHROMA, chromaWeighted[best] / totalWeight[best]);
        return fromSource(hue, chroma);
    }

    public static Scheme fromSource(float hue, float chroma) {
        return new Scheme(
                new TonalPalette(hue, chroma),
                new TonalPalette(hue + 30.0f, chroma * 0.8f),
                new TonalPalette(hue + 60.0f, chroma * 0.6f),
                new TonalPalette(hue, NEUTRAL_CHROMA),
                new TonalPalette(hue, NEUTRAL_VARIANT_CHROMA));
    }

    public static Scheme fallback() {
        return fromSource(250.0f, 45.0f);
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    public static Scheme lerp(Scheme a, Scheme b, float t) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        float p = clamp(t, 0.0f, 1.0f);
        return new Scheme(
                lerpPalette(a.primary, b.primary, p),
                lerpPalette(a.secondary, b.secondary, p),
                lerpPalette(a.tertiary, b.tertiary, p),
                lerpPalette(a.neutral, b.neutral, p),
                lerpPalette(a.neutralVariant, b.neutralVariant, p));
    }

    private static TonalPalette lerpPalette(TonalPalette a, TonalPalette b, float t) {
        return new TonalPalette(
                lerpHue(a.hue(), b.hue(), t),
                a.chroma() + (b.chroma() - a.chroma()) * t);
    }

    private static float lerpHue(float a, float b, float t) {
        float delta = ((b - a) % 360.0f + 540.0f) % 360.0f - 180.0f;
        return normalizeHue(a + delta * t);
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    private static Lch srgbToLch(int r, int g, int b) {
        float lr = toLinear(r / 255.0f);
        float lg = toLinear(g / 255.0f);
        float lb = toLinear(b / 255.0f);

        // linear sRGB → XYZ (D65)
        float x = (0.4124f * lr + 0.3576f * lg + 0.1805f * lb) / 0.95047f;
        float y = (0.2126f * lr + 0.7152f * lg + 0.0722f * lb);
        float z = (0.0193f * lr + 0.1192f * lg + 0.9505f * lb) / 1.08883f;

        float fx = labF(x);
        float fy = labF(y);
        float fz = labF(z);

        float l = 116.0f * fy - 16.0f;
        float la = 500.0f * (fx - fy);
        float lbv = 200.0f * (fy - fz);

        float chroma = (float) Math.sqrt(la * la + lbv * lbv);
        float hue = (float) Math.toDegrees(Math.atan2(lbv, la));
        return new Lch(clamp(l, 0.0f, 100.0f), chroma, normalizeHue(hue));
    }

    private static int lchToArgb(float l, float chroma, float hue) {
        float radians = (float) Math.toRadians(hue);
        float la = (float) (chroma * Math.cos(radians));
        float lb = (float) (chroma * Math.sin(radians));

        float fy = (l + 16.0f) / 116.0f;
        float fx = fy + la / 500.0f;
        float fz = fy - lb / 200.0f;

        float x = 0.95047f * labFInv(fx);
        float y = labFInv(fy);
        float z = 1.08883f * labFInv(fz);

        float lr = 3.2406f * x - 1.5372f * y - 0.4986f * z;
        float lg = -0.9689f * x + 1.8758f * y + 0.0415f * z;
        float lb2 = 0.0557f * x - 0.2040f * y + 1.0570f * z;

        int r = toByte(toSrgb(lr));
        int g = toByte(toSrgb(lg));
        int b = toByte(toSrgb(lb2));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static boolean inGamut(float l, float chroma, float hue) {
        float radians = (float) Math.toRadians(hue);
        float la = (float) (chroma * Math.cos(radians));
        float lb = (float) (chroma * Math.sin(radians));

        float fy = (l + 16.0f) / 116.0f;
        float fx = fy + la / 500.0f;
        float fz = fy - lb / 200.0f;

        float x = 0.95047f * labFInv(fx);
        float y = labFInv(fy);
        float z = 1.08883f * labFInv(fz);

        float lr = 3.2406f * x - 1.5372f * y - 0.4986f * z;
        float lg = -0.9689f * x + 1.8758f * y + 0.0415f * z;
        float lb2 = 0.0557f * x - 0.2040f * y + 1.0570f * z;

        if (Float.isNaN(lr) || Float.isNaN(lg) || Float.isNaN(lb2)) {
            return false;
        }
        float eps = 1.0E-4f;
        return lr >= -eps && lr <= 1.0f + eps
                && lg >= -eps && lg <= 1.0f + eps
                && lb2 >= -eps && lb2 <= 1.0f + eps;
    }

    private static float toLinear(float c) {
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4);
    }

    private static float toSrgb(float c) {
        return c <= 0.0031308f ? c * 12.92f : (float) (1.055 * Math.pow(c, 1.0 / 2.4) - 0.055);
    }

    private static float labF(float t) {
        return t > 0.008856f ? (float) Math.cbrt(t) : 7.787f * t + 16.0f / 116.0f;
    }

    private static float labFInv(float t) {
        float cube = t * t * t;
        return cube > 0.008856f ? cube : (t - 16.0f / 116.0f) / 7.787f;
    }

    private static int toByte(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255.0f)));
    }

    private static float normalizeHue(float hue) {
        float h = hue % 360.0f;
        return h < 0.0f ? h + 360.0f : h;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    public static int withAlphaOf(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha & 0xFF) << 24;
    }
}
