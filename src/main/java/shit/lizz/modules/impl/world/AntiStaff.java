package shit.lizz.modules.impl.world;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddPlayerPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import shit.lizz.event.impl.PacketEvent;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.utils.misc.ChatUtil;
import shit.lizz.event.EventTarget;

public class AntiStaff extends Module {
    public static AntiStaff INSTANCE;

    private static final String STAFF_LIST_B64 = "QuermeaQnOaXoOmHj+Wfn+mbqizkuInlm73mnYAs56yZ5qmZLE1lbmdDaGVuMzg4NCxBbmRyZXdrcmlzdCxGaWE5LOaeq+iQp+ael+eEtiznu7/osYbkuYPjgZXjgpMs5oqW6Z+z5Li25bCP5YyqLOaKlumfs19hd2Hpqazljp8sTW5hbUxlb18s5Lit5LqM5bCR5bm0REws5p6V5LiK5Lmm5Li25aGR5pyb5pyILElhbU1vbGluY2VuXywsQ29GdV9fLOaWl+aImOiDnOS9myzlj6rnjqnmlqXlgJks5p6V5LiK5Lmm5Li26Zuq5aScLGFpeXVraSxDYW5keUFwb3N0bGUsY2h1bnlpMSzmtYHlvbHlj6rkvJrlmKTlmKTlmKQscXRlc2RmXzY3NCxxeHRtbGM5OSxTa3lmb3ks56We5Z2R5LmL6YCXLOWco+S4iuiNo+iAgDIzMyzlsI/lhpvlkJvkuLblpKnkvb/kuYvnv7ws5p6V5LiK5Lmm5Li25YKy5a+SLF93aW5uZXJfLFNreV9ZdWFueGlhbw==";

    public AntiStaff() {
        super("AntiStaff", Category.WORLD);
        INSTANCE = this;
    }

    @EventTarget
    public void onPacket(PacketEvent packetEvent) {
        String decoded = new String(Base64.getDecoder().decode(STAFF_LIST_B64), StandardCharsets.UTF_8);
        List<String> list = Arrays.asList(decoded.split(","));
        Packet<?> packet = packetEvent.getPacket();
        if (packet instanceof ClientboundPlayerInfoUpdatePacket clientboundPlayerInfoUpdatePacket) {
            if (clientboundPlayerInfoUpdatePacket.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
                for (ClientboundPlayerInfoUpdatePacket.Entry entry : clientboundPlayerInfoUpdatePacket.entries()) {
                    if (entry.profile() != null) {
                        String name = entry.profile().getName();
                        if (name != null && !name.isEmpty() && list.contains(name)) {
                            this.exitGame();
                        }
                    }
                    if (entry.displayName() != null) {
                        String display = entry.displayName().getString();
                        if (!display.isEmpty() && list.contains(display)) {
                            this.exitGame();
                        }
                    }
                }
            }
        } else if (packet instanceof ClientboundAddPlayerPacket clientboundAddPlayerPacket) {
            if (clientboundAddPlayerPacket.getPlayerId() != null && mc.level != null) {
                net.minecraft.world.entity.Entity entity = mc.level.getEntity(clientboundAddPlayerPacket.getEntityId());
                if (entity != null) {
                    String name = entity.getName().getString();
                    if (!name.isEmpty() && list.contains(name)) {
                        this.exitGame();
                    }
                }
            }
        }
    }

    private void exitGame() {
        ChatUtil.print("Staff detected!");
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.sendCommand("hub");
        }
    }
}
