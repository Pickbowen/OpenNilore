package asm.patchify.loader;

import asm.patchify.annotation.Patch;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/**
 * Java-agent {@link ClassFileTransformer} that applies registered patches at class load.
 *
 * <p>Indexes the patches by the JVM-internal name of their target class and rewrites class
 * bytes via ASM. Classes that have no patch are returned untouched (null).</p>
 *
 * <p>If the system property {@code oz.dumpDir} is set, every successfully
 * transformed class is written under that directory as {@code <internalName>.class} for
 * inspection (use {@code javap -v} or open in Recaf).</p>
 */
public final class PatchClassFileTransformer implements ClassFileTransformer {
    private static final Logger LOGGER = LogManager.getLogger(PatchClassFileTransformer.class);
    private static final String DUMP_DIR_PROPERTY = "oz.dumpDir";

    private final Map<String, List<Class<?>>> patchesByTarget = new HashMap<>();

    public PatchClassFileTransformer() {
        rebuildIndex();
    }

    public void rebuildIndex() {
        patchesByTarget.clear();
        for (Class<?> patchClass : PatchRegistry.getPatches()) {
            Patch patch = patchClass.getAnnotation(Patch.class);
            if (patch == null) continue;
            String internalName = patch.value().getName().replace('.', '/');
            patchesByTarget.computeIfAbsent(internalName, k -> new ArrayList<>()).add(patchClass);
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        if (className == null) {
            return null;
        }
        List<Class<?>> patches = patchesByTarget.get(className);
        if (patches == null || patches.isEmpty()) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);
            for (Class<?> patch : patches) {
                try {
                    LOGGER.debug("Applying patch {} -> {}", patch.getName(), className);
                    PatchTransformer.apply(patch, classNode);
                } catch (Throwable t) {
                    LOGGER.error("Failed to apply patch {} -> {}", patch.getName(), className, t);
                }
            }
            // Override getClassLoader so COMPUTE_FRAMES resolves Minecraft / mod classes via the
            // class loader that actually owns the target class (Forge's TransformingClassLoader),
            // not the one that loaded our PatchTransformer.
            ClassLoader frameLoader = loader != null ? loader : Thread.currentThread().getContextClassLoader();
            ClassWriter writer = new FrameAwareClassWriter(reader, ClassWriter.COMPUTE_FRAMES, frameLoader);
            classNode.accept(writer);
            byte[] transformed = writer.toByteArray();
            dumpIfRequested(className, transformed);
            return transformed;
        } catch (Throwable t) {
            LOGGER.error("Failed to transform {}", className, t);
            return null;
        }
    }

    private void dumpIfRequested(String internalName, byte[] bytes) {
        String dumpDir = System.getProperty(DUMP_DIR_PROPERTY);
        if (dumpDir == null) return;
        try {
            Path target = Path.of(dumpDir).resolve(internalName + ".class");
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            LOGGER.warn("Failed to dump transformed class {}", internalName, e);
        }
    }

    private static final class FrameAwareClassWriter extends ClassWriter {
        private final ClassLoader loader;

        FrameAwareClassWriter(ClassReader reader, int flags, ClassLoader loader) {
            super(reader, flags);
            this.loader = loader;
        }

        @Override
        protected ClassLoader getClassLoader() {
            return loader;
        }
    }
}
