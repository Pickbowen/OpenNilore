package shit.lizz.manager;

import java.util.HashMap;
import java.util.Map;
import shit.lizz.LizzClient;
import shit.lizz.command.Command;
import shit.lizz.command.impl.BindCommand;
import shit.lizz.command.impl.ConfigCommand;
import shit.lizz.command.impl.InfoCommand;
import shit.lizz.command.impl.LanguageCommand;
import shit.lizz.command.impl.ToggleCommand;
import shit.lizz.event.impl.ChatEvent;
import shit.lizz.utils.misc.ChatUtil;
import shit.lizz.event.EventTarget;

public class CommandManager {
    public static final String PREFIX = ".";
    public final Map<String, Command> aliasMap = new HashMap<>();

    public CommandManager() {
        LizzClient.getInstance().getEventBus().register(this);
    }

    public void initCommands() {
        this.registerCommand(new BindCommand());
        this.registerCommand(new ConfigCommand());
        this.registerCommand(new LanguageCommand());
        this.registerCommand(new ToggleCommand());
        this.registerCommand(new InfoCommand());
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
