package shit.nilore.utils.misc;

import lombok.Generated;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import shit.nilore.ClientBase;

public final class ChatUtil
extends ClientBase {
    public static void addMessage(Component component) {
        ChatComponent chatComponent = mc.gui.getChat();
        chatComponent.addMessage(component);
    }

    public static void print(String message) {
        ChatUtil.print(true, message);
    }

    public static void print(boolean withPrefix, String message) {
        ChatUtil.addMessage(Component.nullToEmpty((withPrefix ? "§7[§b§7] " : "") + message));
    }

    @Generated
    private ChatUtil() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}