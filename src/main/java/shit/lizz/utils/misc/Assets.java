package shit.lizz.utils.misc;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Unified resource opener for the jar.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code Assets.class.getResourceAsStream(classpath)} — works whenever
 *       the jar is on a class loader's search path (Forge mod path, dev
 *       {@code -javaagent}, etc.).</li>
 *   <li>The directory pointed to by the {@code svc.resources} system
 *       property — populated by {@code ModuleInit} on the DLL injection
 *       path because the jar is not on any class loader URL search there
 *       (classes are defined directly onto the game loader).</li>
 * </ol>
 *
 * <p>Always returns {@code null} (not throwing) when the resource is missing,
 * matching the contract of {@link Class#getResourceAsStream(String)}.</p>
 */
public final class Assets {
    private Assets() {
    }

    public static InputStream open(String classpath) {
        InputStream cp = Assets.class.getResourceAsStream(classpath);
        if (cp != null) return cp;
        String dir = System.getProperty("svc.resources");
        if (dir != null) {
            String relative = classpath.startsWith("/") ? classpath.substring(1) : classpath;
            File f = new File(dir, relative);
            if (f.isFile()) {
                try {
                    return new FileInputStream(f);
                } catch (IOException ignored) {
                }
            }
        }
        return null;
    }
}
