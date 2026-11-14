package client.nilore.config;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Write-through-a-temp-file helper for config persistence.
 *
 * <p>Writing a config target in place means a crash (or a kill during world unload) leaves a
 * truncated file, and the next launch reads it as "no settings at all". Every write here lands
 * in a sibling {@code .tmp} first and is renamed into place afterwards, keeping the previous
 * content as {@code .bak} so a bad save is always recoverable by hand.
 */
public final class AtomicWrite {

    @FunctionalInterface
    public interface Body {
        void writeTo(BufferedWriter writer) throws IOException;
    }

    public static void writeAtomically(Path target, Body body) throws IOException {
        Path fileName = target.getFileName();
        if (fileName == null) {
            throw new IOException("Config target has no file name: " + target);
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = target.resolveSibling(fileName + ".tmp");
        Path backup = target.resolveSibling(fileName + ".bak");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                body.writeTo(writer);
            }
            if (Files.exists(target)) {
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            moveIntoPlace(tmp, target);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(tmp);
            throw e;
        }
    }

    public static void writeStringAtomically(Path target, String content) throws IOException {
        writeAtomically(target, writer -> writer.write(content));
    }

    private static void moveIntoPlace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Some Windows file systems refuse ATOMIC_MOVE; the .bak copy still covers us.
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private AtomicWrite() {
    }
}
