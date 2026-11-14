package client.nilore.manager;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import client.nilore.NiloreClient;
import client.nilore.config.AtomicWrite;
import client.nilore.config.Config;
import client.nilore.config.ModulesConfig;
import client.nilore.config.ValuesConfig;

public class ConfigManager {
    public static final Logger LOGGER = LogManager.getLogger("ConfigManager");
    public static final File CONFIG_DIR = new File(NiloreClient.configDir, "configs");
    public static final File SAVED_CONFIGS_DIR = new File(NiloreClient.configDir, "saved_configs");
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
            File file = config.getFile();
            if (!file.exists()) {
                continue;
            }
            // Per-file isolation: config.read() can throw runtime exceptions on malformed data
            // (Integer.parseInt / ClassCastException / ArrayIndexOutOfBounds). Catching only
            // IOException used to abort the whole loop, so one bad line in modules.cfg left
            // values.cfg unread while moduleInit was already set and never retried.
            try {
                readConfigFile(config, file);
            } catch (Exception e) {
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

    /**
     * Writes through a sibling .tmp file and keeps the previous content as .bak.
     * Writing the target in place meant a crash mid-write left a truncated file that the
     * next launch silently treated as "no settings", losing the whole configuration.
     */
    private void saveConfig(Config config) {
        try {
            AtomicWrite.writeAtomically(config.getFile().toPath(), config::save);
        } catch (Exception e) {
            LOGGER.error("Failed to save config " + config.getName(), e);
        }
    }
}
