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
 * 网易云官方接口（直连 music.163.com，走 weapi 加密）。
 *
 * <p>和 {@code NeteaseApi}（走 gdstudio 第三方聚合）的区别：
 * <ul>
 *   <li>未登录也能搜索、拿歌词、拿封面；只有播放地址需要登录（多数歌要会员 Cookie）。</li>
 *   <li>歌词里可能带 {@code yrc} 逐字时间，能做出精确的卡拉OK扫光 —— 第三方聚合接口拿不到这个。</li>
 * </ul>
 *
 * <p>接口地址和参数都实测过：搜索 {@code weapi/search/get}（不是 cloudsearch，后者未登录会被风控挡）、
 * 歌词 {@code weapi/song/lyric}、播放地址 {@code weapi/song/enhance/player/url/v1}、
 * 封面 {@code weapi/v3/song/detail}（直接给 picUrl，省得自己算图片地址的加密）、
 * 二维码 {@code weapi/login/qrcode/unikey} + {@code .../client/login}。
 */
public final class NeteaseOfficialApi {

    private static final String BASE = "https://music.163.com";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    /** yrc 逐字行：[行起始ms,行时长ms](词起始ms,词时长ms,0)词 ... */
    private static final Pattern YRC_LINE = Pattern.compile("\\[(\\d+),(\\d+)](.*)");
    private static final Pattern YRC_WORD = Pattern.compile("\\((\\d+),(\\d+),\\d+\\)([^()]*)");
    /** 普通 LRC 行的时间戳部分：[mm:ss.xx]。文本部分由调用方在时间戳之后截取（支持一行多个时间戳）。 */
    private static final Pattern LRC_LINE = Pattern.compile("\\[(\\d{2}):(\\d{2})[.:](\\d{2,3})]");
    /** LRC 全局偏移标签：[offset:+500]，正数表示歌词提前 */
    private static final Pattern OFFSET_TAG = Pattern.compile("\\[offset:([+-]?\\d+)]", Pattern.CASE_INSENSITIVE);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 手动维护的 Cookie 表（抄 Melodify：不依赖 CookieManager，自己解析 Set-Cookie）。
     * Java 的 CookieManager 收网易 Set-Cookie 不稳定（domain/HttpOnly 偶发丢），
     * 这是扫码登录没反应、封面偶发加载不出的根因之一。
     */
    private static final Map<String, String> COOKIES = new ConcurrentHashMap<>();

    /** weapi 请求头伪装的固定 IP，降低被网易风控拦的概率。 */
    private static final String REAL_IP = "116.25.146.177";

    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile boolean loggedIn = false;

    private NeteaseOfficialApi() {
    }

    static {
        resetDeviceCookies();
    }

    /** 生成一组随机设备 Cookie（抄 Melodify 的 resetDeviceCookies）。 */
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
    // 会话
    // ------------------------------------------------------------------

    public static boolean isLoggedIn() {
        return loggedIn;
    }

    /** 从磁盘恢复登录态。启动时调一次即可。 */
    public static void loadSession(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return;
            }
            String cookie = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (cookie.isEmpty()) {
                return;
            }
            // 解析 "a=b; c=d" 进 COOKIES；登录态看 MUSIC_U 是否出现
            for (String part : cookie.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && !kv[0].isEmpty()) {
                    COOKIES.put(kv[0], kv[1]);
                }
            }
            String musicU = COOKIES.get("MUSIC_U");
            loggedIn = musicU != null && !musicU.isEmpty();
        } catch (Exception ignored) {
            // 会话读不出来就当没登录，不影响使用
        }
    }

    /** 把当前 Cookie 落盘，下次启动免扫码。 */
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

    /** 从 COOKIES 拼请求头（末尾补 os/appver 兜底）。 */
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

    /** 手动收 Set-Cookie（抄 Melodify 的 captureSetCookies）。 */
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
    // 业务接口
    // ------------------------------------------------------------------

    /** 搜索。未登录可用。 */
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
                    // 官方的 album.picId 是个纯数字，这里沿用 SongInfo.albumPicUrl 存它，
                    // 和 gdstudio 那边的用法保持一致
                    if (album.has("picId") && !album.get("picId").isJsonNull()) {
                        picId = album.get("picId").getAsString();
                    }
                }
                out.add(new SongInfo(id, name, artists.toString(), albumName, picId, songDurationMs(song)));
            }
            return out;
        });
    }

    /**
     * 歌词。
     *
     * <p>优先返回带逐字时间的版本（yrc），拿不到才退回行级 LRC。
     * 逐字数据会填进 {@link LyricLine#words()}，界面据此做精确扫光。
     */
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

    /**
     * 请求的音频编码。
     *
     * <p><b>只能用 mp3</b>：播放侧走 JDK 的 {@code AudioSystem}，而这个环境里只装了
     * jlayer + mp3spi（见 build.gradle），解不了 aac——换成 "aac" 的表现就是
     * 每播一段突然静音（解码器中途失败，不是彻底没声音）。
     *
     * <p>代价是同档位的码率：{@code exhigh} 配 mp3 是 320kbps，配 aac 是 256kbps。
     * 想再往上只能走 {@code lossless}，但那是 FLAC，得额外加一个 FLAC 解码库。
     */
    private static final String ENCODE_TYPE = "mp3";

    /** 播放地址 + 时长。 */
    public record PlayerUrl(String url, long durationMs) {
    }

    /**
     * 播放地址。未登录时多数歌会返回 null。
     *
     * <p>高码率拿不到会**自动降一档**再试一次，不管请求的是不是 lossless——
     * 原来 {@code lossless} 请求失败时直接返回 null，等于白白放弃这次播放。
     */
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

    /**
     * 从 song/url 的响应里取地址和时长。
     *
     * <p>{@code time} 是网易随播放地址一起返回的时长（毫秒），比搜索接口那个 duration 可靠得多——
     * 搜索结果经常不带时长，界面就只能拿文件大小去估，进度条和倒计时会一直是错的。
     */
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

    /** 封面直链。走 song/detail，省得自己实现图片地址的加密。 */
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
            // 这个接口返回的专辑字段叫 al，只有老接口才叫 album。
            // 之前只认 album，所以封面永远是 null。
            JsonObject album = firstAlbum(song);
            if (album == null) {
                return null;
            }
            return album.has("picUrl") && !album.get("picUrl").isJsonNull()
                    ? album.get("picUrl").getAsString() + "?param=300y300" : null;
        });
    }

    // ------------------------------------------------------------------
    // 二维码登录
    // ------------------------------------------------------------------

    /** 登录轮询的状态。 */
    public enum QrStatus { WAITING, SCANNED, CONFIRMED, EXPIRED, FAILED }

    /** 申请一个二维码 key。把这个 key 拼进 {@link #qrContent} 就是二维码内容。 */
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

    /** 二维码里要编码的字符串。 */
    public static String qrContent(String key) {
        return "https://music.163.com/login?codekey=" + key;
    }

    /**
     * 轮询扫码结果。服务端约定：800 过期 / 801 待扫码 / 802 已扫码待确认 / 803 成功。
     *
     * @param sessionFile 登录成功时把 Cookie 落盘到这里
     */
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
                            // Set-Cookie 已经在 post 的 captureSetCookies 里收进 COOKIES 了，直接落盘即可
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
    // 网络
    // ------------------------------------------------------------------

    private static CompletableFuture<JsonElement> post(String path, String jsonBody) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // body 里补 csrf_token（从 __csrf Cookie 来，登录类接口依赖它）。
                String csrf = COOKIES.getOrDefault("__csrf", "");
                JsonObject parsed = JsonParser.parseString(jsonBody).getAsJsonObject();
                if (!parsed.has("csrf_token")) {
                    parsed.addProperty("csrf_token", csrf);
                }
                String body = NeteaseCrypto.encrypt(parsed.toString());
                String url = BASE + path + "?csrf_token=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8);
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
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
    // 解析
    // ------------------------------------------------------------------

    /** 从 {@code {"yrc":{"version":1,"lyric":"..."}}} 这类结构里取出 lyric 文本。 */
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
            // yrc 开头混着「作词 : xxx / 作曲 : xxx」这类元数据行 —— 它们不唱歌，
            // 留在列表里会让前面多出几行、整体看起来歌词错位一截。
            if (words.isEmpty() || isMetadataLine(lineText)) {
                continue;
            }
            out.add(new LyricLine(start, lineText, words));
        }
        return out;
    }

    /** 词级元数据行：歌词开头那些署名行。 */
    private static boolean isMetadataLine(String text) {
        String lower = text.toLowerCase();
        return (text.contains(":") || text.contains("："))
                && (lower.contains("作词") || lower.contains("作曲")
                || lower.contains("编曲") || lower.contains("填词")
                || lower.contains("作词 :") || lower.contains("作曲 :") || lower.contains("lyric")
                || lower.contains("compos") || lower.contains("arrang"));
    }

    /**
     * 行级 LRC。
     *
     * <p>两个易踩的坑都在这处理了：
     * <ul>
     *   <li>多时间戳行 {@code [00:12.00][00:15.00]词} —— 用 {@code lookingAt} 逐个吞时间戳，
     *       每个时间戳生成一行；不能拿 {@code matches} 一次匹配，那样第二个时间戳会被 {@code (.*)}
     *       吃进歌词文本里。</li>
     *   <li>{@code [offset:+500]} 全局偏移标签。</li>
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
                // 正 offset 表示歌词提前出现，时间戳要减
                out.add(new LyricLine(Math.max(0, t - offsetMs), text, null));
            }
        }
        out.sort((a, b) -> Long.compare(a.timeMs(), b.timeMs()));
        return out;
    }

    private static String optString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    /**
     * 专辑字段的取法。
     *
     * <p>网易有两套命名：新接口（搜索结果、{@code v3/song/detail}）用缩写 {@code al}，
     * 老接口用全称 {@code album}。之前只认后者，所以封面和专辑名一直拿不到。
     */
    private static JsonObject firstAlbum(JsonObject song) {
        if (song.has("al") && song.get("al").isJsonObject()) {
            return song.getAsJsonObject("al");
        }
        if (song.has("album") && song.get("album").isJsonObject()) {
            return song.getAsJsonObject("album");
        }
        return null;
    }

    /** 时长字段同样有两套：新接口 {@code duration}，老接口 {@code dt}。 */
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
