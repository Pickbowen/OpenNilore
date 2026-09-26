package client.nilore.modules.impl.misc.music;

import client.nilore.modules.impl.misc.music.dsp.BeatDetector;

public class SongInfo {
    public final long id;
    public final String name;
    public final String artist;
    public final String albumName;
    public final String albumPicUrl;
    public long duration;
    public float chorusStartSec;
    public float chorusDurationSec;
    /** 节拍网格。预加载解码时顺手算出来，automix 靠它把切歌点对齐到小节线；分析失败时为 null。 */
    public BeatDetector.BeatGrid beat;
    /**
     * automix 算出来的入口位置（跳过前奏后的第一句），单位秒。
     *
     * <p>和 {@code chorusStartSec} 的区别：那个是「高潮在哪」，这个是「人声/主歌在哪」。
     * 混音交接用后者更自然——从主歌进比从副歌突然进要好听得多。0 表示没算出来。
     */
    public float introEndSec;

    public SongInfo(long id, String name, String artist, String albumName, String albumPicUrl, long duration) {
        this.id = id;
        this.name = name;
        this.artist = artist;
        this.albumName = albumName;
        this.albumPicUrl = albumPicUrl;
        this.duration = duration;
    }

    public String formatDuration() {
        long totalSec = duration / 1000;
        return String.format("%d:%02d", totalSec / 60, totalSec % 60);
    }
}
