package client.nilore.modules.impl.misc.music;

import client.nilore.modules.impl.misc.music.provider.MusicSource;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * gdstudio 第三方聚合 API（{@code music-api.gdstudio.xyz}）的真实实现。
 *
 * <p>原来这堆逻辑直接写在 {@code NeteaseApi} 里；引入 provider 层后 {@code NeteaseApi}
 * 退化成门面，如果 {@code GdstudioSource} 还调门面就会形成
 * {@code NeteaseApi → MusicSources → GdstudioSource → NeteaseApi} 的环，
 * 一进来就 StackOverflow。所以把真实 HTTP 逻辑挪到这里，让 source 直接调这里、不再回头。
 */
public final class GdstudioApi {

    private static final String BASE = "https://music-api.gdstudio.xyz/api.php";
    private static final Pattern LRC_PATTERN = Pattern.compile("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})]");
    /** LRC 全局偏移标签：[offset:+500]，正数表示歌词提前 */
    private static final Pattern OFFSET_TAG = Pattern.compile("\\[offset:([+-]?\\d+)]", Pattern.CASE_INSENSITIVE);

    private GdstudioApi() {
    }

    public static CompletableFuture<List<SongInfo>> search(String keywords, int limit) {
        String normalized = keywords == null ? "" : keywords.trim().replaceAll("\\s+", " ");
        String encoded = java.net.URLEncoder.encode(normalized, StandardCharsets.UTF_8);
        String params = "types=search&source=netease&name=" + encoded + "&count=" + limit + "&pages=1";
        return get(params).thenCompose(root -> {
            List<SongInfo> results = parseSearchResults(root);
            if (!results.isEmpty()) {
                return CompletableFuture.completedFuture(results);
            }
            return get(params).thenApply(GdstudioApi::parseSearchResults);
        });
    }

    private static List<SongInfo> parseSearchResults(JsonElement root) {
        List<SongInfo> results = new ArrayList<>();
        if (root == null || !root.isJsonArray()) return results;
        JsonArray arr = root.getAsJsonArray();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            if (!obj.has("id") || !obj.has("name")) continue;
            long id = obj.get("id").getAsLong();
            String name = obj.get("name").getAsString();
            JsonArray artists = obj.has("artist") && obj.get("artist").isJsonArray()
                    ? obj.getAsJsonArray("artist") : new JsonArray();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < artists.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(artists.get(i).getAsString());
            }
            String albumName = obj.has("album") && !obj.get("album").isJsonNull()
                    ? obj.get("album").getAsString() : "";
            String picId = obj.has("pic_id") && !obj.get("pic_id").isJsonNull()
                    ? obj.get("pic_id").getAsString() : "";
            results.add(new SongInfo(id, name, sb.toString(), albumName, picId, 0));
        }
        return results;
    }

    public static CompletableFuture<MusicSource.SongUrlResult> getSongUrl(long songId) {
        return get("types=url&source=netease&id=" + songId + "&br=320")
                .thenApply(root -> {
                    if (root == null || !root.isJsonObject()) return null;
                    JsonObject obj = root.getAsJsonObject();
                    String url = obj.has("url") && !obj.get("url").isJsonNull()
                            ? obj.get("url").getAsString() : null;
                    long size = obj.has("size") ? obj.get("size").getAsLong() : 0;
                    System.out.println("[MusicPlayer] Song URL: " + url + " size=" + size);
                    return url != null ? new MusicSource.SongUrlResult(url, size, 0L) : null;
                });
    }

    public static CompletableFuture<String> getAlbumPicUrl(String picId) {
        if (picId == null || picId.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return get("types=pic&source=netease&id=" + picId + "&size=300")
                .thenApply(root -> {
                    if (root == null || !root.isJsonObject()) return null;
                    JsonObject obj = root.getAsJsonObject();
                    return obj.has("url") ? obj.get("url").getAsString() : null;
                });
    }

    public static CompletableFuture<List<LyricLine>> getLyrics(long songId) {
        return get("types=lyric&source=netease&id=" + songId)
                .thenApply(root -> {
                    if (root == null || !root.isJsonObject()) return Collections.emptyList();
                    JsonObject obj = root.getAsJsonObject();
                    String raw = obj.has("lyric") ? obj.get("lyric").getAsString() : "";
                    if (raw.isEmpty()) return Collections.emptyList();
                    return parseLrc(raw);
                });
    }

    /**
     * 行级 LRC 解析。
     *
     * <p>坑和 {@code NeteaseOfficialApi.parseLrc} 一样：多时间戳行 {@code [00:12.00][00:15.00]词}
     * 要用 {@code lookingAt} 逐个吞，用 {@code matches} 一次匹配会把第二个时间戳吃进歌词文本；
     * 还要处理 {@code [offset:...]} 全局偏移和开头的署名元数据行。
     */
    private static List<LyricLine> parseLrc(String raw) {
        List<LyricLine> lines = new ArrayList<>();
        long offsetMs = 0;
        for (String line : raw.split("\n")) {
            Matcher offset = OFFSET_TAG.matcher(line);
            if (offset.matches()) {
                try {
                    offsetMs = Long.parseLong(offset.group(1));
                } catch (NumberFormatException ignored) {
                }
                continue;
            }
            List<Long> times = new ArrayList<>();
            int textStart = -1;
            Matcher m = LRC_PATTERN.matcher(line);
            int from = 0;
            while (from < line.length()) {
                m.region(from, line.length());
                if (!m.lookingAt()) {
                    break;
                }
                String frac = m.group(3);
                long fracMs = frac.length() == 2 ? Long.parseLong(frac) * 10 : Long.parseLong(frac);
                times.add(Long.parseLong(m.group(1)) * 60_000
                        + Long.parseLong(m.group(2)) * 1000
                        + fracMs);
                from = m.end();
                textStart = from;
            }
            if (times.isEmpty() || textStart < 0 || textStart >= line.length()) {
                continue;
            }
            String text = line.substring(textStart).trim();
            if (text.isEmpty() || isMetadataLine(text)) {
                continue;
            }
            for (long t : times) {
                // 正 offset 表示歌词提前出现，时间戳要减
                lines.add(new LyricLine(Math.max(0, t - offsetMs), text));
            }
        }
        Collections.sort(lines, (a, b) -> Long.compare(a.timeMs(), b.timeMs()));
        return lines;
    }

    /** 署名元数据行（作词/作曲/编曲），不是歌词。 */
    private static boolean isMetadataLine(String text) {
        String lower = text.toLowerCase();
        return (text.contains(":") || text.contains("："))
                && (lower.contains("作词") || lower.contains("作曲") || lower.contains("编曲")
                || lower.contains("填词") || lower.contains("lyric") || lower.contains("compos")
                || lower.contains("arrang"));
    }

    private static CompletableFuture<JsonElement> get(String params) {
        String url = BASE + "?" + params;
        return MusicHttp.getStringAsync(URI.create(url))
                .thenApply(body -> {
                    JsonReader reader = new JsonReader(new StringReader(body));
                    reader.setLenient(true);
                    return JsonParser.parseReader(reader);
                })
                .exceptionally(e -> {
                    System.err.println("[MusicPlayer] API " + params.split("&")[0] + " failed: " + e.getMessage());
                    return null;
                });
    }
}