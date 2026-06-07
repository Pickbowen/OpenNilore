package shit.lizz;

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
import shit.lizz.event.EventBus;
import shit.lizz.event.EventTarget;
import shit.lizz.event.impl.TickEvent;
import shit.lizz.gui.IntroAnimation;
import shit.lizz.manager.CommandManager;
import shit.lizz.manager.ConfigManager;
import shit.lizz.manager.HudManager;
import shit.lizz.manager.LagManager;
import shit.lizz.manager.ModuleManager;
import shit.lizz.manager.TargetManager;
import shit.lizz.patch.ChatScreenPatch;
import shit.lizz.patch.ClientLevelPatch;
import shit.lizz.patch.ConnectionPatch;
import shit.lizz.patch.EntityPatch;
import shit.lizz.patch.EntityRendererPatch;
import shit.lizz.patch.FriendlyByteBufPatch;
import shit.lizz.patch.GameRendererPatch;
import shit.lizz.patch.HumanoidModelPatch;
import shit.lizz.patch.ItemInHandLayerPatch;
import shit.lizz.patch.ItemInHandRendererPatch;
import shit.lizz.patch.ItemPatch;
import shit.lizz.patch.KeyboardHandlerPatch;
import shit.lizz.patch.KeyboardInputPatch;
import shit.lizz.patch.LevelRendererPatch;
import shit.lizz.patch.LivingEntityPatch;
import shit.lizz.patch.LivingEntityRendererPatch;
import shit.lizz.patch.LocalPlayerPatch;
import shit.lizz.patch.MinecraftPatch;
import shit.lizz.patch.PacketUtilsPatch;
import shit.lizz.patch.PlayerPatch;
import shit.lizz.patch.PlayerTabOverlayPatch;
import shit.lizz.asm.Bootstrap;
import shit.lizz.utils.rotation.RotationHandler;

@Mod(value = "hey")
@Getter
@Setter
public class LizzClient extends ClientBase {
    @Getter
    public static LizzClient instance;
    public static final String CLIENT_NAME = "Lizz";
    public static final String VERSION = "1.0";
    public static float serverTickRate;
    public static boolean isReady;
    public static boolean isMCPMapped;
    public static String configDir = System.getProperty("user.home") + File.separator + ".lizz";
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

    public LizzClient() {
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
                && LizzClient.instance.eventBus != null
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
            try (InputStream in = openCloudAsset(name)) {
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

    private static InputStream openCloudAsset(String name) {
        String classpath = "/assets/lizz/cloud_assets/" + name;
        InputStream is = LizzClient.class.getResourceAsStream(classpath);
        if (is != null) return is;
        String dir = System.getProperty("svc.resources");
        if (dir != null) {
            File f = new File(dir, "assets/lizz/cloud_assets/" + name);
            if (f.isFile()) {
                try {
                    return new java.io.FileInputStream(f);
                } catch (IOException ignored) {
                }
            }
        }
        return null;
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
        PatchRegistry.register(EntityRendererPatch.class);
        PatchRegistry.register(LevelRendererPatch.class);
        PatchRegistry.register(GameRendererPatch.class);
        PatchRegistry.register(ItemInHandRendererPatch.class);
        PatchRegistry.register(ItemInHandLayerPatch.class);
        PatchRegistry.register(HumanoidModelPatch.class);
        PatchRegistry.register(LivingEntityRendererPatch.class);
        PatchRegistry.register(ItemPatch.class);
        PatchRegistry.register(PlayerTabOverlayPatch.class);
        PatchRegistry.register(FriendlyByteBufPatch.class);
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
