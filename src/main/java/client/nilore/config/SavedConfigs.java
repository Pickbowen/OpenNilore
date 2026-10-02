package client.nilore.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import client.nilore.NiloreClient;
import client.nilore.exception.ModuleNotFoundException;
import client.nilore.hud.HudElement;
import client.nilore.manager.ConfigManager;
import client.nilore.manager.ModuleManager;
import client.nilore.modules.Module;
import client.nilore.settings.Setting;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.MultiSelectSetting;
import client.nilore.settings.impl.NumberSetting;

public final class SavedConfigs {
    private static final Logger LOGGER = LogManager.getLogger("SavedConfigs");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String EXTENSION = ".json";
    private static final String HUD_X = "hudX";
    private static final String HUD_Y = "hudY";

    public static File directory() {
        File dir = ConfigManager.SAVED_CONFIGS_DIR;
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static boolean isValidName(String name) {
        return name != null
                && !name.isBlank()
                && name.length() <= 64
                && !name.contains("/")
                && !name.contains("\\")
                && !name.contains("..");
    }

    public static File file(String name) {
        return new File(directory(), name + EXTENSION);
    }

    public static List<String> list() {
        String[] files = directory().list((dir, fileName) -> fileName.toLowerCase(Locale.ROOT).endsWith(EXTENSION));
        if (files == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String fileName : files) {
            names.add(fileName.substring(0, fileName.length() - EXTENSION.length()));
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public static boolean save(String name) {
        JsonObject modules = new JsonObject();
        JsonObject values = new JsonObject();
        for (Module module : NiloreClient.getInstance().getModuleManager().getModules()) {
            modules.addProperty(module.getName(), module.isEnabled());
            JsonObject settings = new JsonObject();
            if (module instanceof HudElement hudElement) {
                settings.addProperty(HUD_X, hudElement.getX());
                settings.addProperty(HUD_Y, hudElement.getY());
            }
            for (Setting<?> setting : module.getSettings()) {
                try {
                    setting.save(settings);
                } catch (Exception exception) {
                    LOGGER.warn("Failed to save setting {} of {}", setting.getName(), module.getName(), exception);
                }
            }
            values.add(module.getName(), settings);
        }
        JsonObject root = new JsonObject();
        root.add("modules", modules);
        root.add("values", values);
        try {
            Files.writeString(file(name).toPath(), GSON.toJson(root), StandardCharsets.UTF_8);
            return true;
        } catch (IOException exception) {
            LOGGER.error("Failed to save config {}", name, exception);
            return false;
        }
    }

    public enum LoadResult {
        SUCCESS,
        NOT_FOUND,
        INVALID
    }

    public static LoadResult load(String name) {
        File configFile = file(name);
        if (!configFile.exists()) {
            return LoadResult.NOT_FOUND;
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(configFile.toPath(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Exception exception) {
            LOGGER.error("Failed to parse config {}", name, exception);
            return LoadResult.INVALID;
        }
        JsonElement modulesElement = root.get("modules");
        JsonElement valuesElement = root.get("values");
        if (modulesElement == null && valuesElement == null) {
            LOGGER.error("Failed to load config {}: no modules or values section", name);
            return LoadResult.INVALID;
        }
        if ((modulesElement != null && !modulesElement.isJsonObject())
                || (valuesElement != null && !valuesElement.isJsonObject())) {
            LOGGER.error("Failed to load config {}: malformed section", name);
            return LoadResult.INVALID;
        }

        ModuleManager moduleManager = NiloreClient.getInstance().getModuleManager();
        if (modulesElement != null) {
            for (Map.Entry<String, JsonElement> entry : modulesElement.getAsJsonObject().entrySet()) {
                Module module = findModule(moduleManager, entry.getKey());
                if (module == null || !isBoolean(entry.getValue())) {
                    continue;
                }
                try {
                    boolean enabled = entry.getValue().getAsBoolean();
                    if (module.isEnabled() != enabled) {
                        module.setEnabled(enabled);
                    }
                } catch (Exception exception) {
                    LOGGER.warn("Failed to load state of {}", module.getName(), exception);
                }
            }
        }
        if (valuesElement != null) {
            for (Map.Entry<String, JsonElement> entry : valuesElement.getAsJsonObject().entrySet()) {
                Module module = findModule(moduleManager, entry.getKey());
                if (module == null || !entry.getValue().isJsonObject()) {
                    continue;
                }
                applyValues(module, entry.getValue().getAsJsonObject());
            }
        }
        return LoadResult.SUCCESS;
    }

    private static void applyValues(Module module, JsonObject values) {
        for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            if (module instanceof HudElement hudElement && isNumber(entry.getValue())) {
                if (HUD_X.equals(entry.getKey())) {
                    hudElement.setX(entry.getValue().getAsFloat());
                    continue;
                }
                if (HUD_Y.equals(entry.getKey())) {
                    hudElement.setY(entry.getValue().getAsFloat());
                    continue;
                }
            }
            for (Setting<?> setting : module.getSettings()) {
                if (!setting.getName().equals(entry.getKey())) {
                    continue;
                }
                if (!matchesType(setting, entry.getValue())) {
                    LOGGER.warn("Skipped setting {} of {}: type mismatch", setting.getName(), module.getName());
                    break;
                }
                try {
                    setting.load(entry.getValue());
                } catch (Exception exception) {
                    LOGGER.warn("Failed to load setting {} of {}", setting.getName(), module.getName(), exception);
                }
                break;
            }
        }
    }

    private static boolean matchesType(Setting<?> setting, JsonElement element) {
        if (setting instanceof BooleanSetting) {
            return isBoolean(element);
        }
        if (setting instanceof NumberSetting) {
            return isNumber(element);
        }
        if (setting instanceof ModeSetting) {
            return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
        }
        if (setting instanceof MultiSelectSetting) {
            return element.isJsonArray();
        }
        return false;
    }

    private static boolean isBoolean(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean();
    }

    private static boolean isNumber(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
    }

    private static Module findModule(ModuleManager moduleManager, String name) {
        try {
            return moduleManager.getModule(name.replace(" ", ""));
        } catch (ModuleNotFoundException exception) {
            return null;
        }
    }

    private SavedConfigs() {
    }
}
