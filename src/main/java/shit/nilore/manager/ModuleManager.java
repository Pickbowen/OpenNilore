package shit.nilore.manager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import shit.nilore.ClientBase;
import shit.nilore.NiloreClient;
import shit.nilore.event.impl.KeyEvent;
import shit.nilore.exception.ModuleNotFoundException;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.modules.impl.combat.AntiBots;
import shit.nilore.modules.impl.combat.AntiFireball;
import shit.nilore.modules.impl.combat.AntiKB;
import shit.nilore.modules.impl.combat.AutoOffHand;
import shit.nilore.modules.impl.combat.AutoSoup;
import shit.nilore.modules.impl.combat.AutoThrow;
import shit.nilore.modules.impl.combat.Backtrack;
import shit.nilore.modules.impl.combat.Critical;
import shit.nilore.modules.impl.combat.CrystalAura;
import shit.nilore.modules.impl.combat.FakeLag;
import shit.nilore.modules.impl.combat.KillAura;
import shit.nilore.modules.impl.exploit.Disabler;
import shit.nilore.modules.impl.exploit.FastPlace;
import shit.nilore.modules.impl.misc.AimAssist;
import shit.nilore.modules.impl.misc.AutoClicker;
import shit.nilore.modules.impl.misc.AutoRod;
import shit.nilore.modules.impl.misc.MusicPlayer;
import shit.nilore.modules.impl.misc.SafeWalk;
import shit.nilore.modules.impl.movement.CollisionSpeed;
import shit.nilore.modules.impl.movement.NoSlow;
import shit.nilore.modules.impl.movement.FastWeb;
import shit.nilore.modules.impl.movement.FireballBlink;
import shit.nilore.modules.impl.movement.Fly;
import shit.nilore.modules.impl.movement.GuiMove;
import shit.nilore.modules.impl.movement.HighJump;
import shit.nilore.modules.impl.movement.NoDelay;
import shit.nilore.modules.impl.movement.NoPush;
import shit.nilore.modules.impl.movement.Scaffold;
import shit.nilore.modules.impl.movement.Sprint;
import shit.nilore.modules.impl.movement.TargetStrafe;
import shit.nilore.modules.impl.player.AntiTNT;
import shit.nilore.modules.impl.player.AntiVoid;
import shit.nilore.modules.impl.player.AntiWeb;
import shit.nilore.modules.impl.player.AutoMLG;
import shit.nilore.modules.impl.player.AutoWebPlace;
import shit.nilore.modules.impl.player.ChestStealer;
import shit.nilore.modules.impl.player.GhostHand;
import shit.nilore.modules.impl.player.Helper;
import shit.nilore.modules.impl.player.InventoryManager;
import shit.nilore.modules.impl.player.MidPearl;
import shit.nilore.modules.impl.player.NoFall;
import shit.nilore.modules.impl.player.Stuck;
import shit.nilore.modules.impl.render.*;
import shit.nilore.modules.impl.world.AntiStaff;
import shit.nilore.modules.impl.world.AutoPlay;
import shit.nilore.modules.impl.world.AutoTools;
import shit.nilore.modules.impl.world.BlockIn;
import shit.nilore.modules.impl.world.Debugger;
import shit.nilore.modules.impl.world.Teams;
import shit.nilore.modules.impl.world.WebUI;
import shit.nilore.event.EventTarget;

public class ModuleManager extends ClientBase {
    private final Map<String, Module> moduleMap = new ConcurrentHashMap<>();

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

        this.register(new CollisionSpeed());
        this.register(new NoSlow());
        this.register(new FastWeb());
        this.register(new FireballBlink());
        this.register(new Fly());
        this.register(new GuiMove());
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
        this.register(new Watermark());
        this.register(new XRay());

        this.register(new AntiStaff());
        this.register(new AutoPlay());
        this.register(new AutoTools());
        this.register(new BlockIn());
        this.register(new Debugger());
        this.register(new Teams());
        this.register(new WebUI());
    }

    public void register(Module module) {
        this.moduleMap.put(module.getClass().getSimpleName(), module);
        module.registerSettings();
    }

    public Module getModule(String string) {
        Module module = null;
        for (Module module2 : this.moduleMap.values()) {
            if (!StringUtils.replace(module2.getName(), " ", "").equalsIgnoreCase(string)) continue;
            module = module2;
        }
        if (module == null) {
            throw new ModuleNotFoundException();
        }
        return module;
    }

    public <T extends Module> T getModule(Class<T> clazz) {
        Module module = clazz.cast(this.moduleMap.get(clazz.getSimpleName()));
        if (module == null) {
            throw new ModuleNotFoundException();
        }
        return (T) module;
    }

    public List<Module> getModules() {
        return this.moduleMap.values().stream().toList();
    }

    public List<Module> getModulesByCategory(Category category) {
        return this.moduleMap.values().stream()
                .filter(module -> module.getCategory().equals(category))
                .sorted((a, b) -> a.getName().compareTo(b.getName()))
                .collect(Collectors.toList());
    }

    @EventTarget
    public void onKey(KeyEvent event) {
        if (mc.screen == null) {
            for (Module module : this.moduleMap.values()) {
                if (module.getKey() != 0 && module.getKey() == event.getKeyCode() && event.isPressed()) {
                    module.toggle();
                }
            }
        }
    }
}
