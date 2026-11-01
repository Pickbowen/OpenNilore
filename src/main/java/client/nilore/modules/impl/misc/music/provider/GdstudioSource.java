package client.nilore.modules.impl.misc.music.provider;

import client.nilore.modules.impl.misc.music.GdstudioApi;
import client.nilore.modules.impl.misc.music.LyricLine;
import client.nilore.modules.impl.misc.music.SongInfo;

import java.util.List;
import java.util.concurrent.CompletableFuture;

final class GdstudioSource implements MusicSource {

    @Override
    public String displayName() {
        return "GDStudio";
    }

    @Override
    public CompletableFuture<List<SongInfo>> search(String keyword, int limit) {
        return GdstudioApi.search(keyword, limit);
    }

    @Override
    public CompletableFuture<List<LyricLine>> lyrics(long songId) {
        return GdstudioApi.getLyrics(songId);
    }

    @Override
    public CompletableFuture<SongUrlResult> songUrl(long songId) {
        return GdstudioApi.getSongUrl(songId);
    }

    @Override
    public CompletableFuture<String> coverUrl(SongInfo song) {
        return GdstudioApi.getAlbumPicUrl(song.albumPicUrl);
    }
}