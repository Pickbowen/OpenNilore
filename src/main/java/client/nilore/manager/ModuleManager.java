package client.nilore.manager;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import client.nilore.ClientBase;
import client.nilore.NiloreClient;
import client.nilore.event.impl.KeyEvent;
import client.nilore.exception.ModuleNotFoundException;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.combat.AntiBots;
import client.nilore.modules.impl.combat.AntiFireball;
import client.nilore.modules.impl.combat.AntiKB;
import client.nilore.modules.impl.combat.AutoOffHand;
import client.nilore.modules.impl.combat.AutoSoup;
import client.nilore.modules.impl.combat.AutoThrow;
import client.nilore.modules.impl.combat.Backtrack;
import client.nilore.modules.impl.combat.Critical;
import client.nilore.modules.impl.combat.CrystalAura;
import client.nilore.modules.impl.combat.FakeLag;
import client.nilore.modules.impl.combat.KillAura;
import client.nilore.modules.impl.exploit.Disabler;
import client.nilore.modules.impl.exploit.FastPlace;
import client.nilore.modules.impl.misc.AimAssist;
import client.nilore.modules.impl.misc.AutoClicker;
import client.nilore.modules.impl.misc.AutoRod;
import client.nilore.modules.impl.misc.KillSay;
import client.nilore.modules.impl.misc.MusicPlayer;
import client.nilore.modules.impl.misc.SafeWalk;
import client.nilore.modules.impl.movement.SpeedModule;
import client.nilore.modules.impl.movement.NoSlow;
import client.nilore.modules.impl.movement.FastWeb;
import client.nilore.modules.impl.movement.FireballBlink;
import client.nilore.modules.impl.movement.Fly;
import client.nilore.modules.impl.movement.HighJump;
import client.nilore.modules.impl.movement.NoDelay;
import client.nilore.modules.impl.movement.NoPush;
import client.nilore.modules.impl.movement.Scaffold;
import client.nilore.modules.impl.movement.Sprint;
import client.nilore.modules.impl.movement.TargetStrafe;
import client.nilore.modules.impl.player.AntiTNT;
import client.nilore.modules.impl.player.AntiVoid;
import client.nilore.modules.impl.player.AntiWeb;
import client.nilore.modules.impl.player.AutoMLG;
import client.nilore.modules.impl.player.AutoWebPlace;
import client.nilore.modules.impl.player.ChestStealer;
import client.nilore.modules.impl.player.GhostHand;
import client.nilore.modules.impl.player.Helper;
import client.nilore.modules.impl.player.InventoryManager;
import client.nilore.modules.impl.player.MidPearl;
import client.nilore.modules.impl.player.NoFall;
import client.nilore.modules.impl.player.Stuck;
import client.nilore.modules.impl.render.*;
import client.nilore.modules.impl.world.AntiStaff;
import client.nilore.modules.impl.world.AutoPlay;
import client.nilore.modules.impl.world.AutoTools;
import client.nilore.modules.impl.world.BlockIn;
import client.nilore.modules.impl.world.Debugger;
import client.nilore.modules.impl.world.Teams;
import client.nilore.modules.impl.world.Protocol;
import client.nilore.modules.impl.world.WebUI;
import client.nilore.event.EventTarget;

public class ModuleManager extends ClientBase {
    private final Map<String, Module> moduleMap = new ConcurrentHashMap<>();
    private final Map<Class<?>, Module> modulesByClass = new ConcurrentHashMap<>();
    private final Map<String, Module> modulesByName = new ConcurrentHashMap<>();
    private volatile List<Module> moduleSnapshot = List.of();
    private volatile Map<Category, List<Module>> modulesByCategory = Map.of();

    public ModuleManager() {
        NiloreClient.getInstance().getEventBus().register(this);
    }

    public void initModules() {
        this.register(new AntiBots());
        this.register(new AntiFireball());
        this.register(new AntiKB());
        this.register(new AutoOffHand());
        this.register(new AutoSoup());
        this.register(new AutoThrow());
        this.register(new Backtrack());
        this.register(new Critical());
        this.register(new CrystalAura());
        this.register(new KillAura());
        this.register(new FakeLag());

        this.register(new Disabler());
        this.register(new FastPlace());

        this.register(new AimAssist());
        this.register(new AutoClicker());
        this.register(new AutoRod());
        this.register(new SafeWalk());
        this.register(new MusicPlayer());
        this.register(new KillSay());

        this.register(new SpeedModule());
        this.register(new NoSlow());
        this.register(new FastWeb());
        this.register(new FireballBlink());
        this.register(new Fly());
        this.register(new HighJump());
        this.register(new NoDelay());
        this.register(new NoPush());
        this.register(new Scaffold());
        this.register(new Sprint());
        this.register(new TargetStrafe());

        this.register(new AntiTNT());
        this.register(new AntiVoid());
        this.register(new AntiWeb());
        this.register(new AutoMLG());
        this.register(new AutoWebPlace());
        this.register(new ChestStealer());
        this.register(new GhostHand());
        this.register(new Helper());
        this.register(new InventoryManager());
        this.register(new MidPearl());
        this.register(new NoFall());
        this.register(new Stuck());

        this.register(new AspectRatio());
        this.register(new ChestESP());
        this.register(new ClickGuiModule());
        this.register(new Compass());
        this.register(new DamageGlow());
        this.register(new ESP());
        this.register(new EntityEditor());
        this.register(new FakeAntiAim());
        this.register(new FullBright());
        this.register(new Interface());
        this.register(new ItemTags());
        this.register(new LyricsModule());
        this.register(new NameProtect());
        this.register(new NameTags());
        this.register(new NoFov());
        this.register(new NoHurtCam());
        this.register(new OldHitting());
        this.register(new Projectiles());
        this.register(new TimeWeather());
        this.register(new Watermark());
        this.register(new XRay());

        this.register(new AntiStaff());
        this.register(new AutoPlay());
        this.register(new AutoTools());
        this.register(new BlockIn());
        this.register(new Debugger());
        this.register(new Teams());
        this.register(new WebUI());
        this.register(new Protocol());
    }

    public void register(Module module) {
        this.moduleMap.put(module.getClass().getSimpleName(), module);
        this.modulesByClass.put(module.getClass(), module);
        this.modulesByName.put(normalizeName(module.getName()), module);
        module.registerSettings();
        this.rebuildCaches();
    }

    // 索引键是去掉空格的模块名；查询键沿用原先的行为，不对入参做去空格处理。
    public Module getModule(String string) {
        Module module = this.modulesByName.get(string.toLowerCase(Locale.ROOT));
        if (module == null) {
            throw new ModuleNotFoundException();
        }
        return module;
    }

    public <T extends Module> T getModule(Class<T> clazz) {
        Module module = this.modulesByClass.get(clazz);
        if (module == null) {
            throw new ModuleNotFoundException();
        }
        return clazz.cast(module);
    }

    public List<Module> getModules() {
        return this.moduleSnapshot;
    }

    public List<Module> getModulesByCategory(Category category) {
        return this.modulesByCategory.getOrDefault(category, List.of());
    }

    private static String normalizeName(String name) {
        return name.replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private void rebuildCaches() {
        List<Module> snapshot = List.copyOf(this.moduleMap.values());
        this.moduleSnapshot = snapshot;
        Map<Category, List<Module>> byCategory = new EnumMap<>(Category.class);
        for (Category category : Category.values()) {
            List<Module> modules = new ArrayList<>();
            for (Module module : snapshot) {
                if (module.getCategory().equals(category)) {
                    modules.add(module);
                }
            }
            modules.sort((a, b) -> a.getName().compareTo(b.getName()));
            byCategory.put(category, List.copyOf(modules));
        }
        this.modulesByCategory = byCategory;
    }

    @EventTarget
    public void onKey(KeyEvent event) {
        if (mc.screen == null && event.isPressed()) {
            for (Module module : this.moduleSnapshot) {
                if (module.getKey() != 0 && module.getKey() == event.getKeyCode()) {
                    module.toggle();
                }
            }
        }
    }
}
