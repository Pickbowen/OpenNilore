package client.nilore.manager;

import java.util.HashMap;
import java.util.Map;
import client.nilore.NiloreClient;
import client.nilore.command.Command;
import client.nilore.command.impl.BindCommand;
import client.nilore.command.impl.ConfigCommand;
import client.nilore.command.impl.InfoCommand;
import client.nilore.command.impl.LanguageCommand;
import client.nilore.command.impl.MusicCommand;
import client.nilore.command.impl.ReconnectCommand;
import client.nilore.command.impl.ToggleCommand;
import client.nilore.event.impl.ChatEvent;
import client.nilore.utils.misc.ChatUtil;
import client.nilore.event.EventTarget;

public class CommandManager {
    public static final String PREFIX = ".";
    public final Map<String, Command> aliasMap = new HashMap<>();

    public CommandManager() {
        NiloreClient.getInstance().getEventBus().register(this);
    }

    public void initCommands() {
        this.registerCommand(new BindCommand());
        this.registerCommand(new ConfigCommand());
        this.registerCommand(new LanguageCommand());
        this.registerCommand(new ToggleCommand());
        this.registerCommand(new InfoCommand());
        this.registerCommand(new MusicCommand());
        this.registerCommand(new ReconnectCommand());
    }

    private void registerCommand(Command command) {
        this.aliasMap.put(command.getPrefix().toLowerCase(), command);
        for (String string : command.getAliases()) {
            this.aliasMap.put(string.toLowerCase(), command);
        }
    }

    @EventTarget
    public void onChat(ChatEvent chatEvent) {
        if (chatEvent.getMessage().startsWith(PREFIX)) {
            chatEvent.setCancelled(true);
            String string = chatEvent.getMessage().substring(PREFIX.length());
            String[] stringArray = string.split(" ");
            if (stringArray.length < 1) {
                ChatUtil.print("Unknown command");
                return;
            }
            String alias = stringArray[0].toLowerCase();
            Command command = this.aliasMap.get(alias);
            if (command == null) {
                ChatUtil.print("Unknown command");
                return;
            }
            String[] args = new String[stringArray.length - 1];
            System.arraycopy(stringArray, 1, args, 0, args.length);
            command.onCommand(args);
        }
    }
}
