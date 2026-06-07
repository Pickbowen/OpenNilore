package shit.lizz.network.webui;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import shit.lizz.modules.Category;

public class CategoriesHandler extends AbstractHttpHandler {

    @Override
    public int handleRequest(InputStream in, OutputStream out, HttpExchange exchange) throws Throwable {
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        Map<String, Object> response = new HashMap<>();
        List<String> categories = new ArrayList<>();
        response.put("success", true);
        response.put("result", categories);
        for (Category category : Category.values()) {
            categories.add(category.displayName);
        }
        writer.write(new Gson().toJson(response));
        writer.flush();
        return 200;
    }
}
