package shit.nilore.network.webui;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import shit.nilore.NiloreClient;
import shit.nilore.exception.ModuleNotFoundException;
import shit.nilore.modules.Module;
import shit.nilore.settings.Setting;
import shit.nilore.settings.impl.BooleanSetting;
import shit.nilore.settings.impl.ModeSetting;
import shit.nilore.settings.impl.NumberSetting;
import shit.nilore.utils.render.TextureUtil;

public class SettingsHandler extends AbstractHttpHandler {

    @Override
    public int handleRequest(InputStream in, OutputStream out, HttpExchange exchange) throws Throwable {
        Map<String, String> query = TextureUtil.parseQueryString(exchange.getRequestURI().getQuery());
        Map<String, Object> response = new HashMap<>();
        boolean success = false;
        String reason = null;
        if (query.containsKey("module")) {
            try {
                Module module = lookupModule(query.get("module"));
                if (module == null) {
                    reason = "找不到模块";
                } else {
                    List<Setting<?>> settings = module.getSettings();
                    List<Map<String, Object>> entries = new ArrayList<>();
                    for (Setting<?> setting : settings) {
                        Map<String, Object> entry = new HashMap<>();
                        entry.put("name", setting.getName());
                        entry.put("displayName", setting.getName());
                        if (setting instanceof NumberSetting numberSetting) {
                            entry.put("type", "slider");
                            entry.put("max", numberSetting.getMax());
                            entry.put("min", numberSetting.getMin());
                            entry.put("step", numberSetting.getStep());
                            entry.put("value", numberSetting.getValue());
                        } else if (setting instanceof BooleanSetting) {
                            entry.put("type", "checkbox");
                            entry.put("value", setting.getValue());
                        } else if (setting instanceof ModeSetting modeSetting) {
                            entry.put("type", "selection");
                            entry.put("value", modeSetting.getValue());
                            entry.put("values", modeSetting.getModes());
                        }
                        entries.add(entry);
                    }
                    response.put("result", entries);
                    success = true;
                }
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                success = false;
                reason = throwable.toString();
            }
        } else {
            reason = "参数不足";
        }
        response.put("success", success);
        response.put("reason", reason);
        out.write(new Gson().toJson(response).getBytes(StandardCharsets.UTF_8));
        return 200;
    }

    private static Module lookupModule(String name) {
        try {
            return NiloreClient.getInstance().getModuleManager().getModule(name);
        } catch (ModuleNotFoundException e) {
            return null;
        }
    }
}
