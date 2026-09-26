package client.nilore.modules.impl.misc.music.dsp;

/**
 * 节拍检测。
 *
 * <p>给 automix 找「切在拍子上」的位置用：算出自相关最强的周期（BPM），
 * 以及第一个强拍落在哪（相位）。有了这两个数，交叉淡化的起止点就能吸附到节拍网格，
 * 而不是随便挑个时间点硬切——听感上的差别就是从"接歌"变成"混歌"。
 *
 * <p>算法是最经典的能量通量 + 自相关：分帧算 RMS，相邻帧取正向差分当 onset
 * （只有变响才算，避免衰减尾巴被当成拍），去均值后自相关，在 70~180 BPM 区间取最大峰。
 * 相位则选「落在网格上的 onset 之和最大」的那个偏移。
 */
public final class BeatDetector {

    private static final int HOP = 512;
    private static final float MIN_BPM = 70.0f;
    private static final float MAX_BPM = 180.0f;

    private BeatDetector() {
    }

    /** 一次检测的结果。时间单位统一用毫秒，和播放器、歌词那边对齐。 */
    public static final class BeatGrid {
        public final float bpm;
        /** 第一个强拍的位置（毫秒，相对曲首）。 */
        public final float phaseMs;
        /** 小节线位置（毫秒）。一小节按 4 拍算。 */
        public final float barPhaseMs;

        BeatGrid(float bpm, float phaseMs, float barPhaseMs) {
            this.bpm = bpm;
            this.phaseMs = phaseMs;
            this.barPhaseMs = barPhaseMs;
        }

        public float beatPeriodMs() {
            return this.bpm <= 0f ? 0f : 60000.0f / this.bpm;
        }

        public float barPeriodMs() {
            return this.bpm <= 0f ? 0f : 240000.0f / this.bpm;
        }

        /** 把时间点吸附到最近的强拍。 */
        public float snapToBeat(float timeMs) {
            return snap(timeMs, this.phaseMs, beatPeriodMs());
        }

        /**
         * 把时间点吸附到最近的小节线。
         *
         * <p>切歌点用这个——落在小节线上，切入的乐句才是完整的。
         */
        public float snapToBar(float timeMs) {
            return snap(timeMs, this.barPhaseMs, barPeriodMs());
        }

        private static float snap(float timeMs, float phaseMs, float periodMs) {
            if (periodMs <= 0f) {
                return timeMs;
            }
            float rel = timeMs - phaseMs;
            return phaseMs + Math.round(rel / periodMs) * periodMs;
        }
    }

    /**
     * @param monoPcm    单声道 PCM，取值 [-1, 1]
     * @param sampleRate 采样率
     * @return 检测结果；音频太短或没有明显节拍时返回 {@code null}
     */
    public static BeatGrid detect(float[] monoPcm, int sampleRate) {
        if (monoPcm == null || sampleRate <= 0) {
            return null;
        }
        int hops = monoPcm.length / HOP;
        if (hops < 16) {
            return null;
        }

        // 1) 能量通量：帧 RMS 的正向差分，只有「变响」才算 onset
        float[] onset = new float[hops];
        float prevEnergy = 0f;
        double total = 0.0;
        for (int h = 0; h < hops; h++) {
            int base = h * HOP;
            float energy = 0f;
            for (int i = 0; i < HOP; i++) {
                float s = monoPcm[base + i];
                energy += s * s;
            }
            energy = (float) Math.sqrt(energy / HOP);
            float flux = energy - prevEnergy;
            onset[h] = flux > 0f ? flux : 0f;
            prevEnergy = energy;
            total += onset[h];
        }
        if (total < 1.0e-4) {
            return null;
        }

        // 2) 去均值，自相关只关心波动
        float mean = (float) (total / hops);
        for (int h = 0; h < hops; h++) {
            onset[h] -= mean;
        }

        // 3) 自相关找周期；每慢一帧给一点补偿权重，否则容易锁到最快的那层倍频
        float hopsPerSec = (float) sampleRate / HOP;
        int minLag = Math.max(1, Math.round(hopsPerSec * 60.0f / MAX_BPM));
        int maxLag = Math.min(hops - 1, Math.round(hopsPerSec * 60.0f / MIN_BPM));
        if (maxLag <= minLag) {
            return null;
        }
        int bestLag = minLag;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double ac = 0.0;
            for (int h = lag; h < hops; h++) {
                ac += (double) onset[h] * onset[h - lag];
            }
            ac *= 1.0 + 5.0e-4 * (maxLag - lag);
            if (ac > bestScore) {
                bestScore = ac;
                bestLag = lag;
            }
        }
        float bpm = 60.0f * hopsPerSec / bestLag;

        // 4) 相位：挑让最多 onset 落在网格上的偏移
        int bestOffset = 0;
        float bestSum = Float.NEGATIVE_INFINITY;
        for (int off = 0; off < bestLag; off++) {
            float sum = 0f;
            for (int h = off; h < hops; h += bestLag) {
                sum += onset[h];
            }
            if (sum > bestSum) {
                bestSum = sum;
                bestOffset = off;
            }
        }
        float msPerHop = 512000.0f / sampleRate;
        float phaseMs = bestOffset * msPerHop;

        // 5) 小节相位：在 4 拍范围里挑更重的那一拍
        int barLag = bestLag * 4;
        if (barLag < hops) {
            float bestBarSum = Float.NEGATIVE_INFINITY;
            int bestBarOffset = bestOffset;
            int step = Math.max(1, bestLag / 4);
            for (int off = bestOffset; off < bestOffset + bestLag; off += step) {
                float sum = 0f;
                for (int h = off; h < hops; h += barLag) {
                    sum += onset[h];
                }
                if (sum > bestBarSum) {
                    bestBarSum = sum;
                    bestBarOffset = off;
                }
            }
            return new BeatGrid(bpm, phaseMs, bestBarOffset * msPerHop);
        }
        return new BeatGrid(bpm, phaseMs, phaseMs);
    }
}
