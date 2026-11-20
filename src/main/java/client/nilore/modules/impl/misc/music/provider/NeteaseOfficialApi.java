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
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

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

    private static volatile String lastMessage = "Not signed in";
    private static volatile int lastQrCode = 0;

    private NeteaseOfficialApi() {
    }

    static {
        resetDeviceCookies();
    }

    private static void resetDeviceCookies() {
        String nuid = randomHex(32);
        long ts = System.currentTimeMillis();
        COOKIES.put("__remember_me", "true");
        COOKIES.put("ntes_kaola_ad", "1");
        COOKIES.put("_ntes_nuid", nuid);
        COOKIES.put("_ntes_nnid", nuid + "," + ts);
        COOKIES.put("WNMCID", randomHex(12) + ts);
        COOKIES.put("WEVNSM", "1.0.0");
        COOKIES.put("NMTID", randomHex(16));
        COOKIES.put("os", "pc");
        COOKIES.put("osver", "Microsoft-Windows-10");
        COOKIES.put("appver", "2.10.7");
        COOKIES.put("channel", "netease");
        COOKIES.put("deviceId", randomHex(16));
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

    private static volatile String accountName = null;
    private static volatile long accountId = 0L;
    private static volatile boolean vip = false;

    public static boolean isLoggedIn() {
        return loggedIn;
    }

    /** Nickname of the signed-in account, or null when unknown / signed out. */
    public static String accountName() {
        return accountName;
    }

    /** NetEase user id of the signed-in account, 0 when unknown. */
    public static long accountId() {
        return accountId;
    }

    /** True when the signed-in account has an active VIP subscription. */
    public static boolean isVip() {
        return vip;
    }

    /** Latest human readable step of the sign-in flow, shown in the settings page and the dialog. */
    public static String lastMessage() {
        return lastMessage;
    }

    /** Raw NetEase status code of the last QR poll (801 waiting, 802 scanned, 803 ok, 800 expired). */
    public static int lastQrCode() {
        return lastQrCode;
    }

    /**
     * Drops the session cookie and the saved file, keeping the device cookies so the next
     * sign-in looks like the same install.
     */
    public static void logout(Path sessionFile) {
        loggedIn = false;
        accountName = null;
        accountId = 0L;
        vip = false;
        lastMessage = "Not signed in";
        lastQrCode = 0;
        COOKIES.remove("MUSIC_U");
        COOKIES.remove("MUSIC_A");
        COOKIES.remove("__csrf");
        COOKIES.remove("JSESSIONID-WYYY");
        try {
            if (sessionFile != null) {
                Files.deleteIfExists(sessionFile);
            }
        } catch (Exception ignored) {
        }
    }

    /** Asks the server who we are; also the cheapest way to find out if a stored cookie died. */
    public static CompletableFuture<Boolean> fetchAccount() {
        return post("/weapi/w/nuser/account/get", "{}").thenApply(root -> {
            if (root == null || !root.isJsonObject()) {
                return loggedIn;
            }
            JsonObject obj = root.getAsJsonObject();
            JsonObject profile = obj.has("profile") && obj.get("profile").isJsonObject()
                    ? obj.getAsJsonObject("profile") : null;
            if (profile == null) {
                // Only a 200 answer is allowed to drop the session; a proxy or a 5xx must not
                // sign the user out, it just means "could not tell right now".
                if (optLong(obj, "code") == 200) {
                    loggedIn = false;
                    accountName = null;
                    accountId = 0L;
                    vip = false;
                }
                return false;
            }
            accountName = optString(profile, "nickname");
            accountId = optLong(profile, "userId");
            vip = optLong(profile, "vipType") > 0;
            loggedIn = true;
            lastMessage = accountName.isEmpty() ? "Signed in" : "Signed in as " + accountName;
            return true;
        });
    }

    public static void loadSession(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return;
            }
            String saved = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (saved.isEmpty()) {
                return;
            }
            if (saved.startsWith("{")) {
                JsonObject obj = JsonParser.parseString(saved).getAsJsonObject();
                String musicU = obj.has("MUSIC_U") ? obj.get("MUSIC_U").getAsString() : "";
                String csrf = obj.has("__csrf") ? obj.get("__csrf").getAsString() : "";
                if (!musicU.isBlank()) {
                    COOKIES.put("MUSIC_U", musicU);
                }
                if (!csrf.isBlank()) {
                    COOKIES.put("__csrf", csrf);
                }
            } else {
                for (String part : saved.split(";")) {
                    String[] kv = part.trim().split("=", 2);
                    if (kv.length == 2 && !kv[0].isEmpty()) {
                        COOKIES.put(kv[0], kv[1]);
                    }
                }
            }
            String musicU = COOKIES.get("MUSIC_U");
            loggedIn = musicU != null && !musicU.isEmpty();
            if (loggedIn) {
                lastMessage = "Restored the saved session";
            }
        } catch (Exception ignored) {
        }
    }

    private static void saveSession(Path file) {
        try {
            String musicU = COOKIES.get("MUSIC_U");
            if (musicU == null || musicU.isBlank()) {
                return;
            }
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("MUSIC_U", musicU);
            obj.addProperty("__csrf", COOKIES.getOrDefault("__csrf", ""));
            Files.writeString(file, obj.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : COOKIES.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
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
                lastMessage = "Could not reach NetEase";
                return null;
            }
            JsonObject obj = root.getAsJsonObject();
            if (!obj.has("unikey") || obj.get("unikey").isJsonNull()) {
                lastMessage = "NetEase refused the QR request (code " + optLong(obj, "code") + ")";
                return null;
            }
            lastMessage = "Waiting for the NetEase app to scan";
            return obj.get("unikey").getAsString();
        });
    }

    public static String qrContent(String key) {
        return "https://music.163.com/login?codekey=" + key;
    }

    public static CompletableFuture<QrStatus> pollQr(String key, Path sessionFile) {
        return post("/weapi/login/qrcode/client/login", "{\"key\":\"" + key + "\",\"type\":1}")
                .thenCompose(root -> {
                    if (root == null || !root.isJsonObject()) {
                        lastMessage = "Network error while polling";
                        return CompletableFuture.completedFuture(QrStatus.FAILED);
                    }
                    int code = (int) optLong(root.getAsJsonObject(), "code");
                    lastQrCode = code;
                    switch (code) {
                        case 802 -> {
                            lastMessage = "Scanned — confirm on your phone";
                            return CompletableFuture.completedFuture(QrStatus.SCANNED);
                        }
                        case 803 -> {
                            loggedIn = true;
                            saveSession(sessionFile);
                            lastMessage = "Confirmed, loading your account";
                            // 803 only means "the phone said yes"; the session is real only if the
                            // account endpoint can read a profile with the cookies we just stored.
                            return confirmAccount(sessionFile, 3);
                        }
                        case 800 -> {
                            lastMessage = "QR code expired";
                            return CompletableFuture.completedFuture(QrStatus.EXPIRED);
                        }
                        case 801 -> {
                            lastMessage = "Waiting for the NetEase app to scan";
                            return CompletableFuture.completedFuture(QrStatus.WAITING);
                        }
                        default -> {
                            lastMessage = "Unexpected answer from NetEase (code " + code + ")";
                            return CompletableFuture.completedFuture(QrStatus.FAILED);
                        }
                    }
                });
    }

    /**
     * The account call right after a confirm sometimes answers before the new session cookie is
     * accepted, which used to surface as a bogus "sign in failed". Retry a couple of times.
     */
    private static CompletableFuture<QrStatus> confirmAccount(Path sessionFile, int attemptsLeft) {
        return fetchAccount().thenCompose(ok -> {
            if (ok) {
                // The account call can rotate the session cookies, so the file is written again.
                saveSession(sessionFile);
                return CompletableFuture.completedFuture(QrStatus.CONFIRMED);
            }
            if (attemptsLeft <= 1) {
                lastMessage = "Signed in, but the account could not be loaded";
                return CompletableFuture.completedFuture(QrStatus.CONFIRMED);
            }
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(700L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }).thenCompose(ignored -> confirmAccount(sessionFile, attemptsLeft - 1));
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
