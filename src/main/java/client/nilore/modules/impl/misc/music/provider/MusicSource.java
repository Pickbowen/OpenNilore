package client.nilore.modules.impl.misc.music.provider;

import client.nilore.modules.impl.misc.music.LyricLine;
import client.nilore.modules.impl.misc.music.SongInfo;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 一个播放渠道（搜索 / 歌词 / 播放地址 / 封面）。
 *
 * <p>底层可以是第三方聚合接口，也可以是网易官方接口；上层（{@code NeteaseApi}
 * 这个门面）始终调用这一个接口。
 */
public interface MusicSource {

    /** 显示名，给用户看的："GDStudio"、"NetEase Official"。 */
    String displayName();

    /** 搜索。 */
    CompletableFuture<List<SongInfo>> search(String keyword, int limit);

    /** 歌词。 */
    CompletableFuture<List<LyricLine>> lyrics(long songId);

    /** 播放地址。size 在不能确定时填 0。 */
    CompletableFuture<SongUrlResult> songUrl(long songId);

    /**
     * 封面直链 URL。
     *
     * <p>两个 source 取封面方式不同（gdstudio 用 picId，官方用 songId），
     * 所以传完整 SongInfo，source 内部自己挑字段。
     */
    CompletableFuture<String> coverUrl(SongInfo song);

    /**
     * @param url        播放直链
     * @param size       文件字节数，拿不到时填 0
     * @param durationMs 歌曲时长（毫秒），拿不到时填 0。
     *                   有这个就别再拿 size 去估了——搜索接口经常不给时长，
     *                   而播放地址接口会顺带返回准确的 {@code time}。
     */
    record SongUrlResult(String url, long size, long durationMs) {
    }
}