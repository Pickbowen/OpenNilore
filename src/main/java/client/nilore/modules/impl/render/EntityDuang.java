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

/**
 * 受击形变(duang), 纯视觉。
 *
 * <p>从 EntityEditor 抽出来的独立模块: EntityEditor 负责"把实体换成别的模型 / 改碰撞盒",
 * 这里只负责"看起来被压扁又弹回来"。两者唯一的交集是都在渲染时改 PoseStack, 但数据源
 * 完全分开, 所以可以单独开关, 也不会因为关掉模型替换而一起失效。
 *
 * <p>它不改 bounding box、不改任何包、不影响命中判定 —— 只在
 * {@code EntityRenderDispatcher.render} 外面套一层以脚底为锚点的缩放。因此可以叠在
 * 任何实体上而不产生服务端错位。
 */
public class EntityDuang extends Module {

    public static EntityDuang INSTANCE;

    private final NumberSetting widthScale = new NumberSetting("Width", 1.0, 0.1, 3.0, 0.05);
    private final NumberSetting heightScale = new NumberSetting("Height", 1.0, 0.1, 3.0, 0.05);
    /** 全程形变: 不受击也一直在呼吸/蠕动的幅度基准。 */
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

    /** 阻尼比: 取 0.9 让弹簧略微欠阻尼 —— 压扁之后会过冲一次再收敛, 这就是"弹"的来源。 */
    private static final double DAMPING = 0.9;
    /** 形变叠乘之后的总范围, 防止 Width/Height 与两套动画相乘后把模型压没。 */
    private static final float MIN_TOTAL = 0.30f;
    private static final float MAX_TOTAL = 2.50f;
    private static final float[] UNIT = new float[]{1.0f, 1.0f};

    public EntityDuang() {
        super("EntityDuang", Category.RENDER);
        INSTANCE = this;
    }

    /**
     * 本帧该用的渲染缩放 {@code {sx, sy}}, {@code sx} 同时用于 z 轴。
     *
     * @return 恒为同一个 {@link #UNIT} 实例表示不需要缩放, 调用方可以直接比引用
     */
    public float[] renderScale(Entity entity, float partialTick) {
        if (entity == null || entity.isInvisible() || !this.appliesTo(entity)) {
            return UNIT;
        }
        float sx = this.widthScale.getValue().floatValue();
        float sy = this.heightScale.getValue().floatValue();

        // 受击弹簧: 压扁 -> 过冲 -> 收敛。
        float hurt = this.onHurt.getValue() ? DuangAnimator.verticalScale(entity.getId(), partialTick) : 1.0f;
        // 全程形变: 与受击无关的持续呼吸, 按 partialTick 连续取值, 所以任何帧率都是平滑的。
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

    /**
     * 持续形变量: 两条不同频率的正弦叠加, 相位按实体 id 打散。
     *
     * <p>单条正弦一眼就看得出是"程序在呼吸" —— 周期恒定、波峰波谷等宽。叠一个非整数倍频
     * (0.37 倍)之后波形每拍都不同, 相邻实体的相位也错开, 一群怪不会整齐划一地一起鼓。
     *
     * <p>时间用 {@code (tickCount + partialTick) / 20}, 也就是把帧间时间也喂进去 —— 这是
     * 它比受击弹簧更容易做到丝滑的原因: 不经过任何按 tick 离散的状态。
     */
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
