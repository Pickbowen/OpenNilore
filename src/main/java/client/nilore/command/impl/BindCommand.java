package client.nilore.command.impl;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Locale;
import client.nilore.NiloreClient;
import client.nilore.command.Command;
import client.nilore.event.EventTarget;
import client.nilore.exception.ModuleNotFoundException;
import client.nilore.modules.KeyBind;
import client.nilore.modules.Module;
import client.nilore.utils.misc.ChatUtil;

public class BindCommand
extends Command {
    public static final class EventHandler {
        private final BindCommand parent;
        private final Module module;
        private final String name;

        public EventHandler(BindCommand parent, Module module, String name) {
            this.parent = parent;
            this.module = module;
            this.name = name;
        }

        @EventTarget
        public void onKey(client.nilore.event.impl.KeyEvent event) {
            if (!event.isPressed()) return;
            int keyCode = event.getKeyCode();
            if (keyCode == 256) {
                module.setKey(0);
                ChatUtil.print("Unbound " + this.name + ".");
            } else {
                module.setKey(keyCode);
                ChatUtil.print("Bound " + this.name + " to " + new KeyBind(keyCode).getName().toUpperCase() + ".");
            }
            NiloreClient.getInstance().getEventBus().unregister(this);
            NiloreClient.getInstance().getConfigManager().saveAll();
        }
    }

    public BindCommand() {
        super("bind", new String[]{"b"});
    }

    @Override
    public void onCommand(String[] args) {
        if (args.length == 1) {
            this.handleBindInteractive(args[0]);
        } else if (args.length == 2) {
            this.handleBindExplicit(args[0], args[1]);
        } else {
            ChatUtil.print("Usage: .bind <module> [key]");
        }
    }

    private void handleBindInteractive(String moduleName) {
        try {
            Module module = NiloreClient.getInstance().getModuleManager().getModule(moduleName);
            if (module == null) {
                ChatUtil.print("Invalid module.");
                return;
            }
            ChatUtil.print("Press a key to bind " + moduleName + " to.");
            NiloreClient.getInstance().getEventBus().register(new BindCommand.EventHandler(this, module, moduleName));
        } catch (ModuleNotFoundException e) {
            ChatUtil.print("Invalid module.");
        }
    }

    private void handleBindExplicit(String moduleName, String keyName) {
        try {
            Module module = NiloreClient.getInstance().getModuleManager().getModule(moduleName);
            if (module == null) {
                ChatUtil.print("Invalid module.");
                return;
            }
            if (keyName.equalsIgnoreCase("none")) {
                module.setKey(InputConstants.UNKNOWN.getValue());
                ChatUtil.print("Unbound " + moduleName + ".");
                NiloreClient.getInstance().getConfigManager().saveAll();
                return;
            }
            int mouseButton = parseMouseButton(keyName);
            if (mouseButton >= 0) {
                module.setKey(KeyBind.mouseCode(mouseButton));
                ChatUtil.print("Bound " + moduleName + " to " + KeyBind.mouseName(mouseButton) + ".");
                NiloreClient.getInstance().getConfigManager().saveAll();
                return;
            }
            InputConstants.Key key = resolveKey(keyName);
            if (key == InputConstants.UNKNOWN) {
                ChatUtil.print("Invalid key.");
                return;
            }
            module.setKey(key.getValue());
            ChatUtil.print("Bound " + moduleName + " to " + new KeyBind(key.getValue()).getName().toUpperCase() + ".");
            NiloreClient.getInstance().getConfigManager().saveAll();
        } catch (ModuleNotFoundException e) {
            ChatUtil.print("Invalid module.");
        }
    }

    private static int parseMouseButton(String name) {
        String text = name.toLowerCase(Locale.ROOT).trim();
        if (text.startsWith("mouse")) {
            text = text.substring(5);
        } else if (text.startsWith("m")) {
            text = text.substring(1);
        } else {
            return -1;
        }
        if (text.length() != 1) {
            return -1;
        }
        char digit = text.charAt(0);
        if (digit < '1' || digit > '8') {
            return -1;
        }
        return digit - '1';
    }

    private static InputConstants.Key resolveKey(String name) {
        String text = name.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
        InputConstants.Key key = InputConstants.getKey("key.keyboard." + text);
        if (key != InputConstants.UNKNOWN) {
            return key;
        }
        key = InputConstants.getKey("key.keyboard." + text.replace('_', '.'));
        if (key != InputConstants.UNKNOWN) {
            return key;
        }
        String compact = text.replace(".", "").replace("_", "");
        String[][] modifiers = {{"shift", "shift"}, {"control", "control"}, {"ctrl", "control"}, {"alt", "alt"}, {"super", "super"}, {"meta", "super"}};
        for (String[] modifier : modifiers) {
            if (!compact.endsWith(modifier[0])) {
                continue;
            }
            String side = compact.substring(0, compact.length() - modifier[0].length());
            if (side.equals("r")) {
                side = "right";
            } else if (side.equals("l")) {
                side = "left";
            }
            if (side.isEmpty()) {
                continue;
            }
            key = InputConstants.getKey("key.keyboard." + side + "." + modifier[1]);
            if (key != InputConstants.UNKNOWN) {
                return key;
            }
        }
        return InputConstants.UNKNOWN;
    }

    @Override
    public String[] onTab(String[] stringArray) {
        return NiloreClient.getInstance().getModuleManager().getModules().stream().map(Module::getName).filter(string -> string.toLowerCase().startsWith(stringArray.length == 0 ? "" : stringArray[0].toLowerCase())).toArray(String[]::new);
    }
}