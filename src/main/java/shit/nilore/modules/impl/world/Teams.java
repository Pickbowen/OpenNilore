package shit.nilore.modules.impl.world;

import java.util.Objects;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.settings.impl.ModeSetting;

public class Teams
extends Module {
    public static Teams instance;
    public static ModeSetting mode;

    public Teams() {
        super("Teams", Category.WORLD);
        instance = this;
    }

    public static boolean isSameTeam(Entity entity) {
        if (!instance.isEnabled()) {
            return false;
        }
        if (entity instanceof Player) {
            if (mode.is("Color")) {
                Integer n = entity.getTeamColor();
                Integer n2 = mc.player.getTeamColor();
                return n.equals(n2);
            }
            String string = Teams.getTeam(entity);
            String string2 = Teams.getTeam(mc.player);
            return Objects.equals(string, string2);
        }
        return false;
    }

    public static String getTeam(Entity entity) {
        PlayerInfo playerInfo = mc.getConnection().getPlayerInfo(entity.getUUID());
        if (playerInfo == null) {
            return null;
        }
        if (playerInfo.getTeam() != null) {
            return playerInfo.getTeam().getName();
        }
        return null;
    }

    static {
        mode = new ModeSetting("Mode", "Color", "Scoreboard").withDefault("Scoreboard");
    }
}