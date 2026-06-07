package shit.lizz.config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import shit.lizz.LizzClient;
import shit.lizz.config.Config;
import shit.lizz.exception.ModuleNotFoundException;
import shit.lizz.manager.ModuleManager;
import shit.lizz.modules.Module;

public class ModulesConfig
extends Config {
    private static final Logger LOGGER = LogManager.getLogger(ModulesConfig.class);

    public ModulesConfig() {
        super("modules.cfg");
    }

    @Override
    public void read(BufferedReader bufferedReader) throws IOException {
        String line;
        ModuleManager moduleManager = LizzClient.getInstance().getModuleManager();
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
        ModuleManager moduleManager = LizzClient.getInstance().getModuleManager();
        ArrayList<Module> moduleList = new ArrayList<>(moduleManager.getModules());
        for (Module module : moduleList) {
            bufferedWriter.write(String.format((String)"%s:%d:%s\n", (Object[])new Object[]{module.getName(), module.getKey(), module.isEnabled()}));
        }
    }
}