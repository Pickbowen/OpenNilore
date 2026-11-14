package client.nilore.modules.impl.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.GlRenderEvent;
import client.nilore.event.impl.RenderEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.misc.MusicPlayer;
import client.nilore.modules.impl.misc.music.AudioPlayer;
import client.nilore.modules.impl.misc.music.LyricLine;
import client.nilore.modules.impl.misc.music.NeteaseApi;
import client.nilore.modules.impl.misc.music.SongInfo;
import client.nilore.render.CustomFont;
import client.nilore.render.DrawContext;
import client.nilore.render.FontPresets;
import client.nilore.render.FontRenderer;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.render.GlHelper;
import client.nilore.render.Rectangle;
import client.nilore.utils.animation.SmoothAnimationTimer;
import client.nilore.utils.math.Easings;
import client.nilore.utils.render.ColorUtil;

public class LyricsModule extends Module {
    private volatile List<LyricLine> lyrics = Collections.emptyList();
    private long lyricsSongId = -1;
    private int currentLyricIndex = -1;
    private final SmoothAnimationTimer scrollAnim = new SmoothAnimationTimer();
    private final SmoothAnimationTimer alphaAnim = new SmoothAnimationTimer();
    private float alpha = 0;

    // Warmup pool: lyrics waiting to be glyph-cached
    private volatile List<LyricLine> warmupPool = Collections.emptyList();
    private long warmupSongId = -1;
    private int warmupIndex = 0;
    private static final int WARMUP_BATCH_SIZE = 1;

    private final NumberSetting yOffset = new NumberSetting("Y Offset", 131, -200, 200, 1);
    private final NumberSetting fontOffset = new NumberSetting("Font Offset", 2, -2, 2, 1);
    private final ModeSetting display = new ModeSetting("Display", "2D", "3D");
    private final NumberSetting worldDistance = new NumberSetting("3D Distance", 12, 4, 32, 1,
            () -> this.is3D());
    private final NumberSetting worldScale = new NumberSetting("3D Scale", 1.0f, 0.3f, 3.0f, 0.05f,
            () -> this.is3D());
    private final BooleanSetting worldThroughWalls = new BooleanSetting("3D Through Walls", false,
            () -> this.is3D());

    private static final float PANEL_H = 130;
    private static final float LINE_SPACING = 22;

    /** 每行随机散开的角度(度), 对齐参考实现 Lyrics3DSettings.spread 的默认 20。 */
    private static final float WORLD_SPREAD = 20.0f;
    /** 沿相机"上"方向的整体抬升(方块), 对齐参考实现 offsetY 默认 0.4, 免得贴着十字线。 */
    private static final double WORLD_OFFSET_Y = 0.4;
    /** 距离 12 格时每个字体像素对应的方块数, 与距离成正比, 视觉大小才不随距离变。 */
    private static final float WORLD_BASE_SCALE = 0.032f;
    private static final float WORLD_FADE_MS = 260.0f;
    private static final double WORLD_GROUND_MARGIN = 0.75;
    /** 一行最多摆成这么宽(字体像素), 超了就整体缩, 免得长句顶到屏幕外。 */
    private static final float WORLD_MAX_WIDTH = 340.0f;
    /** 词刚出现时的弹跳: 从放大 1.45 倍、上浮 5px 收回到正常。 */
    private static final float POP_MS = 160.0f;
    private static final float POP_LIFT = 5.0f;
    private static final float POP_SCALE = 0.45f;
    /** 泛光: 比正文略大的一圈加色白, 对齐参考实现 glow 那一帧。 */
    private static final float GLOW_SCALE = 1.05f;

    private final Random worldRandom = new Random();
    private int worldLineIndex = -1;
    private float worldLineAlpha;
    private boolean worldPlaced;
    private double worldX;
    private double worldY;
    private double worldZ;
    private float worldSpreadYaw;
    private float worldSpreadPitch;
    private long worldLastNanos;
    /** 逐词状态(字符区间 + 出现时刻), 每帧复用, 不做每帧分配。 */
    private int[] wordStart = new int[32];
    private int[] wordEnd = new int[32];
    private long[] wordAppear = new long[32];
    private int wordCount;

    private final FontRenderer currentFont = FontPresets.pingfang(21);
    private final FontRenderer nearFont = FontPresets.pingfang(18);

    public LyricsModule() {
        super("Lyrics", Category.RENDER);
    }

    @EventTarget
    public void onGlRender(GlRenderEvent event) {
        AudioPlayer player = MusicPlayer.AUDIO_PLAYER;
        SongInfo song = player.getCurrentSong();
        boolean playing = song != null && player.getState() != AudioPlayer.State.STOPPED;

        // Process warmup pool: glyph-cache characters invisibly
        if (!warmupPool.isEmpty()) {
            int end = Math.min(warmupIndex + WARMUP_BATCH_SIZE, warmupPool.size());
            for (int i = warmupIndex; i < end; i++) {
                String text = warmupPool.get(i).text();
                // Use getStringWidth to trigger glyph building (lighter than drawText)
                GlHelper.getStringWidth(text, currentFont);
                GlHelper.getStringWidth(text, nearFont);
            }
            warmupIndex = end;
            if (warmupIndex >= warmupPool.size()) {
                // All warmed up — switch to visible lyrics
                lyrics = warmupPool;
                lyricsSongId = warmupSongId;
                currentLyricIndex = -1;
                scrollAnim.animate(0, 0);
                warmupPool = Collections.emptyList();
            }
        }

        List<LyricLine> snapshot = lyrics;

        alphaAnim.animate(playing && !snapshot.isEmpty() ? 1.0 : 0.0, 0.4, Easings.EASE_OUT_QUAD);
        alphaAnim.tick();
        alpha = alphaAnim.getValueF();
        if (alpha < 0.01f) return;

        if (song != null) loadLyricsIfNeeded(song.id);

        if (this.is3D()) {
            return;
        }

        DrawContext ctx = event.drawContext();
        if (ctx == null) return;

        float screenW = mc.getWindow().getGuiScaledWidth();
        float screenH = mc.getWindow().getGuiScaledHeight();
        float centerX = screenW / 2f;
        float panelCenterY = screenH / 2f + yOffset.getValue().floatValue() + fontOffset.getValue().floatValue();
        float panelTop = panelCenterY - PANEL_H / 2f;

        ctx.save();
        ctx.clipRect(Rectangle.ofXYWH(0, panelTop, screenW, PANEL_H), true);

        long currentMs = player.getCurrentPositionMs();
        int newIdx = findLyricIndex(currentMs, snapshot);
        if (newIdx != currentLyricIndex) {
            currentLyricIndex = newIdx;
            if (currentLyricIndex >= 0) {
                scrollAnim.animate(currentLyricIndex * LINE_SPACING, 0.45, Easings.EASE_OUT_QUAD);
            }
        }
        scrollAnim.tick();
        float scrollY = scrollAnim.getValueF();

        // Render previous, current, next, and next-next lines (4 lines total)
        if (currentLyricIndex >= 0 && currentLyricIndex < snapshot.size()) {
            // Previous line
            int prevIdx = currentLyricIndex - 1;
            if (prevIdx >= 0) {
                String prevText = snapshot.get(prevIdx).text();
                float prevLineY = panelCenterY + (prevIdx * LINE_SPACING) - scrollY;
                float prevTw = GlHelper.getStringWidth(prevText, nearFont);
                float prevTextX = centerX - prevTw / 2f;
                float prevBaseline = prevLineY - GlHelper.getFontAscent(nearFont) / 2f;
                GlHelper.drawText(prevText, prevTextX, prevBaseline, nearFont,
                        ColorUtil.fromARGB(255, 255, 255, (int)(80 * alpha)));
            }

            // Current line
            String currentText = snapshot.get(currentLyricIndex).text();
            float currentLineY = panelCenterY + (currentLyricIndex * LINE_SPACING) - scrollY;
            float currentTw = GlHelper.getStringWidth(currentText, currentFont);
            float currentTextX = centerX - currentTw / 2f;
            float currentBaseline = currentLineY - GlHelper.getFontAscent(currentFont) / 2f;
            GlHelper.drawText(currentText, currentTextX, currentBaseline, currentFont,
                    ColorUtil.fromARGB(255, 255, 255, (int)(255 * alpha)));

            // Next line
            int nextIdx = currentLyricIndex + 1;
            if (nextIdx < snapshot.size()) {
                String nextText = snapshot.get(nextIdx).text();
                float nextLineY = panelCenterY + (nextIdx * LINE_SPACING) - scrollY;
                float nextTw = GlHelper.getStringWidth(nextText, nearFont);
                float nextTextX = centerX - nextTw / 2f;
                float nextBaseline = nextLineY - GlHelper.getFontAscent(nearFont) / 2f;
                GlHelper.drawText(nextText, nextTextX, nextBaseline, nearFont,
                        ColorUtil.fromARGB(255, 255, 255, (int)(130 * alpha)));
            }

            // Next-next line
            int nextNextIdx = currentLyricIndex + 2;
            if (nextNextIdx < snapshot.size()) {
                String nextNextText = snapshot.get(nextNextIdx).text();
                float nextNextLineY = panelCenterY + (nextNextIdx * LINE_SPACING) - scrollY;
                float nextNextTw = GlHelper.getStringWidth(nextNextText, nearFont);
                float nextNextTextX = centerX - nextNextTw / 2f;
                float nextNextBaseline = nextNextLineY - GlHelper.getFontAscent(nearFont) / 2f;
                GlHelper.drawText(nextNextText, nextNextTextX, nextNextBaseline, nearFont,
                        ColorUtil.fromARGB(255, 255, 255, (int)(60 * alpha)));
            }
        }

        ctx.restore();
    }

    /**
     * 3D 模式: 当前这一行歌词作为面向相机的 billboard 浮在视线前方。
     * 布局对齐 NeoRaven 的 WorldLyrics —— 每行带随机角偏移, 平滑跟随视角, 换行时淡入,
     * 按词弹跳出现, 正文统一白色并带一圈加色泛光。
     */
    @EventTarget
    public void onRender(RenderEvent event) {
        AudioPlayer player = MusicPlayer.AUDIO_PLAYER;
        List<LyricLine> snapshot = lyrics;
        if (!this.is3D() || player == null || mc.player == null || mc.gameRenderer == null
                || snapshot.isEmpty() || this.alpha < 0.01f
                || player.getState() == AudioPlayer.State.STOPPED) {
            this.resetWorldLine();
            return;
        }
        long position = player.getCurrentPositionMs();
        int index = this.findLyricIndex(position, snapshot);
        if (index < 0) {
            this.resetWorldLine();
            return;
        }
        LyricLine line = snapshot.get(index);
        String text = line.text();
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        long now = System.nanoTime();
        float deltaMs = this.worldLastNanos == 0L
                ? 16.0f : Math.min((now - this.worldLastNanos) / 1_000_000.0f, 100.0f);
        this.worldLastNanos = now;

        if (index != this.worldLineIndex) {
            this.worldLineIndex = index;
            this.worldLineAlpha = 0.0f;
            this.worldPlaced = false;
            this.worldSpreadYaw = (this.worldRandom.nextFloat() * 2.0f - 1.0f) * WORLD_SPREAD;
            this.worldSpreadPitch = (this.worldRandom.nextFloat() * 2.0f - 1.0f) * WORLD_SPREAD * 0.35f;
        }
        this.worldLineAlpha = Math.min(1.0f, this.worldLineAlpha + deltaMs / WORLD_FADE_MS);

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 eye = camera.getPosition();
        this.followCamera(camera.getLookVector(), eye, deltaMs);

        float alpha = Mth.clamp(this.worldLineAlpha * this.alpha, 0.0f, 1.0f);
        if (alpha <= 0.01f) {
            return;
        }
        long lineEnd = index + 1 < snapshot.size()
                ? snapshot.get(index + 1).timeMs() : line.timeMs() + 4000L;
        float pixels = WORLD_BASE_SCALE * this.worldScale.getValue().floatValue()
                * (this.worldDistance.getValue().floatValue() / 12.0f);
        PoseStack poseStack = event.poseStack();
        poseStack.pushPose();
        poseStack.translate(this.worldX - eye.x, this.worldY - eye.y, this.worldZ - eye.z);
        poseStack.mulPose(camera.rotation());
        poseStack.scale(-pixels, -pixels, pixels);
        boolean throughWalls = this.worldThroughWalls.getValue();
        if (throughWalls) {
            RenderSystem.disableDepthTest();
        }
        RenderSystem.depthMask(false);
        try {
            this.drawWorldText(poseStack, line, text, position, alpha, lineEnd);
        } finally {
            RenderSystem.depthMask(true);
            if (throughWalls) {
                RenderSystem.enableDepthTest();
            }
            poseStack.popPose();
        }
    }

    private void followCamera(Vector3f look, Vec3 eye, float deltaMs) {
        double distance = this.worldDistance.getValue().floatValue();
        double yawOffset = Math.toRadians(this.worldSpreadYaw);
        double cos = Math.cos(yawOffset);
        double sin = Math.sin(yawOffset);
        double dx = look.x() * cos + look.z() * sin;
        double dz = -look.x() * sin + look.z() * cos;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double elevation = Math.atan2(look.y(), horizontal) + Math.toRadians(this.worldSpreadPitch);
        double cosPitch = Math.cos(elevation);
        double sinPitch = Math.sin(elevation);
        double hx = horizontal > 1.0E-6 ? dx / horizontal : 0.0;
        double hz = horizontal > 1.0E-6 ? dz / horizontal : 1.0;
        // 相机"上"方向(与 look 垂直且指向上方), 参考实现用它把整行抬一点
        double lookHorizontal = Math.sqrt(look.x() * look.x() + look.z() * look.z());
        double upX = -look.y() * look.x() / Math.max(1.0E-6, lookHorizontal);
        double upZ = -look.y() * look.z() / Math.max(1.0E-6, lookHorizontal);
        double targetX = eye.x + hx * cosPitch * distance + upX * WORLD_OFFSET_Y;
        double targetY = Math.max(eye.y + sinPitch * distance + lookHorizontal * WORLD_OFFSET_Y,
                eye.y - WORLD_GROUND_MARGIN);
        double targetZ = eye.z + hz * cosPitch * distance + upZ * WORLD_OFFSET_Y;
        if (!this.worldPlaced) {
            this.worldX = targetX;
            this.worldY = targetY;
            this.worldZ = targetZ;
            this.worldPlaced = true;
            return;
        }
        float k = 1.0f - (float) Math.exp(-deltaMs / 90.0f);
        this.worldX += (targetX - this.worldX) * k;
        this.worldY += (targetY - this.worldY) * k;
        this.worldZ += (targetZ - this.worldZ) * k;
    }

    private void drawWorldText(PoseStack poseStack, LyricLine line, String text, long position, float alpha,
                               long lineEnd) {
        CustomFont font = this.currentFont.getFont();
        if (font == null) {
            return;
        }
        float width = GlHelper.getStringWidth(text, this.currentFont);
        if (width > WORLD_MAX_WIDTH) {
            float fit = WORLD_MAX_WIDTH / width;
            poseStack.scale(fit, fit, 1.0f);
        }
        this.fillWordState(line, text, position, lineEnd);
        float baseline = GlHelper.getFontAscent(this.currentFont) * 0.5f;
        float left = -width / 2.0f;
        long elapsed = position - line.timeMs();
        for (int w = 0; w < this.wordCount; ++w) {
            int start = this.wordStart[w];
            int end = this.wordEnd[w];
            String word = text.substring(start, end);
            float wordX = left + GlHelper.getStringWidth(text.substring(0, start), this.currentFont);
            float wordWidth = GlHelper.getStringWidth(word, this.currentFont);
            float appear = 1.0f;
            long appearAt = this.wordAppear[w];
            if (appearAt > 0L) {
                appear = Mth.clamp((elapsed - appearAt) / POP_MS, 0.0f, 1.0f);
                appear = appear * appear * (3.0f - 2.0f * appear);
            }
            if (appear <= 0.0f) {
                continue;
            }
            float pop = 1.0f + POP_SCALE * (1.0f - appear);
            float centerX = wordX + wordWidth * 0.5f;
            poseStack.pushPose();
            poseStack.translate(centerX, -(1.0f - appear) * POP_LIFT, 0.0f);
            poseStack.scale(pop, pop, 1.0f);
            poseStack.translate(-centerX, 0.0f, 0.0f);
            font.drawString(poseStack, word, wordX + 1.5f, baseline + 1.5f,
                    ColorUtil.withAlpha(0x000000, alpha * appear * 0.45f));
            if (alpha > 0.02f) {
                poseStack.pushPose();
                poseStack.translate(centerX, 0.0f, 0.0f);
                poseStack.scale(GLOW_SCALE, GLOW_SCALE, 1.0f);
                poseStack.translate(-centerX, 0.0f, 0.0f);
                font.drawStringAdditive(poseStack, word, wordX, baseline,
                        ColorUtil.withAlpha(0xFFFFFF, alpha * appear * 0.22f));
                poseStack.popPose();
            }
            font.drawString(poseStack, word, wordX, baseline, ColorUtil.withAlpha(0xFFFFFF, alpha * appear));
            poseStack.popPose();
        }
    }

    /** 一行拆成词: 有逐字时间轴就用它, 没有就按空白切, 出现时刻沿本行铺开。 */
    private void fillWordState(LyricLine line, String text, long position, long lineEnd) {
        int length = text.length();
        if (this.wordStart.length < length) {
            this.wordStart = new int[length];
            this.wordEnd = new int[length];
            this.wordAppear = new long[length];
        }
        this.wordCount = 0;
        if (line.hasWordTiming()) {
            int cursor = 0;
            for (LyricLine.Word word : line.words()) {
                while (cursor < length && Character.isWhitespace(text.charAt(cursor))) {
                    ++cursor;
                }
                if (cursor >= length) {
                    break;
                }
                int wordLength = Math.min(word.text().length(), length - cursor);
                this.addWord(cursor, cursor + wordLength, word.startMs() - line.timeMs());
                cursor += wordLength;
            }
            return;
        }
        long duration = Math.max(400L, lineEnd - line.timeMs());
        long reveal = Math.min(Math.max(250L, (long) length * 55L), Math.max(400L, (long) (duration * 0.6)));
        int i = 0;
        while (i < length) {
            while (i < length && Character.isWhitespace(text.charAt(i))) {
                ++i;
            }
            if (i >= length) {
                break;
            }
            int start = i;
            while (i < length && !Character.isWhitespace(text.charAt(i))) {
                ++i;
            }
            this.addWord(start, i, length <= 1 ? 0L : (long) (start / (float) (length - 1) * reveal));
        }
    }

    private void addWord(int start, int end, long appear) {
        this.wordStart[this.wordCount] = start;
        this.wordEnd[this.wordCount] = end;
        this.wordAppear[this.wordCount] = appear;
        ++this.wordCount;
    }

    private void resetWorldLine() {
        this.worldLineIndex = -1;
        this.worldLineAlpha = 0.0f;
        this.worldPlaced = false;
        this.worldLastNanos = 0L;
    }

    private boolean is3D() {
        return this.display.is("3D");
    }

    public void setLyrics(long songId, List<LyricLine> newLyrics) {
        // Put into warmup pool instead of directly into lyrics
        warmupSongId = songId;
        warmupPool = List.copyOf(newLyrics);
        warmupIndex = 0;
    }

    /**
     * Check if warmup is complete (no pending warmup pool).
     */
    public boolean isWarmupComplete() {
        return warmupPool.isEmpty();
    }

    private void loadLyricsIfNeeded(long songId) {
        if (songId == lyricsSongId || songId == warmupSongId) return;
        lyrics = Collections.emptyList();
        currentLyricIndex = -1;
        scrollAnim.animate(0, 0);

        NeteaseApi.getLyrics(songId).thenAccept(result -> {
            // Put into warmup pool
            warmupSongId = songId;
            warmupPool = List.copyOf(result);
            warmupIndex = 0;
        }).exceptionally(e -> {
            System.err.println("[Lyrics] Failed to load lyrics: " + e.getMessage());
            return null;
        });
    }

    private int findLyricIndex(long currentMs, List<LyricLine> list) {
        int idx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).timeMs() <= currentMs) {
                idx = i;
            } else {
                break;
            }
        }
        return idx;
    }
}
