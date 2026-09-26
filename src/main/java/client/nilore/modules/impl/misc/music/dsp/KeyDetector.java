package client.nilore.modules.impl.misc.music.dsp;

/**
 * 调性检测（Krumhansl-Schmuckler 模板匹配）。
 *
 * <p>automix 拿它判断两首歌调性差多远——差太多硬接会很刺耳。
 * 流程是标准的：STFT 累计出 chroma（12 个音级），再和大小调各 12 个旋转模板做相关，
 * 最高分对应的就是主调，最高分与次高分之差当作置信度。
 */
public final class KeyDetector {

    /** Krumhansl-Kessler 大调模板。 */
    private static final double[] KS_MAJOR = {
            6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
    /** Krumhansl-Kessler 小调模板。 */
    private static final double[] KS_MINOR = {
            6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};
    private static final String[] NAMES = {
            "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    private static final int FFT_SIZE = 4096;

    private KeyDetector() {
    }

    public record Key(int root, boolean minor, double confidence) {
        public String label() {
            return NAMES[root] + (minor ? "m" : "");
        }
    }

    /** 音频不足 0.25 秒、或没有任何能量时返回 null。 */
    public static Key detect(float[] mono, int sampleRate) {
        if (mono == null || sampleRate <= 0 || mono.length < sampleRate / 4) {
            return null;
        }
        int n = FFT_SIZE;
        if (n > mono.length) {
            n = Integer.highestOneBit(mono.length);
        }
        if (n < 512) {
            return null;
        }
        int hop = n / 2;
        double[] chroma = new double[12];
        double[] re = new double[n];
        double[] im = new double[n];
        int frames = 0;

        for (int off = 0; off + n <= mono.length; off += hop) {
            for (int i = 0; i < n; i++) {
                double window = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1));
                re[i] = mono[off + i] * window;
                im[i] = 0.0;
            }
            fft(re, im);
            for (int bin = 1; bin < n / 2; bin++) {
                double mag = Math.hypot(re[bin], im[bin]);
                if (mag < 1.0e-6) {
                    continue;
                }
                double freq = (double) bin * sampleRate / n;
                // A4 = 440Hz 映射到索引 9（"A"），所以整体 +9
                double pitchClass = 12.0 * (Math.log(freq / 440.0) / Math.log(2.0)) + 9.0;
                int idx = (int) Math.round(pitchClass) % 12;
                if (idx < 0) {
                    idx += 12;
                }
                chroma[idx] += mag;
            }
            frames++;
        }
        if (frames == 0) {
            return null;
        }

        double total = 0.0;
        for (double v : chroma) {
            total += v;
        }
        if (total < 1.0e-6) {
            return null;
        }
        for (int i = 0; i < 12; i++) {
            chroma[i] /= total;
        }

        double best = Double.NEGATIVE_INFINITY;
        double second = Double.NEGATIVE_INFINITY;
        int bestRoot = 0;
        boolean bestMinor = false;
        for (int minor = 0; minor <= 1; minor++) {
            double[] profile = minor == 1 ? KS_MINOR : KS_MAJOR;
            double energy = 0.0;
            for (double v : profile) {
                energy += v * v;
            }
            double scale = 1.0 / Math.sqrt(energy);
            for (int root = 0; root < 12; root++) {
                double corr = 0.0;
                for (int i = 0; i < 12; i++) {
                    corr += chroma[i] * profile[(i + 12 - root) % 12];
                }
                corr *= scale;
                if (corr > best) {
                    second = best;
                    best = corr;
                    bestRoot = root;
                    bestMinor = minor == 1;
                } else if (corr > second) {
                    second = corr;
                }
            }
        }
        double confidence = Math.max(0.0, (best - second) / Math.max(1.0e-9, Math.abs(best)));
        return new Key(bestRoot, bestMinor, Math.min(1.0, confidence));
    }

    /** 五度圈上的距离；大小调不同额外 +2。任一为 null 时返回 -1。 */
    public static int harmonicDistance(Key a, Key b) {
        if (a == null || b == null) {
            return -1;
        }
        int posA = a.root() * 7 % 12;
        int posB = b.root() * 7 % 12;
        int d = Math.abs(posA - posB);
        if (d > 6) {
            d = 12 - d;
        }
        return d + (a.minor() == b.minor() ? 0 : 2);
    }

    /** 0（完全不搭）到 1（同调）。 */
    public static double compatibility(Key a, Key b) {
        int d = harmonicDistance(a, b);
        return d < 0 ? -1.0 : Math.max(0.0, 1.0 - d / 8.0);
    }

    /** 就地 radix-2 Cooley-Tukey。 */
    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            double wRe = Math.cos(angle);
            double wIm = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double curRe = 1.0;
                double curIm = 0.0;
                for (int j = 0; j < len / 2; j++) {
                    int a = i + j;
                    int b = i + j + len / 2;
                    double uRe = re[a];
                    double uIm = im[a];
                    double vRe = re[b] * curRe - im[b] * curIm;
                    double vIm = re[b] * curIm + im[b] * curRe;
                    re[a] = uRe + vRe;
                    im[a] = uIm + vIm;
                    re[b] = uRe - vRe;
                    im[b] = uIm - vIm;
                    double nextRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = nextRe;
                }
            }
        }
    }
}
