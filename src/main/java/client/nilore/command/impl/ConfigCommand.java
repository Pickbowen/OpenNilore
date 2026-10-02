package client.nilore.command.impl;

import java.io.IOException;
import java.util.List;
import client.nilore.NiloreClient;
import client.nilore.command.Command;
import client.nilore.config.SavedConfigs;
import client.nilore.manager.ConfigManager;
import client.nilore.utils.misc.ChatUtil;

public class ConfigCommand extends Command {
    public ConfigCommand() {
        super("config", new String[]{"cfg"});
    }

    @Override
    public void onCommand(String[] args) {
        if (args.length == 0) {
            this.printUsage();
            return;
        }
        switch (args[0].toLowerCase()) {
            case "reload":
                NiloreClient.getInstance().getConfigManager().loadAll();
                ChatUtil.print("Config reloaded!");
                break;
            case "save":
                if (args.length < 2) {
                    ChatUtil.print("Usage: config save <name>");
                    return;
                }
                if (!SavedConfigs.isValidName(args[1])) {
                    ChatUtil.print("Invalid config name: " + args[1]);
                    return;
                }
                ChatUtil.print(SavedConfigs.save(args[1])
                        ? "Saved config " + args[1] + "!"
                        : "Failed to save config " + args[1] + ".");
                break;
            case "load":
                if (args.length < 2) {
                    ChatUtil.print("Usage: config load <name>");
                    return;
                }
                if (!SavedConfigs.isValidName(args[1])) {
                    ChatUtil.print("Invalid config name: " + args[1]);
                    return;
                }
                switch (SavedConfigs.load(args[1])) {
                    case SUCCESS:
                        ChatUtil.print("Loaded config " + args[1] + "!");
                        break;
                    case NOT_FOUND:
                        ChatUtil.print("Config " + args[1] + " not found.");
                        break;
                    default:
                        ChatUtil.print("Config " + args[1] + " is corrupted, nothing was applied.");
                }
                break;
            case "list":
                List<String> names = SavedConfigs.list();
                if (names.isEmpty()) {
                    ChatUtil.print("No saved configs.");
                    return;
                }
                ChatUtil.print("Saved configs (" + names.size() + "): §b" + String.join("§7, §b", names));
                break;
            case "folder":
                this.openFolder();
                break;
            default:
                this.printUsage();
        }
    }

    private void openFolder() {
        try {
            Runtime.getRuntime().exec("explorer " + ConfigManager.CONFIG_DIR.getAbsolutePath());
        } catch (IOException ignored) {
        }
    }

    private void printUsage() {
        ChatUtil.print("Usage: config load/save/list/folder/reload");
    }

    @Override
    public String[] onTab(String[] args) {
        return new String[0];
    }
}
