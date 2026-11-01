package client.nilore.modules.impl.misc.music;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class MusicHttp {
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";
    private static final int MAX_ATTEMPTS = 2;
    private static final Duration TIMEOUT = Duration.ofSeconds(6);
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private MusicHttp() {
    }

    public static CompletableFuture<String> getStringAsync(URI uri) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return send(request(uri), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
            } catch (IOException e) {
                throw new CompletionException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
        });
    }

    public static byte[] getBytes(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://music.163.com")
                .GET()
                .build();
        return send(request, HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    public static StreamResponse getInputStream(URI uri) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = send(request(uri), HttpResponse.BodyHandlers.ofInputStream());
        return new StreamResponse(response.body(), response.headers());
    }

    private static HttpRequest request(URI uri) {
        return HttpRequest.newBuilder()
                .uri(uri)
                .timeout(TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
    }

    private static <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<T> response = CLIENT.send(request, bodyHandler);
                if (isSuccess(response.statusCode())) {
                    return response;
                }
                closeIfStream(response.body());
                lastFailure = new IOException("HTTP " + response.statusCode() + " for " + request.uri());
                if (!isRetryable(response.statusCode())) {
                    throw new HttpStatusException(response.statusCode(), request.uri());
                }
            } catch (HttpStatusException e) {
                throw e;
            } catch (IOException e) {
                lastFailure = e;
            }

            if (attempt < MAX_ATTEMPTS) {
                Thread.sleep(400L);
            }
        }
        throw lastFailure == null ? new IOException("Request failed for " + request.uri()) : lastFailure;
    }

    private static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private static boolean isRetryable(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode == 500
                || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    private static void closeIfStream(Object body) {
        if (body instanceof InputStream inputStream) {
            try {
                inputStream.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class HttpStatusException extends IOException {
        private HttpStatusException(int statusCode, URI uri) {
            super("HTTP " + statusCode + " for " + uri);
        }
    }

    public record StreamResponse(InputStream body, HttpHeaders headers) {
    }
}
