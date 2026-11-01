package client.nilore.modules.impl.misc.music.provider;

import client.nilore.modules.impl.misc.music.LyricLine;
import client.nilore.modules.impl.misc.music.SongInfo;

import java.util.List;
import java.util.concurrent.CompletableFuture;

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
        return NeteaseOfficialApi.coverUrl(song.id);
    }
}