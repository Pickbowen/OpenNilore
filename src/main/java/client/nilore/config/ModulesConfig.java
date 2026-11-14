package client.nilore.config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import client.nilore.NiloreClient;
import client.nilore.config.Config;
import client.nilore.exception.ModuleNotFoundException;
import client.nilore.manager.ModuleManager;
import client.nilore.modules.Module;

public class ModulesConfig
extends Config {
    private static final Logger LOGGER = LogManager.getLogger(ModulesConfig.class);

    public ModulesConfig() {
        super("modules.cfg");
    }

    @Override
    public void read(BufferedReader bufferedReader) throws IOException {
        String line;
        ModuleManager moduleManager = NiloreClient.getInstance().getModuleManager();
        while ((line = bufferedReader.readLine()) != null) {
            String[] parts = line.split(":", 3);
            if (parts.length != 3) {
                LOGGER.error("Failed to read line {}!", line);
                continue;
            }
            String moduleName = parts[0];
            try {
                Module module = moduleManager.getModule(moduleName);
                module.setKey(Integer.parseInt(parts[1]));
                module.setEnabled(Boolean.parseBoolean(parts[2]));
            } catch (ModuleNotFoundException ex) {
                LOGGER.error("Failed to find module {}!", moduleName);
            } catch (RuntimeException ex) {
                // Per-line isolation: a malformed key code used to throw NumberFormatException out
                // of this method, dropping the rest of the file and aborting ConfigManager.loadAll
                // before ValuesConfig ever ran.
                LOGGER.error("Failed to read line {}!", line, ex);
            }
        }
    }

    @Override
    public void save(BufferedWriter bufferedWriter) throws IOException {
        ModuleManager moduleManager = NiloreClient.getInstance().getModuleManager();
        ArrayList<Module> moduleList = new ArrayList<>(moduleManager.getModules());
        for (Module module : moduleList) {
            bufferedWriter.write(String.format((String)"%s:%d:%s\n", (Object[])new Object[]{module.getName(), module.getKey(), module.isEnabled()}));
        }
    }
}