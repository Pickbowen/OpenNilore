package shit.lizz.hud;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import shit.lizz.event.impl.GlRenderEvent;
import shit.lizz.event.impl.PacketEvent;
import shit.lizz.event.impl.Render2DEvent;
import shit.lizz.hud.target.MoonTargetStyle;
import shit.lizz.hud.target.RoundTargetStyle;
import shit.lizz.hud.target.TargetStyle;
import shit.lizz.modules.impl.combat.KillAura;
import shit.lizz.settings.impl.ModeSetting;
import shit.lizz.utils.animation.SmoothAnimationTimer;
import shit.lizz.utils.math.Easings;
import shit.lizz.event.EventTarget;

public class TargetHud
extends HudElement {
    private final SmoothAnimationTimer healthAnim = new SmoothAnimationTimer();
    private final SmoothAnimationTimer healthLagAnim = new SmoothAnimationTimer();
    public static final Map<String, AtomicInteger> playerHealthMap = new HashMap<>();
    private float lastHealth;
    private float healthDelta;
    private final ModeSetting styleMode = new ModeSetting("Mode", "Round", "Moon").withDefault("Opal");

    public TargetHud() {
        super("TargetHUD");
        this.setWidth(200.0f);
        this.setHeight(60.0f);
        TargetStyle.initStyles();
        this.setEnabled(true);
    }

    @EventTarget
    public void onPacket(PacketEvent packetEvent) {
        Packet<?> packet = packetEvent.getPacket();
        if (packet instanceof ClientboundSetScorePacket clientboundSetScorePacket) {
            if (mc.level != null && mc.player != null && ("belowHealth".equals(clientboundSetScorePacket.getObjectiveName()) || "health".equals(clientboundSetScorePacket.getObjectiveName())) && !clientboundSetScorePacket.getOwner().equals(mc.player.getGameProfile().getName())) {
                playerHealthMap.computeIfAbsent(clientboundSetScorePacket.getOwner(), string -> new AtomicInteger()).set(clientboundSetScorePacket.getScore());
            }
        }
    }

    @Override
    public void onSettings() {
    }

    @Override
    public void onGlRender(GlRenderEvent glRenderEvent, float x, float y) {
    }

    @Override
    public void onRender2D(Render2DEvent render2DEvent, float x, float y) {
        if (mc.level == null || mc.player == null) {
            return;
        }
        float maxHealth;
        for (AbstractClientPlayer player : mc.level.players()) {
            if (player == mc.player || !playerHealthMap.containsKey(player.getName().getString())) continue;
            player.setHealth((float)Math.max(1, playerHealthMap.get(player.getName().getString()).get()));
        }
        LivingEntity target = null;
        if (mc.screen instanceof ChatScreen) {
            target = mc.player;
        } else if (KillAura.aimingTarget instanceof LivingEntity le) {
            target = le;
        }
        if (target != null) {
            if (!Mth.equal(this.lastHealth, target.getHealth())) {
                this.healthDelta = target.getHealth() - this.lastHealth;
                this.lastHealth = target.getHealth();
            }
            float currentHealth = Math.min(target.getHealth(), 20.0f);
            maxHealth = Math.min(target.getMaxHealth(), 20.0f);
            float ratio = maxHealth > 0.0f ? currentHealth / maxHealth : 0.0f;
            this.healthAnim.animate(ratio, 0.5, Easings.EASE_OUT_POW4);
            this.healthLagAnim.animate(ratio, 1.5, Easings.EASE_OUT_POW5);
        } else {
            this.healthDelta = 0.0f;
        }
        this.healthAnim.tick();
        this.healthLagAnim.tick();
        TargetStyle targetStyle = TargetStyle.getByName(this.styleMode.getValue());
        if (targetStyle != null) {
            maxHealth = target != null ? (target.getMaxHealth() > 0.0f ? Math.min(target.getHealth(), 20.0f) / Math.min(target.getMaxHealth(), 20.0f) : 0.0f) : 0.0f;
            targetStyle.render(render2DEvent, target, this.healthAnim, this.healthLagAnim, maxHealth, x, y);
            if (targetStyle instanceof RoundTargetStyle) {
                this.setWidth(120.0f);
                this.setHeight(38.0f);
            } else if (targetStyle instanceof MoonTargetStyle) {
                this.setWidth(200.0f);
                this.setHeight(40.0f);
            } else {
                this.setWidth(150.0f);
                this.setHeight(36.0f);
            }
        }
    }
}