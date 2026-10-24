package client.nilore.modules.impl.render;

import client.nilore.event.impl.Render2DEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.misc.MusicPlayer;
import client.nilore.render.Paint;
import client.nilore.render.Renderer;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.event.EventTarget;

/**
 * 音频可视化: 屏幕下半部分的纯白色频谱。
 *
 * <p>数据来自 {@link MusicPlayer#AUDIO_PLAYER} 的实时频谱快照 —— 播放线程在把 PCM 写进
 * SourceDataLine 之前已经做过一段 1024 点的分析(32 段), 这里只是把它重采样到想要的条数、
 * 做时间平滑, 然后按屏幕下半区的高度画出来。没有播放时快照为空, 整片自动消失, 不需要
 * 额外判断播放状态。
 *
 * <p>两点实现取舍:
 *
 * <ul>
 *   <li><b>衰减按帧间隔补偿</b>: 快照大约每 23ms 更新一次, 但渲染是 60~200 帧/秒。如果按
 *       帧数做衰减, 高帧率下柱子会掉得飞快、低帧率下又发黏。所以衰减用的是
 *       {@code pow(decay, dt * 60)} —— 换算成"每 1/60 秒衰减多少", 与帧率无关。</li>
 *   <li><b>起音即时、释放平滑</b>: 只对新值取较大者, 旧的按衰减掉。这样鼓点瞬间能顶到
 *       满高度, 回落是缓的, 也就是常见的 VU 表手感。</li>
 * </ul>
 */
public class AudioVisualizer extends Module {

    public static AudioVisualizer INSTANCE;

    private final ModeSetting mode = new ModeSetting("Mode", "Bars", "Only Left", "Only Right").withDefault("Bars");
    private final NumberSetting barCount = new NumberSetting("Bars", 48, 8, 128, 1);
    /** 占据屏幕高度的比例; 0.5 = 正好下半屏。 */
    private final NumberSetting heightRatio = new NumberSetting("Height", 0.5, 0.05, 1.0, 0.01);
    private final NumberSetting gap = new NumberSetting("Gap", 2.0, 0.0, 8.0, 0.5);
    private final NumberSetting sensitivity = new NumberSetting("Sensitivity", 1.6, 0.2, 4.0, 0.05);
    private final NumberSetting smoothing = new NumberSetting("Smoothing", 0.95, 0.0, 0.95, 0.01);
    private final NumberSetting alpha = new NumberSetting("Alpha", 120, 30, 255, 5);
    private final BooleanSetting hideInGui = new BooleanSetting("Hide In GUI", true);

    /** 每根柱子的当前高度(0~1), 跨帧保留用于衰减。 */
    private float[] level = new float[0];
    private long lastFrameNanos;

    public AudioVisualizer() {
        super("AudioVisualizer", Category.RENDER);
        INSTANCE = this;
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (mc == null || mc.player == null || mc.getWindow() == null) {
            return;
        }
        if (this.hideInGui.getValue() && mc.screen != null) {
            return;
        }

        int count = this.barCount.getValue().intValue();
        float[] spectrum = MusicPlayer.AUDIO_PLAYER.getSpectrumSnapshot();
        this.updateLevels(spectrum, count);
        if (this.level.length == 0) {
            return;
        }

        float screenWidth = mc.getWindow().getGuiScaledWidth();
        float screenHeight = mc.getWindow().getGuiScaledHeight();
        float areaHeight = screenHeight * this.heightRatio.getValue().floatValue();

        // 横向范围: Bars 占满整屏, Only Left / Only Right 只占一半屏幕。
        // 竖直方向两种模式完全一致 —— 都从屏幕底边向上生长。
        float areaLeft;
        float areaWidth;
        if (this.mode.is("Only Left")) {
            areaLeft = 0.0f;
            areaWidth = screenWidth * 0.5f;
        } else if (this.mode.is("Only Right")) {
            areaLeft = screenWidth * 0.5f;
            areaWidth = screenWidth * 0.5f;
        } else {
            areaLeft = 0.0f;
            areaWidth = screenWidth;
        }

        float slot = areaWidth / count;
        float barWidth = Math.max(1.0f, slot - this.gap.getValue().floatValue());
        int color = (this.alpha.getValue().intValue() << 24) | 0xFFFFFF;
        // Only Left / Only Right: 在这半边内叠一条从右到左递减的高度包络。
        // 两个模式的方向相同(都是右高左低) —— 左半边最高点落在屏幕中线, 右半边最高点落在屏幕右缘。
        boolean shaped = this.mode.is("Only Left") || this.mode.is("Only Right");

        float[] levelSnapshot = this.level;
        Renderer.render(event.guiGraphics(), drawContext -> {
            try (Paint paint = new Paint()) {
                paint.setColor(color);
                for (int i = 0; i < levelSnapshot.length; i++) {
                    float value = levelSnapshot[i];
                    if (value <= 0.002f) {
                        continue;
                    }
                    float weight = 1.0f;
                    if (shaped) {
                        // t: 0 = 区域最左侧, 1 = 区域最右侧; 包络 1.0 -> 0.1 线性递减。
                        float t = count <= 1 ? 0.0f : i / (float) (count - 1);
                        weight = 1.0f - 0.9f * t;
                    }
                    float barHeight = value * areaHeight * weight;
                    if (barHeight <= 0.5f) {
                        continue;
                    }
                    float x = areaLeft + i * slot + (slot - barWidth) * 0.5f;
                    drawContext.drawRectXYWH(x, screenHeight - barHeight, barWidth, barHeight, paint);
                }
            }
        });
    }

    /**
     * 把快照重采样到 {@code count} 根柱子并做时间平滑。
     *
     * <p>频谱越高越弱是音乐本身的特性(能量集中在低频), 所以对高频段乘一个 {@code 1 + 0.6t}
     * 的增益, 否则右半屏几乎全是空的。
     */
    private void updateLevels(float[] spectrum, int count) {
        if (this.level.length != count) {
            this.level = new float[count];
        }
        long now = System.nanoTime();
        float deltaSeconds = this.lastFrameNanos == 0L ? 1.0f / 60.0f : Math.min(0.1f, (now - this.lastFrameNanos) / 1.0E9f);
        this.lastFrameNanos = now;

        if (spectrum.length == 0) {
            // 停止播放: 只保留衰减, 让画面自然收掉, 而不是"啪"地消失。
            float fade = (float) Math.pow(this.smoothing.getValue().doubleValue(), deltaSeconds * 60.0);
            for (int i = 0; i < count; i++) {
                this.level[i] *= fade;
                if (this.level[i] < 0.002f) {
                    this.level[i] = 0.0f;
                }
            }
            return;
        }

        float gain = this.sensitivity.getValue().floatValue();
        float decay = (float) Math.pow(this.smoothing.getValue().doubleValue(), deltaSeconds * 60.0);
        for (int i = 0; i < count; i++) {
            float t = count <= 1 ? 0.0f : i / (float) (count - 1);
            float sample = sample(spectrum, t) * gain * (1.0f + 0.6f * t);
            // 起音即时(取较大者), 释放按衰减走 —— 鼓点能顶满, 回落是缓的。
            this.level[i] = Math.max(Math.min(1.0f, sample), this.level[i] * decay);
        }
    }

    /** 在 32 段快照上按位置 {@code t ∈ [0,1]} 做线性插值。 */
    private static float sample(float[] spectrum, float t) {
        if (spectrum.length == 1) {
            return spectrum[0];
        }
        float position = t * (spectrum.length - 1);
        int index = (int) position;
        int next = Math.min(index + 1, spectrum.length - 1);
        float fraction = position - index;
        return spectrum[index] * (1.0f - fraction) + spectrum[next] * fraction;
    }

    @Override
    protected void onDisable() {
        this.level = new float[0];
        this.lastFrameNanos = 0L;
        super.onDisable();
    }
}
