package shit.lizz.command.impl;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import shit.lizz.ClientBase;
import shit.lizz.command.Command;
import shit.lizz.utils.misc.ChatUtil;

public class InfoCommand extends Command {

    public InfoCommand() {
        super("info", new String[0]);
    }

    @Override
    public void onCommand(String[] args) {
        if (ClientBase.mc.player == null || ClientBase.mc.getConnection() == null) {
            ChatUtil.print("Not in game.");
            return;
        }

        String playerName = ClientBase.mc.player.getName().getString();

        // Latency
        int ping = 0;
        var playerInfo = ClientBase.mc.getConnection().getPlayerInfo(ClientBase.mc.player.getUUID());
        if (playerInfo != null) {
            ping = playerInfo.getLatency();
        }

        // Server
        String server;
        ServerData serverData = ClientBase.mc.getCurrentServer();
        if (serverData != null) {
            server = serverData.ip;
        } else {
            server = "Singleplayer";
        }

        // Real time
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        ChatUtil.print("§7Player: §b" + playerName);
        ChatUtil.print("§7Ping: §b" + ping + "ms");
        ChatUtil.print("§7Server: §b" + server);
        ChatUtil.print("§7Time: §b" + time);
    }

    @Override
    public String[] onTab(String[] args) {
        return new String[0];
    }
}
