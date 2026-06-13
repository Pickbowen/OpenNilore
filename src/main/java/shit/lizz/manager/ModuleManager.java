package shit.lizz.manager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import shit.lizz.ClientBase;
import shit.lizz.LizzClient;
import shit.lizz.event.impl.KeyEvent;
import shit.lizz.exception.ModuleNotFoundException;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.modules.impl.combat.AntiBots;
import shit.lizz.modules.impl.combat.AntiFireball;
import shit.lizz.modules.impl.combat.AntiKB;
import shit.lizz.modules.impl.combat.AutoOffHand;
import shit.lizz.modules.impl.combat.AutoSoup;
import shit.lizz.modules.impl.combat.AutoThrow;
import shit.lizz.modules.impl.combat.Backtrack;
import shit.lizz.modules.impl.combat.Critical;
import shit.lizz.modules.impl.combat.CrystalAura;
import shit.lizz.modules.impl.combat.KillAura;
import shit.lizz.modules.impl.exploit.Disabler;
import shit.lizz.modules.impl.exploit.FastPlace;
import shit.lizz.modules.impl.misc.AimAssist;
import shit.lizz.modules.impl.misc.AutoClicker;
import shit.lizz.modules.impl.misc.AutoRod;
import shit.lizz.modules.impl.misc.AI;
import shit.lizz.modules.impl.misc.SafeWalk;
import shit.lizz.modules.impl.movement.CollisionSpeed;
import shit.lizz.modules.impl.movement.NoSlow;
import shit.lizz.modules.impl.movement.FastWeb;
import shit.lizz.modules.impl.movement.FireballBlink;
import shit.lizz.modules.impl.movement.Fly;
import shit.lizz.modules.impl.movement.GuiMove;
import shit.lizz.modules.impl.movement.HighJump;
import shit.lizz.modules.impl.movement.NoDelay;
import shit.lizz.modules.impl.movement.NoPush;
import shit.lizz.modules.impl.movement.Scaffold;
import shit.lizz.modules.impl.movement.Sprint;
import shit.lizz.modules.impl.movement.TargetStrafe;
import shit.lizz.modules.impl.player.AntiTNT;
import shit.lizz.modules.impl.player.AntiVoid;
import shit.lizz.modules.impl.player.AntiWeb;
import shit.lizz.modules.impl.player.AutoMLG;
import shit.lizz.modules.impl.player.AutoWebPlace;
import shit.lizz.modules.impl.player.ChestStealer;
import shit.lizz.modules.impl.player.GhostHand;
import shit.lizz.modules.impl.player.Helper;
import shit.lizz.modules.impl.player.InventoryManager;
import shit.lizz.modules.impl.player.MidPearl;
import shit.lizz.modules.impl.player.NoFall;
import shit.lizz.modules.impl.player.Stuck;
import shit.lizz.modules.impl.render.AspectRatio;
import shit.lizz.modules.impl.render.ChestESP;
import shit.lizz.modules.impl.render.ClickGuiModule;
import shit.lizz.modules.impl.render.Compass;
import shit.lizz.modules.impl.render.DamageGlow;
import shit.lizz.modules.impl.render.ESP;
import shit.lizz.modules.impl.render.FullBright;
import shit.lizz.modules.impl.render.Interface;
import shit.lizz.modules.impl.render.ItemTags;
import shit.lizz.modules.impl.render.NameProtect;
import shit.lizz.modules.impl.render.NameTags;
import shit.lizz.modules.impl.render.NoHurtCam;
import shit.lizz.modules.impl.render.OldHitting;
import shit.lizz.modules.impl.render.Projectiles;
import shit.lizz.modules.impl.render.Watermark;
import shit.lizz.modules.impl.render.XRay;
import shit.lizz.modules.impl.world.AntiStaff;
import shit.lizz.modules.impl.world.AutoPlay;
import shit.lizz.modules.impl.world.AutoTools;
import shit.lizz.modules.impl.world.Debugger;
import shit.lizz.modules.impl.world.Teams;
import shit.lizz.modules.impl.world.WebUI;
import shit.lizz.event.EventTarget;

public class ModuleManager extends ClientBase {
    private final Map<String, Module> moduleMap = new ConcurrentHashMap<>();

    public ModuleManager() {
        LizzClient.getInstance().getEventBus().register(this);
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

        this.register(new Disabler());
        this.register(new FastPlace());

        this.register(new AimAssist());
        this.register(new AutoClicker());
        this.register(new AutoRod());
        this.register(new SafeWalk());
        this.register(new AI());

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
        this.register(new FullBright());
        this.register(new Interface());
        this.register(new ItemTags());
        this.register(new NameProtect());
        this.register(new NameTags());
        this.register(new NoHurtCam());
        this.register(new OldHitting());
        this.register(new Projectiles());
        this.register(new Watermark());
        this.register(new XRay());

        this.register(new AntiStaff());
        this.register(new AutoPlay());
        this.register(new AutoTools());
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
