package shit.lizz.network.webui;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import shit.lizz.utils.misc.Assets;

public class StaticFileHandler extends AbstractHttpHandler {
    private final String resourcePath;
    private final String urlPrefix;

    public StaticFileHandler(String resourcePath, String urlPrefix) {
        this.resourcePath = resourcePath;
        this.urlPrefix = urlPrefix;
    }

    @Override
    public int handleRequest(InputStream in, OutputStream out, HttpExchange exchange) throws Throwable {
        String requestPath = exchange.getRequestURI().getPath();
        if (!requestPath.startsWith(this.urlPrefix)) {
            return 404;
        }
        String relative = requestPath.substring(this.urlPrefix.length());
        relative = URLDecoder.decode(relative, StandardCharsets.UTF_8);
        if (relative.isEmpty() || relative.endsWith("/")) {
            relative += "index.html";
        }
        String classpath = this.resourcePath + "/" + relative;
        InputStream resource = Assets.open(classpath);
        if (resource == null) {
            return 404;
        }
        try (resource) {
            exchange.getResponseHeaders().add("Content-Type", guessContentType(relative));
            resource.transferTo(out);
        }
        return 200;
    }

    @Override
    public void sendResponse(int status, OutputStream out, HttpExchange exchange) throws IOException {
        InputStream resource = Assets.open(this.resourcePath + "/" + status + ".html");
        if (resource != null) {
            try (resource) {
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                resource.transferTo(out);
            }
        }
    }

    private static String guessContentType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }
}
