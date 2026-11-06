package client.nilore;

import asm.patchify.loader.ClassAgent;
import asm.patchify.loader.PatchRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.Mod;
import client.nilore.event.EventBus;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.TickEvent;
import client.nilore.gui.IntroAnimation;
import client.nilore.manager.CommandManager;
import client.nilore.manager.ConfigManager;
import client.nilore.manager.HudManager;
import client.nilore.manager.LagManager;
import client.nilore.manager.ModuleManager;
import client.nilore.manager.TargetManager;
import client.nilore.patch.ChatComponentPatch;
import client.nilore.patch.ChatScreenPatch;
import client.nilore.patch.ClientLevelPatch;
import client.nilore.patch.ConnectionPatch;
import client.nilore.patch.EntityPatch;
import client.nilore.patch.EntityRenderDispatcherPatch;
import client.nilore.patch.EntityRendererPatch;
import client.nilore.patch.FriendlyByteBufPatch;
import client.nilore.patch.GuiPatch;
import client.nilore.patch.BlockOcclusionCachePatch;
import client.nilore.patch.GameRendererPatch;
import client.nilore.patch.HumanoidModelPatch;
import client.nilore.patch.ItemInHandLayerPatch;
import client.nilore.patch.ItemInHandRendererPatch;
import client.nilore.patch.ItemPatch;
import client.nilore.patch.KeyboardHandlerPatch;
import client.nilore.patch.KeyboardInputPatch;
import client.nilore.patch.LevelRendererPatch;
import client.nilore.patch.LivingEntityPatch;
import client.nilore.patch.LivingEntityRendererPatch;
import client.nilore.patch.LocalPlayerPatch;
import client.nilore.patch.MinecraftPatch;
import client.nilore.patch.PacketUtilsPatch;
import client.nilore.patch.PlayerPatch;
import client.nilore.patch.PlayerTabOverlayPatch;
import client.nilore.asm.Bootstrap;
import client.nilore.utils.rotation.RotationHandler;
import client.nilore.utils.misc.Assets;

@Mod(value = "hey")
@Getter
@Setter
public class NiloreClient extends ClientBase {
    @Getter
    public static NiloreClient instance;
    public static final String CLIENT_NAME = "Nilore";
    public static final String CLIENT_NAME_UPPER = "NILORE";
    public static final String VERSION = "1.0";
    public static float serverTickRate;
    public static boolean isReady;
    public static boolean isMCPMapped;
    public static String configDir = System.getProperty("user.home") + File.separator + ".nilore";
    public static String username = "";

    private static final String[] CLOUD_ASSET_NAMES = { "panel.png", "ptr.png", "lie.wav", "truth.wav" };

    private EventBus eventBus;
    private RotationHandler rotationHandler;
    private ModuleManager moduleManager;
    private CommandManager commandManager;
    private ConfigManager configManager;
    private HudManager hudManager;
    private LagManager lagManager;
    private TargetManager targetManager;
    private int reconnectAttempts;

    public NiloreClient() {
        if (instance == null) {
            instance = this;
            this.init();
        }
    }

    private void init() {
        try {
            username = System.getProperty("user.name", "Player");
            File dir = new File(configDir);
            if (!dir.exists() && !dir.mkdirs()) {
                logger.warn("Failed to create config directory at {}", configDir);
            }
            mc = getMcInstance();
            this.eventBus = new EventBus();
            this.rotationHandler = new RotationHandler();
            this.eventBus.register(this.rotationHandler);
            this.moduleManager = new ModuleManager();
            this.hudManager = new HudManager();
            this.commandManager = new CommandManager();
            this.configManager = new ConfigManager();
            this.extractCloudAssets();
            this.lagManager = new LagManager();
            this.targetManager = new TargetManager();
            this.eventBus.register(this.hudManager);
            this.eventBus.register(this.lagManager);
            this.eventBus.register(this.targetManager);
            this.eventBus.register(this);
            this.commandManager.initCommands();
            this.eventBus.register(new IntroAnimation());
            Bootstrap.init();
            registerPatches();
            if (ClassAgent.getInstrumentation() != null) {
                ClassAgent.installPatchesAndRetransform();
            } else {
                logger.warn("ClassAgent not attached. Launch with `./gradlew runClient0` so the agent jvmArg is set.");
            }
            isReady = true;
            logger.info("{} v{} initialized.", CLIENT_NAME, VERSION);
        } catch (Throwable throwable) {
            logger.error(throwable.getMessage(), throwable);
        }
    }

    private boolean moduleInit = false;

    @EventTarget
    public void onTick(TickEvent e) {
        if (isReady() && !moduleInit) {
            moduleInit = true;
            this.moduleManager.initModules();
            this.configManager.loadAll();
        }
    }

    public static boolean isReady() {
        return instance != null
                && NiloreClient.instance.eventBus != null
                && isReady
                && mc != null
                && mc.player != null
                && !username.isEmpty()
                && mc.player.tickCount > 5;
    }

    public void shutdown() {
        isReady = false;
        if (this.configManager != null) {
            this.configManager.saveAll();
        }
    }

    private void extractCloudAssets() {
        File targetDir = ConfigManager.CONFIG_DIR;
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            logger.warn("Failed to create config directory at {}", targetDir);
            return;
        }
        for (String name : CLOUD_ASSET_NAMES) {
            File outFile = new File(targetDir, name);
            if (outFile.exists()) continue;
            try (InputStream in = Assets.open("/assets/nilore/cloud_assets/" + name)) {
                if (in == null) {
                    logger.warn("Cloud asset missing on classpath: {}", name);
                    continue;
                }
                try (OutputStream out = new FileOutputStream(outFile)) {
                    in.transferTo(out);
                }
            } catch (IOException ioException) {
                logger.error("Failed to extract cloud asset {}", name, ioException);
            }
        }
    }

    public static void registerPatches() {
        PatchRegistry.register(MinecraftPatch.class);
        PatchRegistry.register(LocalPlayerPatch.class);
        PatchRegistry.register(LivingEntityPatch.class);
        PatchRegistry.register(EntityPatch.class);
        PatchRegistry.register(PlayerPatch.class);
        PatchRegistry.register(ClientLevelPatch.class);
        PatchRegistry.register(ConnectionPatch.class);
        PatchRegistry.register(PacketUtilsPatch.class);
        PatchRegistry.register(KeyboardHandlerPatch.class);
        PatchRegistry.register(KeyboardInputPatch.class);
        PatchRegistry.register(ChatScreenPatch.class);
        PatchRegistry.register(ChatComponentPatch.class);
        PatchRegistry.register(EntityRendererPatch.class);
        PatchRegistry.register(EntityRenderDispatcherPatch.class);
        PatchRegistry.register(LevelRendererPatch.class);
        PatchRegistry.register(GameRendererPatch.class);
        PatchRegistry.register(ItemInHandRendererPatch.class);
        PatchRegistry.register(ItemInHandLayerPatch.class);
        PatchRegistry.register(HumanoidModelPatch.class);
        PatchRegistry.register(LivingEntityRendererPatch.class);
        PatchRegistry.register(ItemPatch.class);
        PatchRegistry.register(PlayerTabOverlayPatch.class);
        PatchRegistry.register(FriendlyByteBufPatch.class);
        PatchRegistry.register(GuiPatch.class);

        // Compatibility patch for Embeddium/Sodium's BlockOcclusionCache.
        // Always registered so the transformer can catch the class when it
        // first loads. We must NOT use Class.forName() here — that would
        // load the class before our transformer is installed, preventing
        // the patch from ever being applied.
        PatchRegistry.register(BlockOcclusionCachePatch.class);
    }

    public static Minecraft getMcInstance() {
        Minecraft minecraft = null;
        try {
            Class<?> clazz = Minecraft.class;
            for (Field field : clazz.getDeclaredFields()) {
                if (field.getType() != clazz) continue;
                field.setAccessible(true);
                minecraft = (Minecraft) field.get(null);
                field.setAccessible(false);
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
        return minecraft != null ? minecraft : Minecraft.getInstance();
    }

}
