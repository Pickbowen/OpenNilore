package client.nilore.utils.misc;

import java.util.Locale;
import lombok.Generated;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import client.nilore.ClientBase;
import client.nilore.NiloreClient;

public final class ChatUtil
extends ClientBase {
    private static final String PREFIX = "§7[§b"
            + NiloreClient.CLIENT_NAME.substring(0, 1).toUpperCase(Locale.ROOT)
            + "§7] ";

    public static void addMessage(Component component) {
        ChatComponent chatComponent = mc.gui.getChat();
        chatComponent.addMessage(component);
    }

    public static void print(String message) {
        ChatUtil.print(true, message);
    }

    public static void print(boolean withPrefix, String message) {
        ChatUtil.addMessage(Component.nullToEmpty((withPrefix ? PREFIX : "") + message));
    }

    @Generated
    private ChatUtil() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}