package client.nilore.modules.impl.misc.music.dsp;

import java.util.Arrays;

/**
 * Automix 规划：给「正在播的 A」和「下一首 B」算一个交接方案，
 * 让切歌听起来是接上去的，而不是硬切。
 *
 * <p>要解决三件事：
 * <ol>
 *   <li><b>A 从哪儿开始退</b>——找它的自然收尾（{@code findOutroLanding}），
 *       而不是在任意时间点掐断；</li>
 *   <li><b>B 从哪儿进</b>——跳过前奏直接落进人声/主歌（{@code findIntroEnd}），
 *       并且吸附到 B 自己的小节线上；</li>
 *   <li><b>两边速度差多少</b>——把 B 的 BPM 折到 A 附近（{@code octaveNormalise}），
 *       倍率超出 ±8% 就直接放弃匹配，免得变速痕迹太明显。</li>
 * </ol>
 *
 * <p>和 deobfmusic 的实现差别只在入口：那边传 URL、内部自己 seek 读音频；
 * 这边直接吃整首单声道 PCM，因为播放器本来就把整首歌解到内存里了，省一次 IO。
 */
public final class AutomixPlanner {

    /** 分析窗口长度：12 秒。太短测不准 BPM，太长会把不同段落混在一起。 */
    private static final long ANALYSIS_WINDOW_MS = 12000L;
    /** outro 扫描窗口：90 秒。 */
    private static final long OUTRO_SCAN_MS = 90000L;
    /** intro 扫描上限：20 秒。 */
    private static final long INTRO_SCAN_MS = 20000L;
    /** tempo 允许的倍率范围，超出就不做速度匹配。 */
    private static final float MIN_RATIO = 0.92f;
    private static final float MAX_RATIO = 1.08f;
    /** 切入 B 时相对检测到的人声起点再提前一点，避开起唱的呼吸。 */
    private static final float INTRO_OFFSET_MS = 400.0f;

    private AutomixPlanner() {
    }

    /**
     * @param tempo    B 相对 A 的速度倍率，1.0 表示不变速
     * @param phaseMsA A 的交接相位（对齐到 A 的小节线）
     * @param introEndMs B 的入口（相对 bInStartMs），已经吸附到 B 的小节线
     * @param outroMsA A 从 aOutStartMs 起还要走多久才开始退
     */
    public record Plan(float tempo, float bpmA, float bpmB, float phaseMsA, float introEndMs,
                       float rmsA, float rmsB, float outroMsA,
                       KeyDetector.Key keyA, KeyDetector.Key keyB) {

        /** 分析失败时的兜底：不匹配速度、不做相位对齐，退化成普通淡入淡出。 */
        public static Plan degraded() {
            return new Plan(1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, null, null);
        }
    }

    private record Zone(BeatDetector.BeatGrid grid, float rms, KeyDetector.Key key) {
    }

    /**
     * 只算 B 的入口（跳过前奏），不需要 A 的音频。
     *
     * <p>单独暴露这一步，是因为「找入口」本身不依赖 A——
     * 预加载下一首的时候就能先算好，等真的换歌时不用再等分析。
     */
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

        // A 的退场点：有最后一句歌词的时间就用它推（歌词结束 + 1.5 秒），否则找自然收尾
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

        // B 的入口：找前奏结束
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
        // 入口吸附到 B 的小节线，这样切进去的是完整乐句
        if (zoneB.grid.bpm > 1.0f) {
            introEndMs += zoneB.grid.barPhaseMs;
        }

        float bpmA = zoneA.grid.bpm;
        float bpmB = octaveNormalise(zoneB.grid.bpm, bpmA);
        float tempo = bpmA / bpmB;
        if (tempo < MIN_RATIO || tempo > MAX_RATIO) {
            tempo = 1.0f;
        }

        // A 的相位对齐到自己的小节线，且不能跑到混音窗口之外
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
    // 区域分析
    // ------------------------------------------------------------------

    /** 从 {@code startMs} 起取 12 秒做分析；不够 0.25 秒或分析不出节拍就返回 null。 */
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

    /** 按毫秒裁一段单声道 PCM。越界返回 null。 */
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
    // 找 B 的入口
    // ------------------------------------------------------------------

    /** 相对 {@code startMs} 的结果（毫秒）；找不到返回 0，出错返回 -1。 */
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

    /**
     * 「脚步进入」检测：找一个能量明显抬起来、并且持续约 1 秒的位置。
     *
     * <p>用两级阈值——起始点要高过 40% 峰值，之后 1 秒内不能掉到 30% 以下。
     * 只抬高一下又落回去的（比如鼓点、音效）不算。
     */
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

    /**
     * 保底策略：找「能量 × 变化量」最大的时刻——也就是内容最满、同时在起变化的地方。
     * 通常是主歌或副歌真正开始的位置。
     */
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
            // 开头 1 秒和结尾 0.3 秒按比例压低，避免选到淡入淡出的位置
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
    // 找 A 的退场点
    // ------------------------------------------------------------------

    /** 相对 {@code startMs} 的落地毫秒；不够数据或出错返回 -1。 */
    private static float findOutroLanding(float[] mono, int sampleRate, long startMs) {
        float[] window = slice(mono, sampleRate, startMs, OUTRO_SCAN_MS);
        if (window == null || window.length < sampleRate / 2) {
            return -1.0f;
        }
        return detectOutroLanding(window, window.length, sampleRate);
    }

    /**
     * 找「歌快结束了」的那个位置。三级回退，越往后越保守：
     * <ol>
     *   <li>从能量峰值之后找第一段持续 1.2 秒的低谷；</li>
     *   <li>找不到就找第一段持续 0.6 秒、低于均值 55% 的安静段；</li>
     *   <li>再不行就取最后 15 秒里能量最低的那一帧。</li>
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

        // 一级：峰值之后的持续低谷
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

        // 二级：低于均值 55% 的安静段
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

        // 三级：最后 15 秒里最安静的一帧
        int quietest = Math.max(0, hops - Math.round(15.0f * sampleRate / hop));
        for (int h = quietest + 1; h < hops; h++) {
            if (rms[h] < rms[quietest]) {
                quietest = h;
            }
        }
        return quietest * hop * 1000.0f / sampleRate;
    }

    /** 把 BPM 折到参考值附近（±半个八度内），避免把 140 当成 70 或 280。 */
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
