package client.nilore.command.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import client.nilore.ClientBase;
import client.nilore.command.Command;
import client.nilore.utils.misc.ChatUtil;

public class ReconnectCommand extends Command {

    public ReconnectCommand() {
        super("reconnect", new String[]{"rc"});
    }

    @Override
    public void onCommand(String[] args) {
        Minecraft mc = ClientBase.mc;
        ServerData serverData = mc.getCurrentServer();
        if (serverData == null || serverData.ip == null || serverData.ip.isEmpty()) {
            ChatUtil.print("Not connected to a multiplayer server.");
            return;
        }

        if (mc.getConnection() != null) {
            Connection connection = mc.getConnection().getConnection();
            connection.setListener(new PacketListener() {
                @Override
                public void onDisconnect(Component reason) {
                }

                @Override
                public boolean isAcceptingMessages() {
                    return false;
                }
            });
            connection.disconnect(Component.literal("Reconnecting"));
        }

        ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(serverData.ip), serverData, false);
        ChatUtil.print("Reconnecting to " + serverData.ip + "...");
    }

    @Override
    public String[] onTab(String[] args) {
        return new String[0];
    }
}
