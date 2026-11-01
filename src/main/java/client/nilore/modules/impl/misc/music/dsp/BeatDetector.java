package client.nilore.modules.impl.misc.music.dsp;

public final class BeatDetector {

    private static final int HOP = 512;
    private static final float MIN_BPM = 70.0f;
    private static final float MAX_BPM = 180.0f;

    private BeatDetector() {
    }

    public static final class BeatGrid {
        public final float bpm;
        public final float phaseMs;
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

        public float snapToBeat(float timeMs) {
            return snap(timeMs, this.phaseMs, beatPeriodMs());
        }

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

    public static BeatGrid detect(float[] monoPcm, int sampleRate) {
        if (monoPcm == null || sampleRate <= 0) {
            return null;
        }
        int hops = monoPcm.length / HOP;
        if (hops < 16) {
            return null;
        }

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

        float mean = (float) (total / hops);
        for (int h = 0; h < hops; h++) {
            onset[h] -= mean;
        }

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
