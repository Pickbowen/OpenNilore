package shit.lizz.dll;

import asm.patchify.loader.ClassAgent;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * One-shot bootstrap that is loaded by a throwaway {@code URLClassLoader}
 * (parent = Forge GameClassLoader) and re-defines every class in lizz.jar onto
 * the GameClassLoader itself.
 *
 * <p>This is necessary because retransformed Minecraft classes contain
 * {@code INVOKESTATIC} references to our patch handlers (e.g.
 * {@code INVOKESTATIC shit/lizz/patch/ConnectionPatch.transformReceive}).
 * When the JVM resolves those references it uses the Minecraft class's
 * defining loader (cpw.mods.cl.ModuleClassLoader). If our classes only live
 * in a sibling URLClassLoader, resolution fails with
 * {@code NoClassDefFoundError} / {@code VerifyError}.</p>
 *
 * <p>Steps:</p>
 * <ol>
 *   <li>Use the live {@link Instrumentation} (already populated by
 *       {@code instrument.dll}'s {@code Agent_OnAttach}) to {@code redefineModule}
 *       java.base/java.lang open to this module, so reflective access to
 *       {@code ClassLoader.defineClass} works without JVM args.</li>
 *   <li>Walk the jar. {@code .class} entries get re-defined on the game
 *       loader; everything else is extracted to a temp directory and exposed
 *       via the {@code svc.resources} system property so
 *       {@code Bootstrap} and {@code LizzClient} can still find their
 *       resources (mapping.srg, cloud assets, webui static files).</li>
 *   <li>Hand off to {@link ClientInit#start(String)} loaded through the
 *       game loader.</li>
 * </ol>
 */
public final class ModuleInit {
    private static final Logger LOGGER = LogManager.getLogger("ModuleInit");
    public static final String RESOURCES_PROP = "svc.resources";

    private ModuleInit() {
    }

    public static void load(String jarPath, ClassLoader gameLoader) throws Throwable {
        LOGGER.info("ModuleInit.load jar={} gameLoader={}", jarPath, gameLoader);
        long t0 = System.nanoTime();

        Instrumentation inst = ClassAgent.getInstrumentation();
        if (inst == null) {
            throw new IllegalStateException("ClassAgent.getInstrumentation() returned null - "
                    + "did instrument.dll Agent_OnAttach run?");
        }

        openJavaBaseToSelf(inst);

        Method defineClass = ClassLoader.class.getDeclaredMethod(
                "defineClass", String.class, byte[].class, int.class, int.class);
        defineClass.setAccessible(true);

        Path resourceDir = Paths.get(System.getProperty("java.io.tmpdir"),
                "svc-resources-" + ProcessHandle.current().pid());
        Files.createDirectories(resourceDir);

        // Pass 1: read every entry. Defer .class defines until pass 2 so we can
        // retry in dependency order (a patch class extending a lizz base class
        // can only be defined after its super has been defined).
        LinkedHashMap<String, byte[]> pendingClasses = new LinkedHashMap<>();
        int resourceCount = 0;

        try (ZipFile zip = new ZipFile(jarPath)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.startsWith("META-INF/")) continue;

                byte[] bytes;
                try (InputStream is = zip.getInputStream(entry)) {
                    bytes = is.readAllBytes();
                }

                if (name.endsWith(".class")) {
                    String className = name.substring(0, name.length() - 6).replace('/', '.');
                    // ModuleInit itself is already live in this URLClassLoader.
                    if (className.equals(ModuleInit.class.getName())) continue;
                    pendingClasses.put(className, bytes);
                } else {
                    Path target = resourceDir.resolve(name);
                    Path parent = target.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    Files.write(target, bytes);
                    resourceCount++;
                }
            }
        }

        int classCount = definePassUntilFixedPoint(defineClass, gameLoader, pendingClasses);

        if (!pendingClasses.isEmpty()) {
            LOGGER.warn("{} class(es) could not be defined on gameLoader after fixed-point retry:",
                    pendingClasses.size());
            for (Map.Entry<String, byte[]> e : pendingClasses.entrySet()) {
                try {
                    defineClass.invoke(gameLoader, e.getKey(), e.getValue(), 0, e.getValue().length);
                } catch (Throwable t) {
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    LOGGER.warn("  {} -> {}", e.getKey(), cause.toString());
                }
            }
        }

        System.setProperty(RESOURCES_PROP, resourceDir.toAbsolutePath().toString());

        long ms = (System.nanoTime() - t0) / 1_000_000L;
        LOGGER.info("Defined {} classes, extracted {} resources to {} ({} ms)",
                classCount, resourceCount, resourceDir, ms);

        Class<?> bootstrapCls = Class.forName("shit.lizz.dll.ClientInit", true, gameLoader);
        LOGGER.info("ClientInit loader (should be gameLoader): {}", bootstrapCls.getClassLoader());
        Method start = bootstrapCls.getMethod("start", String.class);
        start.invoke(null, jarPath);
    }

    /**
     * Defines classes onto {@code gameLoader} in repeated passes. Each pass
     * retries everything that previously failed; this terminates either when
     * all entries are defined or when a full pass made no progress (genuinely
     * missing dependency). The remaining failures stay in {@code pending} for
     * the caller to log.
     */
    private static int definePassUntilFixedPoint(Method defineClass,
                                                  ClassLoader gameLoader,
                                                  LinkedHashMap<String, byte[]> pending) {
        int total = 0;
        boolean progressed = true;
        while (progressed && !pending.isEmpty()) {
            progressed = false;
            Iterator<Map.Entry<String, byte[]>> it = pending.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, byte[]> entry = it.next();
                try {
                    defineClass.invoke(gameLoader, entry.getKey(),
                            entry.getValue(), 0, entry.getValue().length);
                    it.remove();
                    progressed = true;
                    total++;
                } catch (Throwable t) {
                    // Leave it in the pending map; another pass might succeed
                    // once dependencies are defined. The non-recoverable
                    // failures are logged by the caller.
                }
            }
        }
        return total;
    }

    private static void openJavaBaseToSelf(Instrumentation inst) {
        Module javaBase = ClassLoader.class.getModule();
        Module here = ModuleInit.class.getModule();
        try {
            inst.redefineModule(
                    javaBase,
                    Set.of(),
                    Map.of(),
                    Map.of("java.lang", Set.of(here)),
                    Set.of(),
                    Map.of());
        } catch (Throwable t) {
            LOGGER.warn("redefineModule for java.base/java.lang failed (may be already open): {}",
                    t.toString());
        }
    }
}
