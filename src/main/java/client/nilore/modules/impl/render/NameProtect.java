package client.nilore.modules.impl.render;

import java.util.ArrayList;
import java.util.Random;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.StringUtils;
import client.nilore.NiloreClient;
import client.nilore.event.impl.ChatReceiveEvent;
import client.nilore.event.impl.DisconnectEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.event.EventTarget;

public class NameProtect
extends Module {
    public static NameProtect INSTANCE;
    private final ModeSetting modeSetting = new ModeSetting("Mode", "Fixed", "Random","Hidden").withDefault("Hidden");
    private String cachedRandomName = null;
    private final Random random = new Random();

    public NameProtect() {
        super("NameProtect", Category.RENDER);
        this.setEnabled(true);
        INSTANCE = this;
    }

    @EventTarget
    public void onDisconnect(DisconnectEvent disconnectEvent) {
        if (this.modeSetting.is("Random")) {
            this.cachedRandomName = null;
        }
    }

    public static String replacePlayerName(String string) {
        if (INSTANCE == null || !INSTANCE.isEnabled()) {
            return string;
        }
        if (mc.player == null) {
            return string;
        }
        String string2 = mc.player.getName().getString();
        // Hidden needs no lookups at all; the random branch used to run its online-player scan on
        // every call, which the player list does once per entry per tick.
        String string3 = INSTANCE.modeSetting.is("Hidden")
                ? "Hidden"
                : INSTANCE.generateRandomName();
        if (string3 != null && !string3.equals(string2) && string.contains(string2)) {
            return StringUtils.replace(string, string2, string3);
        }
        return string;
    }

    public static String getProtectedName() {
        if (mc.player == null || INSTANCE == null || !INSTANCE.isEnabled()) {
            return mc.player != null ? mc.player.getName().getString() : "Player";
        }
        String string = mc.player.getName().getString();
        String string2 = INSTANCE.generateRandomName();
        if (string2 != null && !string2.equals(string)) {
            return string2;
        }
        return string;
    }

    private String generateRandomName() {
        if (mc.getConnection() == null) {
            return null;
        }
        ArrayList<PlayerInfo> arrayList = new ArrayList<>(mc.getConnection().getOnlinePlayers());
        ArrayList<String> arrayList2 = new ArrayList<>();
        String string = mc.player.getName().getString();
        for (PlayerInfo playerInfo : arrayList) {
            String string2 = playerInfo.getProfile().getName();
            if (string2.equals(string)) continue;
            arrayList2.add(string2);
        }
        if (arrayList2.isEmpty()) {
            return null;
        }
        if (this.cachedRandomName == null || !arrayList2.contains(this.cachedRandomName)) {
            this.cachedRandomName = arrayList2.get(this.random.nextInt(arrayList2.size()));
        }
        return this.cachedRandomName;
    }

    @EventTarget
    public void onChatReceive(ChatReceiveEvent chatReceiveEvent) {
        chatReceiveEvent.setComponent(Component.literal(NameProtect.replacePlayerName(chatReceiveEvent.getComponent().getString())));
    }
}