package client.nilore.manager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
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

    private String lastTabInput;
    private List<String> lastTabCandidates = Collections.emptyList();
    private int lastTabIndex = -1;

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

    /**
     * Resolves the next tab completion for a chat message. Repeated calls with the same
     * input cycle through the available candidates.
     *
     * @return the completed message, or {@code null} when there is nothing to offer.
     */
    public String tabComplete(String input) {
        if (input == null || !input.startsWith(PREFIX)) {
            this.resetTabState();
            return null;
        }
        if (input.equals(this.lastTabInput) && !this.lastTabCandidates.isEmpty()) {
            this.lastTabIndex = (this.lastTabIndex + 1) % this.lastTabCandidates.size();
            return this.lastTabCandidates.get(this.lastTabIndex);
        }
        List<String> candidates = this.getCandidates(input);
        if (candidates.isEmpty()) {
            this.resetTabState();
            return null;
        }
        this.lastTabInput = input;
        this.lastTabCandidates = candidates;
        this.lastTabIndex = 0;
        return candidates.get(0);
    }

    private void resetTabState() {
        this.lastTabInput = null;
        this.lastTabCandidates = Collections.emptyList();
        this.lastTabIndex = -1;
    }

    /**
     * All completion candidates for the given chat message, in display order. Used both for
     * cycling on Tab and for the suggestion popup above the input box.
     */
    public List<String> getCandidates(String input) {
        if (input == null || !input.startsWith(PREFIX)) {
            return Collections.emptyList();
        }
        return this.buildCandidates(input);
    }

    private List<String> buildCandidates(String input) {
        String body = input.substring(PREFIX.length());
        boolean trailingSpace = body.endsWith(" ");
        String[] parts = body.split(" ", -1);
        if (parts.length == 0 || parts.length == 1 && !trailingSpace) {
            String partial = parts.length == 0 ? "" : parts[0].toLowerCase(Locale.ROOT);
            return this.aliasMap.keySet().stream()
                    .filter(key -> key.startsWith(partial))
                    .distinct()
                    .sorted()
                    .map(key -> PREFIX + key)
                    .collect(Collectors.toList());
        }
        Command command = this.aliasMap.get(parts[0].toLowerCase(Locale.ROOT));
        if (command == null) {
            return Collections.emptyList();
        }
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);
        if (args.length == 0) {
            args = new String[]{""};
        }
        String[] offered = command.onTab(args);
        if (offered == null || offered.length == 0) {
            return Collections.emptyList();
        }
        String partialToken = trailingSpace ? "" : args[args.length - 1];
        StringBuilder head = new StringBuilder(PREFIX).append(parts[0]).append(' ');
        for (int i = 0; i < args.length - 1; ++i) {
            head.append(args[i]).append(' ');
        }
        List<String> result = new ArrayList<>();
        for (String candidate : offered) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            if (!candidate.toLowerCase(Locale.ROOT).startsWith(partialToken.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String completion = head + candidate;
            if (!result.contains(completion)) {
                result.add(completion);
            }
        }
        return result;
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
