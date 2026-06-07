package shit.lizz.modules.impl.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import shit.lizz.event.impl.EntityHurtEvent;
import shit.lizz.event.impl.EntityRemoveEvent;
import shit.lizz.event.impl.RenderEvent;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.settings.impl.NumberSetting;
import shit.lizz.event.EventTarget;

public class DamageGlow extends Module {

    public record EntitySnapshot(long startTime, long expireTime, double snapshotX, double snapshotY, double snapshotZ,
                                 double xo, double yo, double zo, float yRot, float xRot, float yHeadRot,
                                 float xRotCopy, float yBodyRot, float yBodyRotO, float yHeadRotO, float xRotO,
                                 float walkAnimPos, float walkAnimSpeed, float attackAnim, float tickCount,
                                 float headYawDelta, float pitch, int hurtTime) {
    }

    public static DamageGlow INSTANCE;

    private final Map<Integer, List<EntitySnapshot>> glowingEntities = new ConcurrentHashMap<>();
    private final NumberSetting colorRSetting = new NumberSetting("Color R", 0, 0, 255, 1);
    private final NumberSetting colorGSetting = new NumberSetting("Color G", 0, 0, 255, 1);
    private final NumberSetting colorBSetting = new NumberSetting("Color B", 0, 0, 255, 1);
    private final NumberSetting alphaSetting = new NumberSetting("Alpha", 45.0, 0.0, 255.0, 1.0);

    public DamageGlow() {
        super("DamageGlow", Category.RENDER);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.glowingEntities.clear();
    }

    private void addGlowEffect(LivingEntity entity) {
        List<EntitySnapshot> list = this.glowingEntities.computeIfAbsent(entity.getId(), k -> new CopyOnWriteArrayList<>());
        this.cleanExpiredGlows(list);
        EntitySnapshot last = list.isEmpty() ? null : list.get(list.size() - 1);
        int hurt = entity.hurtTime;
        long now = System.currentTimeMillis();
        if (hurt <= 0) return;
        if (last != null) {
            int elapsed = (int) ((now - last.startTime) / 50L);
            int remainder = Math.max(0, last.hurtTime - elapsed);
            if (hurt <= remainder) return;
        }
        float headYawDelta = entity.getYHeadRot() - entity.yBodyRot;
        float pitch = entity.getXRot();
        float tickCount = entity.tickCount + mc.getFrameTime();
        EntitySnapshot snapshot = new EntitySnapshot(now, now + 1500L,
                entity.getX(), entity.getY(), entity.getZ(),
                entity.xo, entity.yo, entity.zo,
                entity.getYRot(), entity.getXRot(), entity.getYHeadRot(), entity.getXRot(),
                entity.yBodyRot, entity.yBodyRotO, entity.yHeadRotO, entity.xRotO,
                entity.walkAnimation.position(0.0f), entity.walkAnimation.speed(0.0f), entity.attackAnim,
                tickCount, headYawDelta, pitch, hurt);
        list.add(snapshot);
        if (list.size() > 6) {
            list.remove(0);
        }
    }

    private void cleanExpiredGlows(List<EntitySnapshot> list) {
        long now = System.currentTimeMillis();
        list.removeIf(s -> now > s.expireTime);
    }

    @EventTarget
    public void onEntityHurt(EntityHurtEvent event) {
        if (event.entity() instanceof Player && event.entity() == mc.player) return;
        LivingEntity entity = event.entity();
        this.addGlowEffect(entity);
    }

    @EventTarget
    public void onEntityRemove(EntityRemoveEvent event) {
        if (event.dead()) return;
        if (event.entity() instanceof Player && event.entity() == mc.player) return;
        if (event.entity() instanceof LivingEntity entity) {
            this.addGlowEffect(entity);
        }
    }

    @EventTarget
    public void onRender(RenderEvent renderEvent) {
        if (mc.level == null) return;
        for (Map.Entry<Integer, List<EntitySnapshot>> entry : this.glowingEntities.entrySet()) {
            Integer id = entry.getKey();
            List<EntitySnapshot> list = entry.getValue();
            this.cleanExpiredGlows(list);
            if (list.isEmpty()) continue;
            Entity entity = mc.level.getEntity(id);
            if (entity instanceof LivingEntity le) {
                this.renderEntityGlow(le, list, renderEvent);
            }
        }
        this.glowingEntities.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void renderEntityGlow(LivingEntity entity, List<EntitySnapshot> list, RenderEvent renderEvent) {
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        for (EntitySnapshot snapshot : list) {
            float alpha = this.calcGlowAlpha(snapshot);
            if (alpha <= 0.0f) continue;
            EntityRenderer renderer = mc.getEntityRenderDispatcher().getRenderer(entity);
            if (!(renderer instanceof LivingEntityRenderer livingRenderer)) continue;
            EntityModel model = livingRenderer.getModel();
            Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
            float intensity = alpha * 0.18f;
            if (intensity <= 0.0f) continue;
            int saved = entity.hurtTime;
            entity.hurtTime = 0;
            try {
                PoseStack poseStack = renderEvent.poseStack();
                poseStack.pushPose();
                poseStack.translate(snapshot.snapshotX - camera.x, snapshot.snapshotY - camera.y, snapshot.snapshotZ - camera.z);
                poseStack.mulPose(Axis.YP.rotationDegrees(180.0f - snapshot.yRot));
                poseStack.mulPose(Axis.XP.rotationDegrees(snapshot.xRot));
                poseStack.scale(-1.0f, -1.0f, 1.0f);
                poseStack.translate(0.0f, -1.501f, 0.0f);
                model.prepareMobModel(entity, snapshot.walkAnimPos, snapshot.walkAnimSpeed, 0.0f);
                model.attackTime = snapshot.attackAnim;
                model.setupAnim(entity, snapshot.walkAnimPos, snapshot.walkAnimSpeed, snapshot.tickCount, snapshot.headYawDelta, snapshot.pitch);
                RenderType type = RenderType.entityTranslucentEmissive(renderer.getTextureLocation(entity));
                VertexConsumer consumer = bufferSource.getBuffer(type);
                float r = this.colorRSetting.getValue().intValue() / 255.0f;
                float g = this.colorGSetting.getValue().intValue() / 255.0f;
                float b = this.colorBSetting.getValue().intValue() / 255.0f;
                float a = this.alphaSetting.getValue().intValue() / 255.0f * intensity;
                model.renderToBuffer(poseStack, consumer, 0xF000F0,
                        LivingEntityRenderer.getOverlayCoords(entity, 0.0f), r, g, b, a);
                poseStack.popPose();
                bufferSource.endBatch(type);
            } finally {
                entity.hurtTime = saved;
            }
        }
    }

    private float calcGlowAlpha(EntitySnapshot snapshot) {
        long remaining = snapshot.expireTime - System.currentTimeMillis();
        if (remaining <= 0L) return 0.0f;
        return Math.min(1.0f, remaining / 1500.0f);
    }
}
