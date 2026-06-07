package shit.lizz.network.webui;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import shit.lizz.LizzClient;
import shit.lizz.exception.ModuleNotFoundException;
import shit.lizz.modules.Module;
import shit.lizz.modules.impl.world.WebUI;
import shit.lizz.settings.Setting;
import shit.lizz.settings.impl.BooleanSetting;
import shit.lizz.settings.impl.ModeSetting;
import shit.lizz.settings.impl.NumberSetting;
import shit.lizz.utils.render.TextureUtil;

public class SetSettingHandler extends AbstractHttpHandler {

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public int handleRequest(InputStream in, OutputStream out, HttpExchange exchange) throws Throwable {
        Map<String, String> query = TextureUtil.parseQueryString(exchange.getRequestURI().getQuery());
        Map<String, Object> response = new HashMap<>();
        boolean success = false;
        String reason = null;
        Object result = null;
        if (query.containsKey("module") && query.containsKey("name") && query.containsKey("value")) {
            try {
                Module module = lookupModule(query.get("module"));
                if (module == null) {
                    reason = "找不到模块";
                } else if (module instanceof WebUI) {
                    reason = "sb";
                } else {
                    String settingName = query.get("name");
                    Optional<Setting<?>> match = module.getSettings().stream()
                            .filter(setting -> setting.getName().equals(settingName))
                            .findFirst();
                    if (match.isEmpty()) {
                        reason = "找不到参数";
                    } else {
                        Setting setting = match.get();
                        String raw = query.get("value");
                        if (setting instanceof NumberSetting) {
                            try {
                                setting.setValue(Double.valueOf(raw));
                                success = true;
                            } catch (NumberFormatException ignored) {
                            }
                        } else if (setting instanceof BooleanSetting) {
                            setting.setValue(Boolean.valueOf(raw));
                            success = true;
                        } else if (setting instanceof ModeSetting modeSetting) {
                            String matchedMode = Stream.of(modeSetting.getModes())
                                    .filter(mode -> mode.equals(raw))
                                    .findFirst()
                                    .orElse(null);
                            if (matchedMode != null) {
                                modeSetting.setValue(matchedMode);
                                success = true;
                            }
                        }
                        if (success) {
                            result = setting.getValue();
                        } else {
                            reason = "无效的值";
                        }
                    }
                }
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                success = false;
                reason = throwable.toString();
            }
        } else {
            result = false;
            reason = "参数不足";
        }
        response.put("success", success);
        response.put("reason", reason);
        response.put("result", result);
        out.write(new Gson().toJson(response).getBytes(StandardCharsets.UTF_8));
        return 200;
    }

    private static Module lookupModule(String name) {
        try {
            return LizzClient.getInstance().getModuleManager().getModule(name);
        } catch (ModuleNotFoundException e) {
            return null;
        }
    }
}
