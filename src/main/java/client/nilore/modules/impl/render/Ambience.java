package client.nilore.modules.impl.render;

import net.minecraft.client.particle.Particle;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.GameTickEvent;
import client.nilore.event.impl.ReceivePacketEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;

public class Ambience extends Module {

    public static Ambience INSTANCE;

    private final ModeSetting timeMode = new ModeSetting("Time", "Day", "Sunset", "Night", "Custom", "Vanilla")
            .withDefault("Vanilla");
    private final NumberSetting customTime = new NumberSetting("Custom Time", 15000, 0, 24000, 100,
            () -> timeMode.is("Custom"));

    private final ModeSetting weatherMode = new ModeSetting("Weather", "Clear", "Rain", "Thunder", "Snowy", "Vanilla")
            .withDefault("Vanilla");
    private final NumberSetting rainDensity = new NumberSetting("Rain Density", 100, 0, 100, 5,
            () -> !"Clear".equals(weatherMode.getValue()) && !"Vanilla".equals(weatherMode.getValue()));

    private final ModeSetting particleType = new ModeSetting("Particles", "Snow", "Cherry", "Firefly", "Ash", "None")
            .withDefault("Snow");
    private final NumberSetting spawnRate = new NumberSetting("Spawn Rate", 40, 1, 200, 1,
            () -> !particleType.is("None"));
    private final NumberSetting radius = new NumberSetting("Radius", 16, 2, 32, 1,
            () -> !particleType.is("None"));
    private final NumberSetting height = new NumberSetting("Height", 10, 1, 24, 1,
            () -> !particleType.is("None"));
    private final NumberSetting drift = new NumberSetting("Drift", 30, 0, 100, 5,
            () -> !particleType.is("None"));

    private float pending;

    public Ambience() {
        super("Ambience", Category.RENDER);
        INSTANCE = this;
    }

    public static boolean isSnowy() {
        return INSTANCE != null && INSTANCE.isEnabled() && "Snowy".equals(INSTANCE.weatherMode.getValue());
    }

    @Override
    protected void onEnable() {
        pending = 0.0f;
    }

    @EventTarget
    public void onGameTick(GameTickEvent event) {
        if (mc.level == null) return;
        applyTime();
        applyWeather();
        if (mc.player != null) {
            spawnParticles();
        }
    }

    @EventTarget
    public void onReceivePacket(ReceivePacketEvent event) {
        if (mc.level == null) return;

        if (event.getPacket() instanceof ClientboundSetTimePacket
                && !"Vanilla".equals(timeMode.getValue())) {
            event.setCancelled(true);
            return;
        }

        if (event.getPacket() instanceof ClientboundGameEventPacket gameEvent
                && !"Vanilla".equals(weatherMode.getValue())) {
            ClientboundGameEventPacket.Type type = gameEvent.getEvent();
            if (type == ClientboundGameEventPacket.START_RAINING
                    || type == ClientboundGameEventPacket.STOP_RAINING
                    || type == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE
                    || type == ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE) {
                event.setCancelled(true);
            }
        }
    }

    private void applyTime() {
        String time = timeMode.getValue();
        if ("Vanilla".equals(time)) return;

        long dayTime = switch (time) {
            case "Day" -> 1000L;
            case "Sunset" -> 13000L;
            case "Night" -> 18000L;
            case "Custom" -> customTime.getValue().longValue();
            default -> 0L;
        };
        mc.level.setDayTime(dayTime);
    }

    private void applyWeather() {
        String weather = weatherMode.getValue();
        if ("Vanilla".equals(weather)) return;

        float density = rainDensity.getValue().floatValue() / 100.0f;
        switch (weather) {
            case "Clear" -> {
                mc.level.rainLevel = 0.0f;
                mc.level.oRainLevel = 0.0f;
                mc.level.thunderLevel = 0.0f;
                mc.level.oThunderLevel = 0.0f;
            }
            case "Rain" -> {
                mc.level.rainLevel = density;
                mc.level.oRainLevel = density;
                mc.level.thunderLevel = 0.0f;
                mc.level.oThunderLevel = 0.0f;
            }
            case "Thunder" -> {
                mc.level.rainLevel = density;
                mc.level.oRainLevel = density;
                mc.level.thunderLevel = 1.0f;
                mc.level.oThunderLevel = 1.0f;
            }
            case "Snowy" -> {
                mc.level.rainLevel = density;
                mc.level.oRainLevel = density;
                mc.level.thunderLevel = 0.0f;
                mc.level.oThunderLevel = 0.0f;
            }
        }
    }

    private void spawnParticles() {
        String mode = particleType.getValue();
        if ("None".equals(mode)) {
            pending = 0.0f;
            return;
        }

        ParticleOptions particle;
        float scale;
        float density;
        boolean rising;
        switch (mode) {
            case "Cherry" -> {
                particle = ParticleTypes.CHERRY_LEAVES;
                scale = 1.0f;
                density = 1.0f;
                rising = false;
            }
            case "Firefly" -> {
                particle = ParticleTypes.GLOW;
                scale = 2.5f;
                density = 3.0f;
                rising = true;
            }
            case "Ash" -> {
                particle = ParticleTypes.ASH;
                scale = 2.0f;
                density = 2.0f;
                rising = false;
            }
            default -> {
                particle = ParticleTypes.SNOWFLAKE;
                scale = 1.0f;
                density = 1.0f;
                rising = false;
            }
        }

        pending += spawnRate.getValue().floatValue() * density / 20.0f;
        int count = (int) pending;
        pending -= count;
        if (count <= 0) return;

        float spawnRadius = radius.getValue().floatValue();
        float spawnHeight = height.getValue().floatValue();
        float spread = drift.getValue().floatValue() / 100.0f;

        for (int i = 0; i < count; i++) {
            double angle = Math.random() * Math.PI * 2.0;
            double distance = spawnRadius * Math.sqrt(Math.random());
            double x = mc.player.getX() + Math.cos(angle) * distance;
            double z = mc.player.getZ() + Math.sin(angle) * distance;
            double y = mc.player.getY() + 0.5 + Math.random() * spawnHeight;
            double vx = (Math.random() - 0.5) * spread;
            double vz = (Math.random() - 0.5) * spread;
            double vy = rising ? 0.01 + Math.random() * 0.02 : -0.02 - Math.random() * 0.05;
            Particle spawned = mc.particleEngine.createParticle(particle, x, y, z, vx, vy, vz);
            if (spawned != null) {
                spawned.scale(scale);
            }
        }
    }
}
