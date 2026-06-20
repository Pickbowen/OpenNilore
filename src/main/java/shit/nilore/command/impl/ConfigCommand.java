package shit.nilore.command.impl;

import java.io.IOException;
import shit.nilore.NiloreClient;
import shit.nilore.command.Command;
import shit.nilore.manager.ConfigManager;
import shit.nilore.utils.misc.ChatUtil;

public class ConfigCommand extends Command {
    public ConfigCommand() {
        super("config", new String[]{"cfg"});
    }

    @Override
    public void onCommand(String[] stringArray) {
        if (stringArray.length == 1) {
            switch (stringArray[0]) {
                case "reload":
                    NiloreClient.getInstance().getConfigManager().loadAll();
                    ChatUtil.print("Config reloaded!");
                    break;
                case "folder":
                    try {
                        Runtime.getRuntime().exec("explorer " + ConfigManager.CONFIG_DIR.getAbsolutePath());
                    } catch (IOException ignored) {
                    }
                    break;
            }
        } else {
            ChatUtil.print("Usage: config reload/folder");
        }
    }

    @Override
    public String[] onTab(String[] stringArray) {
        return new String[0];
    }
}
