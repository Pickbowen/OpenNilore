package shit.lizz.network.webui;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import shit.lizz.LizzClient;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.utils.render.TextureUtil;

public class ModulesHandler extends AbstractHttpHandler {
    private final Gson gson = new Gson();

    @Override
    public int handleRequest(InputStream in, OutputStream out, HttpExchange exchange) throws Throwable {
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);

        Category filter = null;
        try {
            Map<String, String> query = TextureUtil.parseQueryString(exchange.getRequestURI().getQuery());
            String requested = query.get("category");
            if (requested != null && !"null".equalsIgnoreCase(requested)) {
                filter = Category.fromString(requested);
            }
        } catch (Throwable ignored) {
        }

        Map<String, Map<String, Object>> result = new HashMap<>();
        for (Module module : LizzClient.getInstance().getModuleManager().getModules()) {
            if (filter != null && module.getCategory() != filter) {
                continue;
            }
            Map<String, Object> entry = new HashMap<>();
            entry.put("desc", "");
            entry.put("state", module.isEnabled());
            entry.put("settings", !module.getSettings().isEmpty());
            result.put(module.getName(), entry);
        }
        response.put("result", result);
        writer.write(this.gson.toJson(response));
        writer.flush();
        return 200;
    }
}
