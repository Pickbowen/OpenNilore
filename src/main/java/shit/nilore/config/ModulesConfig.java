package shit.nilore.config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import shit.nilore.NiloreClient;
import shit.nilore.config.Config;
import shit.nilore.exception.ModuleNotFoundException;
import shit.nilore.manager.ModuleManager;
import shit.nilore.modules.Module;

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
            int keyCode = Integer.parseInt(parts[1]);
            boolean enabled = Boolean.parseBoolean(parts[2]);
            try {
                Module module = moduleManager.getModule(moduleName);
                module.setKey(keyCode);
                module.setEnabled(enabled);
            } catch (ModuleNotFoundException ex) {
                LOGGER.error("Failed to find module {}!", moduleName);
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