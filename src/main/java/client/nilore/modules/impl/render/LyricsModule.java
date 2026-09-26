package client.nilore.modules.impl.render;

import java.util.Collections;
import java.util.List;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.GlRenderEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.misc.MusicPlayer;
import client.nilore.modules.impl.misc.music.AudioPlayer;
import client.nilore.modules.impl.misc.music.LyricLine;
import client.nilore.modules.impl.misc.music.NeteaseApi;
import client.nilore.modules.impl.misc.music.SongInfo;
import client.nilore.render.DrawContext;
import client.nilore.render.FontPresets;
import client.nilore.render.FontRenderer;
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

    private final NumberSetting yOffset = new NumberSetting("Y Offset", 100, -200, 200, 1);
    /** 文字相对行的微调：不同字体的视觉重心不完全一样，用它把基线对齐到舒服的位置。 */
    private final NumberSetting fontOffset = new NumberSetting("Font Offset", 0, -2, 2, 1);

    private static final float PANEL_H = 130;
    private static final float LINE_SPACING = 22;

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
