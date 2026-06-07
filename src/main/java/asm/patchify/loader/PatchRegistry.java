package asm.patchify.loader;

import asm.patchify.annotation.Patch;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Runtime registry for {@link Patch}-annotated classes.
 *
 * <p>The original obfuscated client relied on a custom class-load transformer to wire up
 * {@code @Inject} / {@code @Overwrite} / {@code @WrapInvoke} handlers. The transformer is
 * provided by the loader and is not part of this restored source; this registry exposes the
 * patch list so a coremod / launch plugin can drive the transformation, and provides a
 * lightweight no-op fallback when no transformer is installed.</p>
 */
public final class PatchRegistry {
    private static final Logger LOGGER = LogManager.getLogger(PatchRegistry.class);
    private static final List<Class<?>> PATCHES = new ArrayList<>();

    private PatchRegistry() {
    }

    public static void register(Class<?> patchClass) {
        Patch annotation = patchClass.getAnnotation(Patch.class);
        if (annotation == null) {
            throw new IllegalArgumentException(patchClass.getName() + " is missing @Patch");
        }
        synchronized (PATCHES) {
            if (!PATCHES.contains(patchClass)) {
                PATCHES.add(patchClass);
                LOGGER.debug("Registered patch {} -> {}", patchClass.getName(), annotation.value().getName());
            }
        }
    }

    public static List<Class<?>> getPatches() {
        synchronized (PATCHES) {
            return Collections.unmodifiableList(new ArrayList<>(PATCHES));
        }
    }
}
