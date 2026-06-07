package asm.patchify.loader;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Java agent entry point — loaded by the system class loader when the JVM is started with
 * {@code -javaagent:<this jar>}.
 *
 * <p>In a ForgeGradle dev environment the mod jar and the agent jar are the same file but get
 * loaded by different class loaders (system vs the Forge module layer). That means the static
 * fields on this class are NOT shared between agent-side and mod-side copies of
 * {@code ClassAgent}. To bridge the gap we stash {@link Instrumentation} in
 * {@link System#getProperties()} under {@link #INSTRUMENTATION_KEY} so the mod can retrieve it
 * regardless of which class loader it lives in.</p>
 */
public final class ClassAgent {
    public static final String INSTRUMENTATION_KEY = "asm.patchify.instrumentation";
    private static final Logger LOGGER = LogManager.getLogger("ClassAgent");

    private static volatile boolean transformerInstalled = false;

    private ClassAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        install(inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        install(inst);
    }

    public static synchronized void install(Instrumentation inst) {
        Object existing = System.getProperties().get(INSTRUMENTATION_KEY);
        if (existing == inst) {
            return;
        }
        System.getProperties().put(INSTRUMENTATION_KEY, inst);
        LOGGER.info("ClassAgent attached, retransform supported = {}", inst.isRetransformClassesSupported());
    }

    /**
     * Looks up the {@link Instrumentation} stashed by {@link #premain}. Works across class loaders.
     */
    public static Instrumentation getInstrumentation() {
        Object instObj = System.getProperties().get(INSTRUMENTATION_KEY);
        return instObj instanceof Instrumentation ? (Instrumentation) instObj : null;
    }

    /**
     * Install a transformer for the currently registered patches and retransform any patch target
     * that is already loaded. Called from mod code once {@link PatchRegistry} is populated.
     */
    public static synchronized void installPatchesAndRetransform() {
        if (transformerInstalled) {
            LOGGER.info("Patches already installed; skipping duplicate retransform request");
            return;
        }
        Instrumentation inst = getInstrumentation();
        if (inst == null) {
            LOGGER.warn("ClassAgent not attached; cannot install patches");
            return;
        }
        ClassTransformer transformer = new ClassTransformer();
        inst.addTransformer(transformer, true);
        transformerInstalled = true;
        List<Class<?>> retransform = new ArrayList<>();
        for (Class<?> patch : PatchRegistry.getPatches()) {
            asm.patchify.annotation.Patch ann = patch.getAnnotation(asm.patchify.annotation.Patch.class);
            if (ann == null) continue;
            Class<?> target;
            try {
                target = ann.value();
            } catch (Throwable t) {
                LOGGER.warn("Patch target unresolved for {}: {}", patch.getName(), t.toString());
                continue;
            }
            if (inst.isModifiableClass(target)) {
                retransform.add(target);
            } else {
                LOGGER.warn("Cannot retransform unmodifiable target {}", target.getName());
            }
        }
        if (retransform.isEmpty()) {
            return;
        }
        // Retransform one class at a time so we can pinpoint which patch produces invalid
        // bytecode if the JVM throws VerifyError / LinkageError.
        int success = 0;
        for (Class<?> target : retransform) {
            try {
                inst.retransformClasses(target);
                success++;
            } catch (Throwable t) {
                LOGGER.error("Retransform failed for {}: {}", target.getName(), t.toString());
            }
        }
        LOGGER.info("Retransformed {} / {} patch target(s)", success, retransform.size());
    }
}
