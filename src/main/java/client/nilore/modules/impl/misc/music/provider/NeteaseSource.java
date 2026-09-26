package client.nilore.modules.impl.misc.music.provider;

import client.nilore.modules.impl.misc.music.LyricLine;
import client.nilore.modules.impl.misc.music.SongInfo;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 直连网易官方接口（{@code music.163.com}，weapi 加密）。
 *
 * <p>优势：歌词里可能有 {@code yrc} 逐字时间（卡拉OK扫光就靠它）。
 * <p>劣势：播放地址对大部分歌需要登录（VIP Cookie），未登录时返回 null。
 */
final class NeteaseSource implements MusicSource {

    @Override
    public String displayName() {
        return "NetEase Official";
    }

    @Override
    public CompletableFuture<List<SongInfo>> search(String keyword, int limit) {
        return NeteaseOfficialApi.search(keyword, limit);
    }

    @Override
    public CompletableFuture<List<LyricLine>> lyrics(long songId) {
        return NeteaseOfficialApi.lyrics(songId);
    }

    @Override
    public CompletableFuture<SongUrlResult> songUrl(long songId) {
        return NeteaseOfficialApi.songUrl(songId, false).thenApply(result ->
                result == null || result.url() == null
                        ? null
                        : new SongUrlResult(result.url(), 0L, result.durationMs()));
    }

    @Override
    public CompletableFuture<String> coverUrl(SongInfo song) {
        // 官方 coverUrl 用 songId 直接拿直链，比 gdstudio 那边拼 picId 简单
        return NeteaseOfficialApi.coverUrl(song.id);
    }
}