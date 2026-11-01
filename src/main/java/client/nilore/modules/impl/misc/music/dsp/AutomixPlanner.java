package client.nilore.modules.impl.misc.music.dsp;

import java.util.Arrays;

/**
 *
 * <ol>
 * </ol>
 *
 */
public final class AutomixPlanner {

    private static final long ANALYSIS_WINDOW_MS = 12000L;
    private static final long OUTRO_SCAN_MS = 90000L;
    private static final long INTRO_SCAN_MS = 20000L;
    private static final float MIN_RATIO = 0.92f;
    private static final float MAX_RATIO = 1.08f;
    private static final float INTRO_OFFSET_MS = 400.0f;

    private AutomixPlanner() {
    }

    public record Plan(float tempo, float bpmA, float bpmB, float phaseMsA, float introEndMs,
                       float rmsA, float rmsB, float outroMsA,
                       KeyDetector.Key keyA, KeyDetector.Key keyB) {

        public static Plan degraded() {
            return new Plan(1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, null, null);
        }
    }

    private record Zone(BeatDetector.BeatGrid grid, float rms, KeyDetector.Key key) {
    }

    public static float introEntryMs(float[] pcmB, int sampleRate) {
        if (pcmB == null || sampleRate <= 0) {
            return 0.0f;
        }
        float ms = findIntroEnd(pcmB, sampleRate, 0L, INTRO_SCAN_MS);
        return ms < 0.0f ? 0.0f : ms;
    }

    public static Plan plan(float[] pcmA, int srA, long aOutStartMs,
                            float[] pcmB, int srB, long bInStartMs, long aLastLyricEndMs) {
        if (pcmA == null || pcmB == null || srA <= 0 || srB <= 0) {
            return Plan.degraded();
        }

        float outroMs = aLastLyricEndMs > 0L
                ? Math.max(0.0f, (float) (aLastLyricEndMs - aOutStartMs) + 1500.0f)
                : findOutroLanding(pcmA, srA, aOutStartMs);
        Zone zoneA = analyzeZone(pcmA, srA, aOutStartMs + Math.max(0L, (long) outroMs));
        if (zoneA == null && outroMs > 0.0f) {
            outroMs = 0.0f;
            zoneA = analyzeZone(pcmA, srA, aOutStartMs);
        }
        if (zoneA == null) {
            return Plan.degraded();
        }

        float introEndMs = findIntroEnd(pcmB, srB, bInStartMs, INTRO_SCAN_MS);
        if (introEndMs < 0.0f) {
            introEndMs = 0.0f;
        }
        Zone zoneB = analyzeZone(pcmB, srB, bInStartMs + (long) introEndMs);
        if (zoneB == null && introEndMs > 0.0f) {
            introEndMs = 0.0f;
            zoneB = analyzeZone(pcmB, srB, bInStartMs);
        }
        if (zoneB == null) {
            return Plan.degraded();
        }
        if (zoneB.grid.bpm > 1.0f) {
            introEndMs += zoneB.grid.barPhaseMs;
        }

        float bpmA = zoneA.grid.bpm;
        float bpmB = octaveNormalise(zoneB.grid.bpm, bpmA);
        float tempo = bpmA / bpmB;
        if (tempo < MIN_RATIO || tempo > MAX_RATIO) {
            tempo = 1.0f;
        }

        float phaseA = zoneA.grid.barPhaseMs + outroMs;
        if (outroMs >= 0.0f && bpmA > 1.0f) {
            float barPeriod = zoneA.grid.barPeriodMs();
            float k = Math.round((outroMs - phaseA) / barPeriod);
            float shifted = phaseA + k * barPeriod;
            if (shifted >= 0.0f && shifted <= 12000.0f + outroMs) {
                phaseA = shifted;
            }
        }

        return new Plan(tempo, bpmA, bpmB, phaseA, introEndMs,
                zoneA.rms, zoneB.rms, outroMs, zoneA.key, zoneB.key);
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    private static Zone analyzeZone(float[] mono, int sampleRate, long startMs) {
        float[] window = slice(mono, sampleRate, startMs, ANALYSIS_WINDOW_MS);
        if (window == null || window.length < sampleRate / 4) {
            return null;
        }
        BeatDetector.BeatGrid grid = BeatDetector.detect(window, sampleRate);
        if (grid == null) {
            return null;
        }
        double sum = 0.0;
        for (float v : window) {
            sum += (double) v * v;
        }
        float rms = (float) Math.sqrt(sum / window.length);
        return new Zone(grid, rms, KeyDetector.detect(window, sampleRate));
    }

    private static float[] slice(float[] mono, int sampleRate, long startMs, long windowMs) {
        int start = (int) Math.max(0L, startMs * sampleRate / 1000L);
        if (start >= mono.length) {
            return null;
        }
        int want = (int) (sampleRate * windowMs / 1000L);
        int len = Math.min(want, mono.length - start);
        return len <= 0 ? null : Arrays.copyOfRange(mono, start, start + len);
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    private static float findIntroEnd(float[] mono, int sampleRate, long startMs, long maxScanMs) {
        if (maxScanMs <= 0L) {
            maxScanMs = INTRO_SCAN_MS;
        }
        if (maxScanMs < 1000L) {
            return 0.0f;
        }
        float[] window = slice(mono, sampleRate, startMs, maxScanMs);
        if (window == null || window.length < sampleRate) {
            return 0.0f;
        }
        return detectIntroEnd(window, window.length, sampleRate);
    }

    private static float detectIntroEnd(float[] mono, int len, int sampleRate) {
        final int hop = 512;
        int hops = len / hop;
        if (hops < 16) {
            return 0.0f;
        }
        float[] rms = new float[hops];
        float maxRms = 0.0f;
        for (int h = 0; h < hops; h++) {
            int base = h * hop;
            double sum = 0.0;
            for (int i = 0; i < hop && base + i < len; i++) {
                sum += (double) mono[base + i] * mono[base + i];
            }
            rms[h] = (float) Math.sqrt(sum / hop);
            maxRms = Math.max(maxRms, rms[h]);
        }
        if (maxRms < 0.001f) {
            return 0.0f;
        }
        float stepFloor = maxRms * 0.4f;
        float holdFloor = maxRms * 0.3f;
        int hold = Math.max(3, Math.round(1.0f * sampleRate / hop));

        for (int h = 0; h < hops - hold; h++) {
            if (rms[h] < stepFloor) {
                continue;
            }
            boolean sustained = true;
            for (int j = h; j < h + hold; j++) {
                if (rms[j] < holdFloor) {
                    sustained = false;
                    break;
                }
            }
            if (sustained) {
                float ms = h * hop * 1000.0f / sampleRate;
                return Math.max(0.0f, ms - INTRO_OFFSET_MS);
            }
        }
        return richestMoment(mono, len, sampleRate, rms);
    }

    private static float richestMoment(float[] mono, int len, int sampleRate, float[] rms) {
        final int hop = 512;
        int hops = rms.length;
        if (hops == 0) {
            return 0.0f;
        }
        float[] flux = new float[hops];
        float[] prevFrame = new float[hop];
        float maxFlux = 0.0f;
        for (int h = 0; h < hops; h++) {
            int base = h * hop;
            double sum = 0.0;
            for (int i = 0; i < hop; i++) {
                float sample = base + i < len ? mono[base + i] : 0.0f;
                sum += Math.abs(sample - prevFrame[i]);
                prevFrame[i] = sample;
            }
            flux[h] = (float) (sum / hop);
            maxFlux = Math.max(maxFlux, flux[h]);
        }
        float maxRms = 0.0f;
        for (float v : rms) {
            maxRms = Math.max(maxRms, v);
        }
        if (maxRms < 0.001f || maxFlux < 0.001f) {
            return 0.0f;
        }

        float totalSec = (float) len / sampleRate;
        float bestScore = -1.0f;
        int bestHop = 0;
        for (int h = 0; h < hops; h++) {
            float score = (rms[h] / maxRms) * (flux[h] / maxFlux);
            float timeSec = (float) (h * hop) / sampleRate;
            if (timeSec < 1.0f) {
                score *= timeSec;
            }
            if (timeSec > totalSec - 0.3f) {
                score *= (totalSec - timeSec) / 0.3f;
            }
            if (score > bestScore) {
                bestScore = score;
                bestHop = h;
            }
        }
        int offsetHop = (int) (INTRO_OFFSET_MS / 1000.0f * sampleRate / hop);
        int landingHop = Math.max(0, bestHop - offsetHop);
        float landingMs = landingHop * hop * 1000.0f / sampleRate;
        float maxMs = Math.max(500.0f, (totalSec - 0.1f) * 1000.0f);
        return Math.max(500.0f, Math.min(maxMs, landingMs));
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    private static float findOutroLanding(float[] mono, int sampleRate, long startMs) {
        float[] window = slice(mono, sampleRate, startMs, OUTRO_SCAN_MS);
        if (window == null || window.length < sampleRate / 2) {
            return -1.0f;
        }
        return detectOutroLanding(window, window.length, sampleRate);
    }

    /**
     * <ol>
     * </ol>
     */
    private static float detectOutroLanding(float[] mono, int len, int sampleRate) {
        final int hop = 1024;
        int hops = len / hop;
        if (hops < 8) {
            return -1.0f;
        }
        float[] rms = new float[hops];
        for (int h = 0; h < hops; h++) {
            int base = h * hop;
            double sum = 0.0;
            for (int i = 0; i < hop && base + i < len; i++) {
                sum += (double) mono[base + i] * mono[base + i];
            }
            rms[h] = (float) Math.sqrt(sum / hop);
        }

        int smoothWin = Math.max(3, Math.round(1.5f * sampleRate / hop));
        float envMax = 0.0f;
        int argmax = 0;
        for (int h = 0; h < hops; h++) {
            int from = Math.max(0, h - smoothWin / 2);
            int to = Math.min(hops, h + smoothWin / 2 + 1);
            double sum = 0.0;
            for (int j = from; j < to; j++) {
                sum += rms[j];
            }
            float env = (float) (sum / Math.max(1, to - from));
            if (env > envMax) {
                envMax = env;
                argmax = h;
            }
        }
        if (envMax < 1.0e-4f) {
            return -1.0f;
        }

        float crossFloor = envMax * 0.5f;
        int needLow = Math.max(3, Math.round(1.2f * sampleRate / hop));
        int outroStart = -1;
        boolean below = false;
        int belowSince = 0;
        for (int h = argmax; h < hops; h++) {
            if (rms[h] < crossFloor) {
                if (!below) {
                    below = true;
                    belowSince = h;
                }
            } else if (below) {
                if (h - belowSince >= needLow) {
                    outroStart = belowSince;
                }
                below = false;
            }
        }
        if (below && hops - belowSince >= needLow) {
            outroStart = belowSince;
        }
        if (outroStart >= 0 && outroStart < hops - 1) {
            return outroStart * hop * 1000.0f / sampleRate;
        }

        float mean = 0.0f;
        for (float v : rms) {
            mean += v;
        }
        mean /= hops;
        float quietThr = mean * 0.55f;
        int need = Math.max(1, Math.round(0.6f * sampleRate / hop));
        for (int k = hops - need; k >= 0; k--) {
            boolean quiet = true;
            for (int j = k; j < k + need; j++) {
                if (rms[j] > quietThr) {
                    quiet = false;
                    break;
                }
            }
            if (quiet) {
                return k * hop * 1000.0f / sampleRate;
            }
        }

        int quietest = Math.max(0, hops - Math.round(15.0f * sampleRate / hop));
        for (int h = quietest + 1; h < hops; h++) {
            if (rms[h] < rms[quietest]) {
                quietest = h;
            }
        }
        return quietest * hop * 1000.0f / sampleRate;
    }

    private static float octaveNormalise(float bpm, float reference) {
        if (bpm <= 0.0f || reference <= 0.0f) {
            return bpm;
        }
        float b = bpm;
        while (b < reference / 1.414f) {
            b *= 2.0f;
        }
        while (b > reference * 1.414f) {
            b /= 2.0f;
        }
        return b;
    }
}
