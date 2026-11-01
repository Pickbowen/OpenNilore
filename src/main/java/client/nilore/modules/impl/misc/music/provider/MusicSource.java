package client.nilore.modules.impl.misc.music.provider;

import client.nilore.modules.impl.misc.music.LyricLine;
import client.nilore.modules.impl.misc.music.SongInfo;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface MusicSource {

    String displayName();

    CompletableFuture<List<SongInfo>> search(String keyword, int limit);

    CompletableFuture<List<LyricLine>> lyrics(long songId);

    CompletableFuture<SongUrlResult> songUrl(long songId);

    CompletableFuture<String> coverUrl(SongInfo song);

    record SongUrlResult(String url, long size, long durationMs) {
    }
}