package shit.lizz.manager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import shit.lizz.LizzClient;
import shit.lizz.config.Config;
import shit.lizz.config.ModulesConfig;
import shit.lizz.config.ValuesConfig;

public class ConfigManager {
    public static final Logger LOGGER = LogManager.getLogger("ConfigManager");
    public static final File CONFIG_DIR = new File(LizzClient.configDir, "configs");
    private final List<Config> configs;

    public ConfigManager() {
        this.configs = new ArrayList<>();
        if (!CONFIG_DIR.exists() && CONFIG_DIR.mkdir()) {
            LOGGER.info("Created config directory");
        }
        this.configs.add(new ModulesConfig());
        this.configs.add(new ValuesConfig());
    }

    public void loadAll() {
        for (Config config : this.configs) {
            try {
                File file = config.getFile();
                if (file.exists()) {
                    readConfigFile(config, file);
                }
            } catch (IOException e) {
                LOGGER.error("Failed to load config " + config.getName(), e);
            }
        }
    }

    private void readConfigFile(Config config, File file) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8))) {
            config.read(reader);
        }
    }

    public void saveAll() {
        for (Config config : this.configs) {
            this.saveConfig(config);
        }
        LOGGER.info("Saved all configs");
    }

    private void saveConfig(Config config) {
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(Files.newOutputStream(config.getFile().toPath()), StandardCharsets.UTF_8))) {
            config.save(writer);
        } catch (IOException e) {
            LOGGER.error("Failed to save config " + config.getName(), e);
        }
    }
}
