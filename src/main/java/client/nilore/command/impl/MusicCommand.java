package client.nilore.command.impl;

import client.nilore.command.Command;
import client.nilore.modules.impl.misc.MusicPlayer;
import client.nilore.modules.impl.misc.music.AudioPlayer;
import client.nilore.utils.misc.ChatUtil;

public class MusicCommand extends Command {
    public MusicCommand() {
        super("music", new String[0]);
    }

    @Override
    public void onCommand(String[] args) {
        AudioPlayer player = MusicPlayer.AUDIO_PLAYER;
        if (args.length == 0) {
            ChatUtil.print("Usage: .music <stop|pause|resume|info>");
            return;
        }
        switch (args[0].toLowerCase()) {
            case "stop" -> {
                player.stop();
                ChatUtil.print("Music stopped.");
            }
            case "pause" -> {
                player.pause();
                ChatUtil.print("Music paused.");
            }
            case "resume", "play" -> {
                player.resume();
                ChatUtil.print("Music resumed.");
            }
            case "info" -> {
                var song = player.getCurrentSong();
                if (song != null) {
                    ChatUtil.print("Now playing: " + song.name + " - " + song.artist);
                } else {
                    ChatUtil.print("No song playing.");
                }
            }
            default -> ChatUtil.print("Usage: .music <stop|pause|resume|info>");
        }
    }

    @Override
    public String[] onTab(String[] args) {
        if (args.length <= 1) {
            return new String[]{"stop", "pause", "resume", "play", "info"};
        }
        return new String[0];
    }
}
