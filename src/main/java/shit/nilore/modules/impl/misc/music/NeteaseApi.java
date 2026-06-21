package shit.nilore.modules.impl.misc.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class NeteaseApi {
    private static final String BASE = "https://music.163.com/api";
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    public static CompletableFuture<List<SongInfo>> search(String keywords, int limit) {
        String encoded = java.net.URLEncoder.encode(keywords, StandardCharsets.UTF_8);
        return get("/search/get", "type=1&offset=0&total=true&limit=" + limit + "&s=" + encoded)
                .thenApply(root -> {
                    List<SongInfo> results = new ArrayList<>();
                    JsonObject result = root.getAsJsonObject("result");
                    if (result == null || !result.has("songs")) return results;
                    JsonArray songs = result.getAsJsonArray("songs");
                    for (JsonElement el : songs) {
                        JsonObject obj = el.getAsJsonObject();
                        long id = obj.get("id").getAsLong();
                        String name = obj.get("name").getAsString();
                        JsonArray artists = obj.getAsJsonArray("artists");
                        StringBuilder artistBuilder = new StringBuilder();
                        for (int i = 0; i < artists.size(); i++) {
                            if (i > 0) artistBuilder.append(", ");
                            artistBuilder.append(artists.get(i).getAsJsonObject().get("name").getAsString());
                        }
                        String albumName = obj.getAsJsonObject("album").get("name").getAsString();
                        String picUrl = obj.getAsJsonObject("album").has("picUrl")
                                ? obj.getAsJsonObject("album").get("picUrl").getAsString() : "";
                        long duration = obj.has("duration") ? obj.get("duration").getAsLong() : 0;
                        results.add(new SongInfo(id, name, artistBuilder.toString(), albumName, picUrl, duration));
                    }
                    return results;
                });
    }

    public static CompletableFuture<String> getSongUrl(long songId) {
        return get("/song/enhance/player/url", "ids=[" + songId + "]&br=320000")
                .thenApply(root -> {
                    JsonArray data = root.getAsJsonArray("data");
                    if (data == null || data.isEmpty()) return null;
                    JsonObject first = data.get(0).getAsJsonObject();
                    String url = first.has("url") && !first.get("url").isJsonNull()
                            ? first.get("url").getAsString() : null;
                    System.out.println("[MusicPlayer] Song URL: " + url);
                    return url;
                });
    }

    public static CompletableFuture<SongInfo> getSongDetail(long songId) {
        return get("/song/detail", "ids=[" + songId + "]")
                .thenApply(root -> {
                    JsonArray songs = root.getAsJsonArray("songs");
                    if (songs == null || songs.isEmpty()) return null;
                    JsonObject obj = songs.get(0).getAsJsonObject();
                    String name = obj.get("name").getAsString();
                    JsonArray ar = obj.getAsJsonArray("ar");
                    StringBuilder artistBuilder = new StringBuilder();
                    for (int i = 0; i < ar.size(); i++) {
                        if (i > 0) artistBuilder.append(", ");
                        artistBuilder.append(ar.get(i).getAsJsonObject().get("name").getAsString());
                    }
                    JsonObject al = obj.getAsJsonObject("al");
                    String albumName = al.get("name").getAsString();
                    String picUrl = al.has("picUrl") ? al.get("picUrl").getAsString() : "";
                    long duration = obj.has("dt") ? obj.get("dt").getAsLong() : 0;
                    return new SongInfo(songId, name, artistBuilder.toString(), albumName, picUrl, duration);
                });
    }

    private static CompletableFuture<JsonObject> get(String path, String params) {
        String url = BASE + path + "?" + params;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Referer", "https://music.163.com")
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();
        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> JsonParser.parseString(resp.body()).getAsJsonObject());
    }
}
