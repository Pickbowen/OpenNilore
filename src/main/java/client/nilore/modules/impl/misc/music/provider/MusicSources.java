package client.nilore.modules.impl.misc.music.provider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class MusicSources {

    private static final List<MusicSource> ALL = List.of(
            new GdstudioSource(),
            new NeteaseSource());

    private static volatile MusicSource current = ALL.get(0);

    private MusicSources() {
    }

    public static List<MusicSource> all() {
        return ALL;
    }

    public static MusicSource current() {
        return current;
    }

    public static void setCurrent(String displayName) {
        for (MusicSource source : ALL) {
            if (source.displayName().equals(displayName)) {
                current = source;
                return;
            }
        }
    }

    public static void load(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return;
            }
            setCurrent(Files.readString(file).trim());
        } catch (Exception ignored) {
        }
    }

    public static void save(Path file) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, current.displayName());
        } catch (Exception ignored) {
        }
    }
}