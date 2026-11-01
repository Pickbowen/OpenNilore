package client.nilore.modules.impl.misc.music.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import client.nilore.modules.impl.misc.music.LyricLine;
import client.nilore.modules.impl.misc.music.SongInfo;

import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * <ul>
 * </ul>
 *
 */
public final class NeteaseOfficialApi {

    private static final String BASE = "https://music.163.com";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private static final Pattern YRC_LINE = Pattern.compile("\\[(\\d+),(\\d+)](.*)");
    private static final Pattern YRC_WORD = Pattern.compile("\\((\\d+),(\\d+),\\d+\\)([^()]*)");
    private static final Pattern LRC_LINE = Pattern.compile("\\[(\\d{2}):(\\d{2})[.:](\\d{2,3})]");
    private static final Pattern OFFSET_TAG = Pattern.compile("\\[offset:([+-]?\\d+)]", Pattern.CASE_INSENSITIVE);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(13))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Map<String, String> COOKIES = new ConcurrentHashMap<>();

    private static final String REAL_IP = "116.25.146.177";

    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile boolean loggedIn = false;

    private NeteaseOfficialApi() {
    }

    static {
        resetDeviceCookies();
    }

    private static void resetDeviceCookies() {
        String nuid = randomHex(32);
        long ts = System.currentTimeMillis();
        COOKIES.put("_ntes_nuid", nuid);
        COOKIES.put("_ntes_nnid", nuid + "," + ts);
        COOKIES.put("WNMCID", randomHex(12) + ts);
        COOKIES.put("NMTID", randomHex(16));
        COOKIES.remove("__csrf");
        COOKIES.remove("JSESSIONID-WYYY");
    }

    private static String randomHex(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append("0123456789abcdef".charAt(RANDOM.nextInt(16)));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    public static boolean isLoggedIn() {
        return loggedIn;
    }

    public static void loadSession(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return;
            }
            String cookie = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (cookie.isEmpty()) {
                return;
            }
            for (String part : cookie.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && !kv[0].isEmpty()) {
                    COOKIES.put(kv[0], kv[1]);
                }
            }
            String musicU = COOKIES.get("MUSIC_U");
            loggedIn = musicU != null && !musicU.isEmpty();
        } catch (Exception ignored) {
        }
    }

    private static void saveSession(Path file) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, cookieHeader(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : COOKIES.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        if (sb.length() > 0) sb.append("; ");
        sb.append("os=pc; appver=2.9.7");
        return sb.toString();
    }

    private static void captureSetCookies(HttpResponse<?> response) {
        List<String> setCookies = response.headers().allValues("Set-Cookie");
        for (String header : setCookies) {
            String[] parts = header.split(";");
            if (parts.length == 0) continue;
            String[] kv = parts[0].split("=", 2);
            if (kv.length != 2) continue;
            COOKIES.put(kv[0].trim(), kv[1].trim());
        }
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    public static CompletableFuture<List<SongInfo>> search(String keyword, int limit) {
        String json = "{\"s\":\"" + escape(keyword) + "\",\"type\":1,\"limit\":" + limit + ",\"offset\":0}";
        return post("/weapi/search/get", json).thenApply(root -> {
            List<SongInfo> out = new ArrayList<>();
            if (root == null || !root.isJsonObject()) {
                return out;
            }
            JsonObject result = root.getAsJsonObject().getAsJsonObject("result");
            if (result == null || !result.has("songs")) {
                return out;
            }
            JsonArray songs = result.getAsJsonArray("songs");
            for (JsonElement el : songs) {
                if (!el.isJsonObject()) continue;
                JsonObject song = el.getAsJsonObject();
                long id = optLong(song, "id");
                String name = optString(song, "name");
                if (id == 0 || name.isEmpty()) continue;

                StringBuilder artists = new StringBuilder();
                JsonArray arr = song.has("artists") && song.get("artists").isJsonArray()
                        ? song.getAsJsonArray("artists") : new JsonArray();
                for (int i = 0; i < arr.size(); i++) {
                    if (i > 0) artists.append(", ");
                    artists.append(optString(arr.get(i).getAsJsonObject(), "name"));
                }
                String albumName = "";
                String picId = "";
                JsonObject album = firstAlbum(song);
                if (album != null) {
                    albumName = optString(album, "name");
                    if (album.has("picId") && !album.get("picId").isJsonNull()) {
                        picId = album.get("picId").getAsString();
                    }
                }
                out.add(new SongInfo(id, name, artists.toString(), albumName, picId, songDurationMs(song)));
            }
            return out;
        });
    }

    public static CompletableFuture<List<LyricLine>> lyrics(long songId) {
        String json = "{\"id\":" + songId + ",\"lv\":-1,\"kv\":-1,\"tv\":-1,\"rv\":-1,\"yv\":-1,\"ytv\":-1,\"yrv\":-1}";
        return post("/weapi/song/lyric", json).thenApply(root -> {
            if (root == null || !root.isJsonObject()) {
                return Collections.<LyricLine>emptyList();
            }
            JsonObject obj = root.getAsJsonObject();
            List<LyricLine> yrc = parseYrc(nestedLyric(obj, "yrc"));
            if (!yrc.isEmpty()) {
                return yrc;
            }
            return parseLrc(nestedLyric(obj, "lrc"));
        });
    }

    private static final String ENCODE_TYPE = "mp3";

    public record PlayerUrl(String url, long durationMs) {
    }

    public static CompletableFuture<PlayerUrl> songUrl(long songId, boolean lossless) {
        String level = lossless ? "lossless" : "exhigh";
        String json = "{\"ids\":\"[" + songId + "]\",\"level\":\"" + level + "\",\"encodeType\":\""
                + ENCODE_TYPE + "\"}";
        return post("/weapi/song/enhance/player/url/v1", json).thenCompose(root -> {
            PlayerUrl parsed = parsePlayerUrl(root);
            if (parsed.url() != null) {
                return CompletableFuture.completedFuture(parsed);
            }
            String fallback = "{\"ids\":\"[" + songId + "]\",\"level\":\"standard\",\"encodeType\":\""
                    + ENCODE_TYPE + "\"}";
            return post("/weapi/song/enhance/player/url/v1", fallback)
                    .thenApply(NeteaseOfficialApi::parsePlayerUrl);
        });
    }

    private static PlayerUrl parsePlayerUrl(JsonElement root) {
        if (root == null || !root.isJsonObject()) {
            return new PlayerUrl(null, 0L);
        }
        JsonElement data = root.getAsJsonObject().get("data");
        if (data == null || !data.isJsonArray() || data.getAsJsonArray().isEmpty()) {
            return new PlayerUrl(null, 0L);
        }
        JsonElement first = data.getAsJsonArray().get(0);
        if (!first.isJsonObject()) {
            return new PlayerUrl(null, 0L);
        }
        JsonObject obj = first.getAsJsonObject();
        String url = obj.has("url") && !obj.get("url").isJsonNull() ? obj.get("url").getAsString() : null;
        return new PlayerUrl(url, optLong(obj, "time"));
    }

    public static CompletableFuture<String> coverUrl(long songId) {
        String json = "{\"ids\":\"[" + songId + "]\",\"c\":\"[{\\\"id\\\":" + songId + "}]\"}";
        return post("/weapi/v3/song/detail", json).thenApply(root -> {
            if (root == null || !root.isJsonObject()) {
                return null;
            }
            JsonElement songs = root.getAsJsonObject().get("songs");
            if (songs == null || !songs.isJsonArray() || songs.getAsJsonArray().isEmpty()) {
                return null;
            }
            JsonObject song = songs.getAsJsonArray().get(0).getAsJsonObject();
            JsonObject album = firstAlbum(song);
            if (album == null) {
                return null;
            }
            return album.has("picUrl") && !album.get("picUrl").isJsonNull()
                    ? album.get("picUrl").getAsString() + "?param=300y300" : null;
        });
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    public enum QrStatus { WAITING, SCANNED, CONFIRMED, EXPIRED, FAILED }

    public static CompletableFuture<String> requestQrKey() {
        return post("/weapi/login/qrcode/unikey", "{\"type\":1}").thenApply(root -> {
            if (root == null || !root.isJsonObject()) {
                return null;
            }
            JsonObject obj = root.getAsJsonObject();
            return obj.has("unikey") && !obj.get("unikey").isJsonNull()
                    ? obj.get("unikey").getAsString() : null;
        });
    }

    public static String qrContent(String key) {
        return "https://music.163.com/login?codekey=" + key;
    }

    public static CompletableFuture<QrStatus> pollQr(String key, Path sessionFile) {
        return post("/weapi/login/qrcode/client/login", "{\"key\":\"" + key + "\",\"type\":1}")
                .thenApply(root -> {
                    if (root == null || !root.isJsonObject()) {
                        return QrStatus.FAILED;
                    }
                    int code = (int) optLong(root.getAsJsonObject(), "code");
                    switch (code) {
                        case 802:
                            return QrStatus.SCANNED;
                        case 803:
                            loggedIn = true;
                            saveSession(sessionFile);
                            return QrStatus.CONFIRMED;
                        case 800:
                            return QrStatus.EXPIRED;
                        case 801:
                            return QrStatus.WAITING;
                        default:
                            return QrStatus.FAILED;
                    }
                });
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    private static CompletableFuture<JsonElement> post(String path, String jsonBody) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String csrf = COOKIES.getOrDefault("__csrf", "");
                JsonObject parsed = JsonParser.parseString(jsonBody).getAsJsonObject();
                if (!parsed.has("csrf_token")) {
                    parsed.addProperty("csrf_token", csrf);
                }
                String body = NeteaseCrypto.encrypt(parsed.toString());
                String url = BASE + path + "?csrf_token=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8);
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(21))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Referer", BASE)
                        .header("Origin", BASE)
                        .header("User-Agent", UA)
                        .header("X-Real-IP", REAL_IP)
                        .header("X-Forwarded-For", REAL_IP)
                        .header("Cookie", cookieHeader())
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response =
                        CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                captureSetCookies(response);
                if (response.statusCode() != 200) {
                    return null;
                }
                JsonReader reader = new JsonReader(new StringReader(response.body()));
                reader.setLenient(true);
                return JsonParser.parseReader(reader);
            } catch (Exception e) {
                System.err.println("[NeteaseOfficial] " + path + " 失败: " + e);
                return null;
            }
        });
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    private static String nestedLyric(JsonObject root, String field) {
        JsonElement el = root.get(field);
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        JsonObject obj = el.getAsJsonObject();
        if (!obj.has("lyric") || obj.get("lyric").isJsonNull()) {
            return null;
        }
        String text = obj.get("lyric").getAsString();
        return text.isEmpty() ? null : text;
    }

    private static List<LyricLine> parseYrc(String yrc) {
        List<LyricLine> out = new ArrayList<>();
        if (yrc == null) {
            return out;
        }
        for (String raw : yrc.split("\n")) {
            Matcher line = YRC_LINE.matcher(raw.trim());
            if (!line.matches()) {
                continue;
            }
            long start = Long.parseLong(line.group(1));
            List<LyricLine.Word> words = new ArrayList<>();
            Matcher word = YRC_WORD.matcher(line.group(3));
            StringBuilder text = new StringBuilder();
            while (word.find()) {
                long wordStart = Long.parseLong(word.group(1));
                long wordDuration = Long.parseLong(word.group(2));
                String content = word.group(3);
                words.add(new LyricLine.Word(content, wordStart, wordDuration));
                text.append(content);
            }
            String lineText = text.toString().trim();
            if (words.isEmpty() || isMetadataLine(lineText)) {
                continue;
            }
            out.add(new LyricLine(start, lineText, words));
        }
        return out;
    }

    private static boolean isMetadataLine(String text) {
        String lower = text.toLowerCase();
        return (text.contains(":") || text.contains("："))
                && (lower.contains("作词") || lower.contains("作曲")
                || lower.contains("编曲") || lower.contains("填词")
                || lower.contains("作词 :") || lower.contains("作曲 :") || lower.contains("lyric")
                || lower.contains("compos") || lower.contains("arrang"));
    }

    /**
     *
     * <ul>
     * </ul>
     */
    private static List<LyricLine> parseLrc(String raw) {
        List<LyricLine> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
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
            Matcher m = LRC_LINE.matcher(line);
            int from = 0;
            while (from < line.length()) {
                m.region(from, line.length());
                if (!m.lookingAt()) {
                    break;
                }
                String fraction = m.group(3);
                long fractionMs = fraction.length() == 2 ? Long.parseLong(fraction) * 10 : Long.parseLong(fraction);
                times.add(Long.parseLong(m.group(1)) * 60_000L
                        + Long.parseLong(m.group(2)) * 1000L
                        + fractionMs);
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
                out.add(new LyricLine(Math.max(0, t - offsetMs), text, null));
            }
        }
        out.sort((a, b) -> Long.compare(a.timeMs(), b.timeMs()));
        return out;
    }

    private static String optString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private static JsonObject firstAlbum(JsonObject song) {
        if (song.has("al") && song.get("al").isJsonObject()) {
            return song.getAsJsonObject("al");
        }
        if (song.has("album") && song.get("album").isJsonObject()) {
            return song.getAsJsonObject("album");
        }
        return null;
    }

    private static long songDurationMs(JsonObject song) {
        long duration = optLong(song, "duration");
        return duration > 0 ? duration : optLong(song, "dt");
    }

    private static long optLong(JsonObject obj, String key) {
        try {
            return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsLong() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
