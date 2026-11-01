package client.nilore.modules.impl.misc.music;

import client.nilore.modules.impl.misc.music.provider.MusicSource;
import client.nilore.modules.impl.misc.music.provider.MusicSources;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class NeteaseApi {

    public static CompletableFuture<List<SongInfo>> search(String keywords, int limit) {
        return MusicSources.current().search(keywords, limit);
    }

    public static CompletableFuture<List<LyricLine>> getLyrics(long songId) {
        return MusicSources.current().lyrics(songId);
    }

    public static CompletableFuture<MusicSource.SongUrlResult> getSongUrl(long songId) {
        return MusicSources.current().songUrl(songId);
    }

    public static CompletableFuture<String> getAlbumPicUrl(SongInfo song) {
        return MusicSources.current().coverUrl(song);
    }
}
