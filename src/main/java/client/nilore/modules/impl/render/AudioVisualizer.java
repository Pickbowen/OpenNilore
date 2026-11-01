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
 *
 *
 *
 * <ul>
 * </ul>
 */
public class AudioVisualizer extends Module {

    public static AudioVisualizer INSTANCE;

    private final ModeSetting mode = new ModeSetting("Mode", "Bars", "Only Left", "Only Right").withDefault("Bars");
    private final NumberSetting barCount = new NumberSetting("Bars", 48, 8, 128, 1);
    private final NumberSetting heightRatio = new NumberSetting("Height", 0.5, 0.05, 1.0, 0.01);
    private final NumberSetting gap = new NumberSetting("Gap", 2.0, 0.0, 8.0, 0.5);
    private final NumberSetting sensitivity = new NumberSetting("Sensitivity", 1.6, 0.2, 4.0, 0.05);
    private final NumberSetting smoothing = new NumberSetting("Smoothing", 0.95, 0.0, 0.95, 0.01);
    private final NumberSetting alpha = new NumberSetting("Alpha", 120, 30, 255, 5);
    private final BooleanSetting hideInGui = new BooleanSetting("Hide In GUI", true);

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

    private void updateLevels(float[] spectrum, int count) {
        if (this.level.length != count) {
            this.level = new float[count];
        }
        long now = System.nanoTime();
        float deltaSeconds = this.lastFrameNanos == 0L ? 1.0f / 60.0f : Math.min(0.1f, (now - this.lastFrameNanos) / 1.0E9f);
        this.lastFrameNanos = now;

        if (spectrum.length == 0) {
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
            this.level[i] = Math.max(Math.min(1.0f, sample), this.level[i] * decay);
        }
    }

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
