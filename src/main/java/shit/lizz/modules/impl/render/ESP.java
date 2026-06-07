package shit.lizz.modules.impl.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4d;
import shit.lizz.event.impl.Render2DEvent;
import shit.lizz.event.impl.RenderEvent;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.settings.impl.BooleanSetting;
import shit.lizz.settings.impl.ModeSetting;
import shit.lizz.utils.game.EntityUtil;
import shit.lizz.utils.math.Vector2f;
import shit.lizz.utils.render.ColorUtil;
import shit.lizz.utils.render.ProjectionUtil;
import shit.lizz.utils.render.RenderUtil;
import shit.lizz.event.EventTarget;

public class ESP extends Module {
    public static ESP INSTANCE;

    public record Pair<A, B>(A first, B second) {

        public static <A, B> Pair<A, B> of(A a, B b) {
                return new Pair<>(a, b);
            }
        }

    private final ModeSetting modeSetting = new ModeSetting("Mode", "Glow", "Outlined 2D").withDefault("Outlined 2D");
    private final BooleanSetting skeletonSetting = new BooleanSetting("Skeleton", false);
    private final BooleanSetting playersSetting = new BooleanSetting("Players", true);
    private final BooleanSetting mobsSetting = new BooleanSetting("Mobs", false);
    private final BooleanSetting animalsSetting = new BooleanSetting("Animals", false);
    private final BooleanSetting itemsSetting = new BooleanSetting("Items", false);
    private final BooleanSetting arrowsSetting = new BooleanSetting("Arrows", true);
    private final Map<Entity, Pair<Vector4d, Boolean>> entityBoxPositions = new HashMap<>();
    private final Map<Entity, float[][]> playerBoneRotations = new HashMap<>();
    private final BooleanSetting showHealthBarSetting = new BooleanSetting("Show Health Bar", true);
    private final ModeSetting healthBarPositionSetting = new ModeSetting("Health Bar Position", "Bottom", "Top", "Left", "Right").withDefault("Bottom");
    private final List<Entity> visibleEntities = new ArrayList<>();
    private final List<Vector2f> projectedPoints = new ArrayList<>();

    public ESP() {
        super("ESP", Category.RENDER);
        INSTANCE = this;
    }

    public boolean isGlowing(Entity entity) {
        if (this.isEnabled() && "Glow".equalsIgnoreCase(this.modeSetting.getValue())) {
            if (entity instanceof Player && this.playersSetting.getValue()) return true;
            if (entity instanceof Animal && this.animalsSetting.getValue()) return true;
            if (entity instanceof Mob && this.mobsSetting.getValue()) return true;
            if (entity instanceof ItemEntity && this.itemsSetting.getValue()) return true;
            return entity instanceof Arrow && this.arrowsSetting.getValue();
        }
        return false;
    }

    @Override
    protected void onEnable() {
        super.onEnable();
    }

    @Override
    protected void onDisable() {
        this.entityBoxPositions.clear();
        this.playerBoneRotations.clear();
        this.visibleEntities.clear();
        super.onDisable();
    }

    private boolean shouldShowEntity(Entity entity) {
        if (entity == mc.player) return false;
        if (entity instanceof Player && this.playersSetting.getValue()) return true;
        if (entity instanceof Animal && this.animalsSetting.getValue()) return true;
        if (entity instanceof Mob && this.mobsSetting.getValue()) return true;
        return entity instanceof ItemEntity && this.itemsSetting.getValue();
    }

    private boolean isInRange(Entity entity) {
        double distSq = mc.player.distanceToSqr(entity);
        return distSq < 10000.0;
    }

    @EventTarget
    public void onRender(RenderEvent renderEvent) {
        if (mc.level == null || mc.player == null) return;
        if (!"Outlined 2D".equals(this.modeSetting.getValue())) {
            this.entityBoxPositions.clear();
            return;
        }
        ProjectionUtil.updateMatrices();
        this.entityBoxPositions.clear();
        this.visibleEntities.clear();
        float partial = renderEvent.partialTick();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!this.shouldShowEntity(entity) || !this.isInRange(entity)) continue;
            this.visibleEntities.add(entity);
        }
        for (Entity entity : this.visibleEntities) {
            AABB aabb = EntityUtil.getInterpolatedAABB(entity, partial);
            Vec3[] corners = new Vec3[]{
                    new Vec3(aabb.minX, aabb.minY, aabb.minZ), new Vec3(aabb.maxX, aabb.minY, aabb.minZ),
                    new Vec3(aabb.maxX, aabb.minY, aabb.maxZ), new Vec3(aabb.minX, aabb.minY, aabb.maxZ),
                    new Vec3(aabb.minX, aabb.maxY, aabb.minZ), new Vec3(aabb.maxX, aabb.maxY, aabb.minZ),
                    new Vec3(aabb.maxX, aabb.maxY, aabb.maxZ), new Vec3(aabb.minX, aabb.maxY, aabb.maxZ)
            };
            this.projectedPoints.clear();
            boolean ok = true;
            for (Vec3 corner : corners) {
                Vector2f projected = ProjectionUtil.project(corner.x, corner.y, corner.z);
                if (projected == null) {
                    ok = false;
                    break;
                }
                this.projectedPoints.add(projected);
            }
            if (!ok || this.projectedPoints.isEmpty()) continue;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;
            for (Vector2f point : this.projectedPoints) {
                if (point.x < minX) minX = point.x;
                if (point.y < minY) minY = point.y;
                if (point.x > maxX) maxX = point.x;
                if (point.y > maxY) maxY = point.y;
            }
            int pad = 3;
            Vector4d box = new Vector4d((int) (minX - pad), (int) (minY - pad), (int) (maxX - minX + pad * 2), (int) (maxY - minY + pad * 2));
            this.entityBoxPositions.put(entity, Pair.of(box, true));
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!"Outlined 2D".equals(this.modeSetting.getValue()) || this.entityBoxPositions.isEmpty()) return;
        Matrix4f matrix4f = event.guiGraphics().pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder builder = tess.getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (Map.Entry<Entity, Pair<Vector4d, Boolean>> entry : this.entityBoxPositions.entrySet()) {
            Vector4d v = entry.getValue().first;
            if (v.z <= 0.0 || v.w <= 0.0) continue;
            Entity entity = entry.getKey();
            Color color = entity instanceof Player p ? ColorUtil.getPlayerColor(p) : Color.WHITE;
            float x1 = (float) v.x();
            float y1 = (float) v.y();
            float x2 = x1 + (float) v.z();
            float y2 = y1 + (float) v.w();
            this.drawFilledRect2D(builder, matrix4f, x1, y1, x2, y2, color);
            if (entity instanceof LivingEntity le && this.showHealthBarSetting.getValue()) {
                this.drawHealthBar(builder, matrix4f, le, v, color);
            }
        }
        BufferUploader.drawWithShader(builder.end());
        RenderSystem.disableBlend();
    }

    private void drawFilledRect2D(BufferBuilder builder, Matrix4f matrix4f, float x1, float y1, float x2, float y2, Color color) {
        RenderUtil.drawQuad(builder, matrix4f, x1 - 1.0f, y1, x1 + 0.5f, y2 + 0.5f, Color.BLACK);
        RenderUtil.drawQuad(builder, matrix4f, x1 - 1.0f, y1 - 0.5f, x2 + 0.5f, y1 + 1.0f, Color.BLACK);
        RenderUtil.drawQuad(builder, matrix4f, x2 - 0.5f, y1, x2 + 0.5f, y2 + 0.5f, Color.BLACK);
        RenderUtil.drawQuad(builder, matrix4f, x1 - 1.0f, y2 - 0.5f, x2 + 0.5f, y2 + 0.5f, Color.BLACK);
        RenderUtil.drawQuad(builder, matrix4f, x1 - 0.5f, y1, x1, y2, color);
        RenderUtil.drawQuad(builder, matrix4f, x1, y2 - 0.5f, x2, y2, color);
        RenderUtil.drawQuad(builder, matrix4f, x1, y1, x2, y1 + 0.5f, color);
        RenderUtil.drawQuad(builder, matrix4f, x2 - 0.5f, y1, x2, y2, color);
    }

    private void drawHealthBar(BufferBuilder builder, Matrix4f matrix4f, LivingEntity entity, Vector4d v, Color color) {
        float healthFrac;
        if (entity instanceof Player p) {
            healthFrac = Mth.clamp(Math.min(p.getHealth(), p.getMaxHealth()) / p.getMaxHealth(), 0.0f, 1.0f);
        } else {
            healthFrac = Math.min(entity.getHealth() / entity.getMaxHealth(), 1.0f);
        }
        float x = (float) v.x();
        float y = (float) v.y();
        float right = x + (float) v.z();
        float bottom = y + (float) v.w();
        String position = this.healthBarPositionSetting.getValue();
        if (position == null) position = "Bottom";
        float barX, barY, barW, barH;
        switch (position) {
            case "Top" -> { barW = (float) v.z(); barH = 2.0f; barX = x; barY = y - 4.0f; }
            case "Left" -> { barW = 2.0f; barH = (float) v.w(); barX = x - 4.0f; barY = y; }
            case "Right" -> { barW = 2.0f; barH = (float) v.w(); barX = right + 2.0f; barY = y; }
            default -> { barW = (float) v.z(); barH = 2.0f; barX = x; barY = bottom + 2.0f; }
        }
        RenderUtil.drawQuad(builder, matrix4f, barX - 0.6f, barY - 0.6f, barX + barW + 0.6f, barY + barH + 0.6f, Color.BLACK);
        RenderUtil.drawQuad(builder, matrix4f, barX, barY, barX + barW, barY + barH, color.darker().darker());
        Color healthColor = this.getHealthColor(healthFrac);
        if (position.equals("Left") || position.equals("Right")) {
            RenderUtil.drawQuad(builder, matrix4f, barX, barY + barH * (1.0f - healthFrac), barX + barW, barY + barH, healthColor);
        } else {
            RenderUtil.drawQuad(builder, matrix4f, barX, barY, barX + barW * healthFrac, barY + barH, healthColor);
        }
    }

    private void renderSkeleton(PoseStack poseStack, float partial) {
        // Skeleton rendering omitted (heavy obfuscated code); enable Glow mode instead.
    }

    private Color getHealthColor(float fraction) {
        return Color.getHSBColor(Math.max(0.0f, fraction) / 3.0f, 1.0f, 1.0f);
    }
}
