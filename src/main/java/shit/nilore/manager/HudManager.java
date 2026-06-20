package shit.nilore.manager;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import shit.nilore.ClientBase;
import shit.nilore.NiloreClient;
import shit.nilore.event.impl.GlRenderEvent;
import shit.nilore.event.impl.Render2DEvent;
import shit.nilore.event.impl.TickEvent;
import shit.nilore.gui.IntroAnimation;
import shit.nilore.hud.HudElement;
import shit.nilore.hud.KeyBindsHud;
import shit.nilore.hud.LieDetector;
import shit.nilore.hud.ModuleListHud;
import shit.nilore.hud.PlayerListHud;
import shit.nilore.hud.PotionEffectsHud;
import shit.nilore.hud.TargetHud;
import shit.nilore.event.EventTarget;

public class HudManager {
    private final Map<String, HudElement> hudElements = new HashMap<>();

    public HudManager() {
        this.init();
    }

    public void init() {
        this.registerHudElement(new TargetHud());
        this.registerHudElement(new KeyBindsHud());
        this.registerHudElement(new ModuleListHud());
        this.registerHudElement(new PlayerListHud());
        this.registerHudElement(new PotionEffectsHud());
        this.registerHudElement(new LieDetector());
    }

    private void registerHudElement(HudElement hudElement) {
        NiloreClient.getInstance().getModuleManager().register(hudElement);
        this.hudElements.put(hudElement.getClass().getSimpleName(), hudElement);
    }

    public <T extends HudElement> T getHudElement(Class<T> clazz) {
        return clazz.cast(this.hudElements.get(clazz.getSimpleName()));
    }

    public HudElement getHudElementByName(String string) {
        return this.hudElements.values().stream().filter(hudElement -> hudElement.getName().equalsIgnoreCase(string)).findFirst().orElse(null);
    }

    public Collection<HudElement> getHudElements() {
        return this.hudElements.values();
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        if (ClientBase.mc.screen == null) {
            try {
                for (HudElement hudElement : NiloreClient.getInstance().getHudManager().getHudElements()) {
                    hudElement.stopDragging();
                }
            } catch (Exception exception) {
                ClientBase.logger.error(exception);
                ClientBase.logger.error(exception.getMessage());
            }
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent render2DEvent) {
        if (IntroAnimation.isRunning()) {
            return;
        }
        for (HudElement hudElement : this.getHudElements()) {
            if (!hudElement.isEnabled()) continue;
            hudElement.onRender2D(render2DEvent, hudElement.getX(), hudElement.getY());
        }
    }

    @EventTarget
    public void onGlRender(GlRenderEvent glRenderEvent) {
        if (IntroAnimation.isRunning()) {
            return;
        }
        for (HudElement hudElement : this.getHudElements()) {
            if (!hudElement.isEnabled()) continue;
            hudElement.onGlRender(glRenderEvent, hudElement.getX(), hudElement.getY());
        }
    }
}