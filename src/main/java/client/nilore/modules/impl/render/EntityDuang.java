package client.nilore.modules.impl.render;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import client.nilore.event.impl.EntityHurtEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.event.impl.WorldChangeEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.game.DuangAnimator;
import client.nilore.event.EventTarget;

public class EntityDuang extends Module {

    public static EntityDuang INSTANCE;

    private final NumberSetting widthScale = new NumberSetting("Width", 1.0, 0.1, 3.0, 0.05);
    private final NumberSetting heightScale = new NumberSetting("Height", 1.0, 0.1, 3.0, 0.05);
    private final BooleanSetting always = new BooleanSetting("Always", true);
    private final NumberSetting idleAmplitude = new NumberSetting("Idle Amplitude", 0.10, 0.0, 0.5, 0.01,
            this.always::getValue);
    private final NumberSetting idleSpeed = new NumberSetting("Idle Speed", 1.6, 0.2, 5.0, 0.1,
            this.always::getValue);
    private final BooleanSetting onHurt = new BooleanSetting("On Hurt", true);
    private final NumberSetting duangStrength = new NumberSetting("Duang Strength", 0.35, 0.05, 1.0, 0.05,
            this.onHurt::getValue);
    private final NumberSetting duangSpeed = new NumberSetting("Duang Speed", 9.0, 3.0, 20.0, 0.5,
            this.onHurt::getValue);
    private final BooleanSetting allEntities = new BooleanSetting("All Entities", true);
    private final BooleanSetting ignoreSelf = new BooleanSetting("Ignore Self", true);

    private static final double DAMPING = 0.9;
    private static final float MIN_TOTAL = 0.30f;
    private static final float MAX_TOTAL = 2.50f;
    private static final float[] UNIT = new float[]{1.0f, 1.0f};

    public EntityDuang() {
        super("EntityDuang", Category.RENDER);
        INSTANCE = this;
    }

    public float[] renderScale(Entity entity, float partialTick) {
        if (entity == null || entity.isInvisible() || !this.appliesTo(entity)) {
            return UNIT;
        }
        float sx = this.widthScale.getValue().floatValue();
        float sy = this.heightScale.getValue().floatValue();

        float hurt = this.onHurt.getValue() ? DuangAnimator.verticalScale(entity.getId(), partialTick) : 1.0f;
        float idle = this.always.getValue() ? this.idleScale(entity, partialTick) : 1.0f;

        if (hurt != 1.0f) {
            sy *= hurt;
            sx *= DuangAnimator.horizontalScale(hurt);
        }
        if (idle != 1.0f) {
            sy *= idle;
            sx *= DuangAnimator.horizontalScale(idle);
        }

        sy = clamp(sy, MIN_TOTAL, MAX_TOTAL);
        sx = clamp(sx, MIN_TOTAL, MAX_TOTAL);
        return sx == 1.0f && sy == 1.0f ? UNIT : new float[]{sx, sy};
    }

    private float idleScale(Entity entity, float partialTick) {
        double amplitude = this.idleAmplitude.getValue().doubleValue();
        if (amplitude <= 0.0) {
            return 1.0f;
        }
        double speed = this.idleSpeed.getValue().doubleValue();
        double time = (entity.tickCount + partialTick) / 20.0;
        double phase = (entity.getId() * 0.6180339887498949) % 1.0 * Math.PI * 2.0;
        double primary = Math.sin(time * speed * Math.PI * 2.0 + phase);
        double secondary = Math.sin(time * speed * Math.PI * 2.0 * 0.37 + phase * 1.7) * 0.35;
        return (float) (1.0 + amplitude * (primary + secondary) * 0.7);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    private boolean appliesTo(Entity entity) {
        if (entity instanceof Player) {
            return !(entity == mc.player && this.ignoreSelf.getValue());
        }
        return this.allEntities.getValue();
    }

    @EventTarget
    public void onEntityHurt(EntityHurtEvent event) {
        if (event == null || !this.onHurt.getValue()) {
            return;
        }
        Entity entity = event.entity();
        if (entity == null || entity.isInvisible() || !this.appliesTo(entity)) {
            return;
        }
        DuangAnimator.kick(entity.getId(), this.duangStrength.getValue().doubleValue());
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (this.onHurt.getValue()) {
            DuangAnimator.tick(this.duangSpeed.getValue().doubleValue(), DAMPING);
        }
    }

    @EventTarget
    public void onWorldChange(WorldChangeEvent event) {
        DuangAnimator.clear();
    }

    @Override
    protected void onDisable() {
        DuangAnimator.clear();
        super.onDisable();
    }
}
