package shit.lizz.dll;

import asm.patchify.loader.ClassAgent;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import shit.lizz.LizzClient;
import shit.lizz.asm.Bootstrap;

/**
 * Final hand-off step on the DLL injection path. By the time {@link #start} is
 * invoked, {@code ModuleInit} has already re-defined every class in the
 * jar onto the Forge GameClassLoader, so {@link LizzClient} and all 21 patch
 * handler classes share Minecraft's class loader.
 *
 * <p>This entry point intentionally does <b>not</b> construct {@link LizzClient}.
 * Once {@link ClassAgent#installPatchesAndRetransform()} returns, the
 * retransformed {@code Minecraft.tick()} contains the injected
 * {@code MinecraftPatch.onTick} prologue that lazily constructs {@link
 * LizzClient} on the next tick. Letting the existing tick-driven lazy-init run
 * keeps the DLL path and the mod path identical from {@code LizzClient.init}
 * onwards.</p>
 */
public final class ClientInit {
    private static final Logger LOGGER = LogManager.getLogger("ClientInit");
    private static volatile boolean started = false;

    private ClientInit() {
    }

    public static synchronized void start(String extractedJarPath) {
        if (started) {
            LOGGER.info("ClientInit.start ignored (already started)");
            return;
        }
        started = true;
        try {
            LOGGER.info("ClientInit.start jar={}", extractedJarPath);
            LOGGER.info("ClientInit loader  = {}", ClientInit.class.getClassLoader());
            LOGGER.info("LizzClient loader     = {}", LizzClient.class.getClassLoader());
            LOGGER.info("Minecraft loader     = {}", Minecraft.class.getClassLoader());
            LOGGER.info("ClassAgent inst      = {}", ClassAgent.getInstrumentation());

            // Load mojmap → SRG mappings before we attempt any retransform.
            Bootstrap.init();

            // Register the 21 patch classes and trigger retransform of the
            // already-loaded Minecraft targets. After this returns,
            // Minecraft.tick / LocalPlayer.tick / etc. carry the injected
            // prologues; the next tick will invoke MinecraftPatch.onTick which
            // performs the real LizzClient construction via its existing
            // lazy-init path.
            LizzClient.registerPatches();
            ClassAgent.installPatchesAndRetransform();

            LOGGER.info("ClientInit done. LizzClient will be constructed on the next Minecraft.tick.");
        } catch (Throwable t) {
            LOGGER.error("ClientInit.start failed", t);
        }
    }
}
