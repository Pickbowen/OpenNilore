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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.entity.projectile.ThrownEgg;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.LingeringPotionItem;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.SplashPotionItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import shit.lizz.event.impl.Render2DEvent;
import shit.lizz.event.impl.RenderEvent;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.modules.impl.player.MidPearl;
import shit.lizz.modules.impl.render.esp.ArrowEspColor;
import shit.lizz.modules.impl.render.esp.ClassEspColor;
import shit.lizz.modules.impl.render.esp.EspColorProvider;
import shit.lizz.modules.impl.render.esp.PotionEspColor;
import shit.lizz.render.DrawContext;
import shit.lizz.render.FontRenderer;
import shit.lizz.render.Fonts;
import shit.lizz.render.Paint;
import shit.lizz.render.Renderer;
import shit.lizz.render.RoundedRectangle;
import shit.lizz.settings.impl.BooleanSetting;
import shit.lizz.settings.impl.NumberSetting;
import shit.lizz.utils.game.RayTraceUtil;
import shit.lizz.utils.math.Vector2f;
import shit.lizz.utils.render.ProjectionUtil;
import shit.lizz.utils.render.RenderUtil;
import shit.lizz.event.EventTarget;

public class Projectiles extends Module {
    public static Projectiles INSTANCE;

    public static final class ProjectileEntry {
        private final String name;
        private final double flightTime;
        private final double distance;
        private final long timestamp;
        private final Item item;
        private final Vec3 velocity;

        public ProjectileEntry(String name, double flightTime, double distance, long timestamp, Item item, Vec3 velocity) {
            this.name = name;
            this.flightTime = flightTime;
            this.distance = distance;
            this.timestamp = timestamp;
            this.item = item;
            this.velocity = velocity;
        }

        public String getName() { return name; }
        public double getX() { return flightTime; }
        public double getZ() { return distance; }
        public long getTimestamp() { return timestamp; }
        public Item getItem() { return item; }
        public Vec3 getVelocity() { return velocity; }
    }

    public record SimulationResult(List<Vec3> path, HitResult hitResult) {
    }

    private final ArrowEspColor arrowsColor = new ArrowEspColor();
    private final PotionEspColor potionsColor = new PotionEspColor();
    private final ClassEspColor enderPearlColor = new ClassEspColor(Collections.singleton(ThrownEnderpearl.class), new Color(173, 12, 255));
    private final ClassEspColor eggColor = new ClassEspColor(Collections.singleton(ThrownEgg.class), new Color(255, 238, 154));
    private final ClassEspColor snowballColor = new ClassEspColor(Collections.singleton(Snowball.class), new Color(255, 255, 255));
    public BooleanSetting showArrows = new BooleanSetting("Show Arrows", true);
    public BooleanSetting showPearls = new BooleanSetting("Show Pearls", true);
    public BooleanSetting showPotions = new BooleanSetting("Show Potions", false);
    public BooleanSetting showEggs = new BooleanSetting("Show Eggs", false);
    public BooleanSetting showSnowballs = new BooleanSetting("Show Snowballs", false);
    private final NumberSetting lineWidth = new NumberSetting("Scale", 0.4, 0.2, 2.0, 0.01);
    private final FontRenderer fontRenderer = Fonts.getRenderer("pingfang_sc_regular.ttf", 32.0f);
    private final int backgroundColorRgb = new Color(0, 0, 0, 80).getRGB();
    public static final ConcurrentHashMap<Integer, ProjectileEntry> projectileMap = new ConcurrentHashMap<>();

    public Projectiles() {
        super("Projectiles", Category.RENDER);
        INSTANCE = this;
    }

    @EventTarget
    public void onRender3D(RenderEvent renderEvent) {
        if (mc.player == null || mc.level == null) return;
        ProjectionUtil.updateMatrices();
        projectileMap.keySet().removeIf(id -> {
            Entity entity = mc.level.getEntity(id);
            return entity == null || !entity.isAlive() || entity.onGround();
        });
        Item mainItem = mc.player.getMainHandItem().getItem();
        Item offItem = mc.player.getOffhandItem().getItem();
        boolean holdingThrowable = this.isThrowable(mainItem) || this.isThrowable(offItem);
        Item heldThrowable = this.isThrowable(mainItem) ? mainItem : offItem;
        PoseStack poseStack = renderEvent.poseStack();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Projectile)) continue;
            if (!entity.isAlive()) continue;
            EspColorProvider provider = this.getColorProvider(entity);
            if (provider == null) continue;
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.setShader(GameRenderer::getPositionShader);
            Color color = provider.getColor(entity);
            RenderSystem.setShaderColor(color.getRed() / 255.0f, color.getGreen() / 255.0f, color.getBlue() / 255.0f, 1.0f);
            this.renderProjectileEntity(poseStack, entity, provider);
            if (entity instanceof ThrownEnderpearl) {
                ProjectileEntry entry = this.buildProjectileEntry(entity, provider);
                if (entry != null) {
                    projectileMap.put(entity.getId(), entry);
                } else {
                    projectileMap.remove(entity.getId());
                }
            }
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            RenderSystem.disableBlend();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
        }
        SimulationResult simulation = null;
        if (holdingThrowable && this.isHoldingThrowable(mc.player, heldThrowable)) {
            simulation = this.simulateTrajectory(renderEvent.partialTick());
        }
        if (simulation == null) {
            poseStack.popPose();
            return;
        }
        List<Vec3> path = simulation.path();
        if (path.size() < 2) {
            poseStack.popPose();
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionShader);
        path.remove(0);
        this.drawLine(poseStack, path);
        if (!path.isEmpty()) {
            Vec3 endpoint = path.get(path.size() - 1);
            this.drawEndPoint(poseStack, endpoint, simulation.hitResult, renderEvent.partialTick());
        }
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        poseStack.popPose();
    }

    @EventTarget
    public void on2DRender(Render2DEvent event) {
        if (projectileMap.isEmpty()) return;
        float scale = this.lineWidth.getValue().floatValue();
        PoseStack poseStack = event.poseStack();
        projectileMap.forEach((id, entry) -> {
            if (entry.getItem() instanceof EnderpearlItem) {
                Vec3 vel = entry.getVelocity();
                Vector2f screenPos = ProjectionUtil.project(vel.x, vel.y, vel.z);
                if (screenPos == null) return;
                String text = String.format("Thrown by: %s%nLands in: %.1fs%nDistance: %.1fm",
                        entry.getName(), entry.getX(), entry.getZ());
                float maxW = 0.0f;
                for (String line : text.split("\n")) {
                    float w = this.fontRenderer.getBounds(line).getWidth();
                    if (w > maxW) maxW = w;
                }
                float lineH = this.fontRenderer.getMetrics().getLineHeight() + 2.0f;
                float blockH = lineH * 3.0f - 2.0f;
                float totalW = maxW + 16.0f;
                float totalH = blockH + 8.0f;
                float xOff = -totalW / 2.0f;
                float yOff = -totalH / 2.0f - 20.0f;
                poseStack.pushPose();
                poseStack.translate(screenPos.x, screenPos.y, 0.0f);
                poseStack.scale(scale, scale, 1.0f);
                RenderUtil.drawBlurredRect(poseStack, xOff, yOff, totalW, totalH, 4.0f, 8.0f, 1.0f, -1);
                poseStack.popPose();
            }
        });
        Renderer.renderConsumer(drawContext -> projectileMap.forEach((id, entry) -> {
            if (entry.getItem() instanceof EnderpearlItem) {
                Vec3 vel = entry.getVelocity();
                Vector2f screenPos = ProjectionUtil.project(vel.x, vel.y, vel.z);
                if (screenPos == null) return;
                this.drawProjectileLabel(drawContext, entry, scale, screenPos);
            }
        }));
    }

    private void drawProjectileLabel(DrawContext drawContext, ProjectileEntry entry, float scale, Vector2f pos) {
        drawContext.save();
        drawContext.translate(pos.x, pos.y);
        drawContext.scale(scale, scale);
        String text = String.format("Thrown by: %s%nLands in: %.1fs%nDistance: %.1fm",
                entry.getName(), entry.getX(), entry.getZ());
        String[] lines = text.split("\n");
        float maxW = 0.0f;
        for (String line : lines) {
            float w = this.fontRenderer.getBounds(line).getWidth();
            if (w > maxW) maxW = w;
        }
        float lineH = this.fontRenderer.getMetrics().getLineHeight() + 2.0f;
        float blockH = lineH * lines.length - 2.0f;
        float totalW = maxW + 16.0f;
        float totalH = blockH + 8.0f;
        float xOff = -totalW / 2.0f;
        float yOff = -totalH / 2.0f - 20.0f;
        float radius = 4.0f;
        try (Paint paint = new Paint()) {
            paint.setColor(this.backgroundColorRgb);
            drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(xOff, yOff, totalW, totalH, radius), paint);
            paint.setColor(Color.WHITE.getRGB());
            float textY = yOff + 4.0f - this.fontRenderer.getMetrics().ascent();
            for (String line : lines) {
                float textX = xOff + (totalW - this.fontRenderer.getBounds(line).getWidth()) / 2.0f;
                drawContext.drawString(line, textX, textY, this.fontRenderer, paint);
                textY += lineH;
            }
        }
        drawContext.restore();
    }

    private ProjectileEntry buildProjectileEntry(Entity entity, EspColorProvider provider) {
        if (mc.player == null) return null;
        double x = entity.getX(), y = entity.getY(), z = entity.getZ();
        double dx = entity.getDeltaMovement().x;
        double dy = entity.getDeltaMovement().y;
        double dz = entity.getDeltaMovement().z;
        Projectile projectile = (Projectile) entity;
        for (int step = 0; step < 1000; ++step) {
            Vec3 start = new Vec3(x, y, z);
            Vec3 end = new Vec3(x + dx, y + dy, z + dz);
            BlockHitResult blockHit = mc.level.clip(new ClipContext(
                    start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
            HitResult bestHit = blockHit;
            List<Entity> nearby = mc.level.getEntities(entity,
                    entity.getBoundingBox().expandTowards(dx, dy, dz).inflate(1.0),
                    e -> e.canBeCollidedWith() && e != projectile.getOwner());
            double bestDistSq = start.distanceToSqr(blockHit.getLocation());
            for (Entity other : nearby) {
                if (!(other instanceof LivingEntity)) continue;
                AABB box = other.getBoundingBox().inflate(0.3);
                EntityHitResult entityHit = RayTraceUtil.getEntityHit(box, start, end);
                if (entityHit == null) continue;
                double dsq = start.distanceToSqr(entityHit.getLocation());
                if (dsq < bestDistSq) {
                    bestDistSq = dsq;
                    bestHit = entityHit;
                }
            }
            if (bestHit != null && bestHit.getType() != HitResult.Type.MISS) {
                Vec3 hitLoc = bestHit.getLocation();
                Entity owner = projectile.getOwner();
                String ownerName = owner != null ? owner.getName().getString() : "Unknown";
                double flightTime = step / 20.0;
                double distance = mc.player.getEyePosition().distanceTo(hitLoc);
                return new ProjectileEntry(ownerName, flightTime, distance, System.currentTimeMillis(), Items.ENDER_PEARL, hitLoc);
            }
            x += dx;
            z += dz;
            y += dy;
            if (y < mc.level.getMinBuildHeight() - 10) break;
            dx *= 0.99;
            dy *= 0.99;
            dz *= 0.99;
            dy -= provider.getLineWidth();
        }
        return null;
    }

    private void drawLine(PoseStack poseStack, List<Vec3> path) {
        Matrix4f matrix4f = poseStack.last().pose();
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        RenderSystem.setShader(GameRenderer::getPositionShader);
        builder.begin(VertexFormat.Mode.DEBUG_LINE_STRIP, DefaultVertexFormat.POSITION);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        for (Vec3 vec3 : path) {
            builder.vertex(matrix4f, (float) vec3.x, (float) vec3.y, (float) vec3.z).endVertex();
        }
        BufferUploader.drawWithShader(builder.end());
    }

    private void drawEndPoint(PoseStack poseStack, Vec3 vec3, HitResult hitResult, float partial) {
        if (hitResult == null || hitResult.getType() == HitResult.Type.MISS) return;
        poseStack.pushPose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        if (hitResult instanceof BlockHitResult bh) {
            Vec3 loc = bh.getLocation();
            Direction dir = bh.getDirection();
            loc = loc.add(dir.getStepX() * 0.005, dir.getStepY() * 0.005, dir.getStepZ() * 0.005);
            this.drawFacePlane(poseStack, loc, dir, 0.4f, new Color(255, 255, 255, 120));
        } else if (hitResult instanceof EntityHitResult eh) {
            Entity entity = eh.getEntity();
            double x = Mth.lerp(partial, entity.xOld, entity.getX());
            double y = Mth.lerp(partial, entity.yOld, entity.getY());
            double z = Mth.lerp(partial, entity.zOld, entity.getZ());
            AABB box = entity.getBoundingBox().move(x - entity.getX(), y - entity.getY(), z - entity.getZ());
            RenderUtil.drawOutlineBox(box, poseStack);
            Vec3 bottom = new Vec3(box.getCenter().x, box.minY, box.getCenter().z);
            this.drawFacePlane(poseStack, bottom, Direction.UP, (float) (entity.getBbWidth() * 0.8), new Color(255, 0, 0, 100));
        }
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        poseStack.popPose();
    }

    private void drawFacePlane(PoseStack poseStack, Vec3 center, Direction direction, float radius, Color color) {
        Matrix4f matrix4f = poseStack.last().pose();
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        float r = color.getRed() / 255.0f;
        float g = color.getGreen() / 255.0f;
        float b = color.getBlue() / 255.0f;
        float a = color.getAlpha() / 255.0f;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        builder.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        Vec3 normal = new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
        Vec3 up = Math.abs(normal.y) > 0.9 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
        Vec3 tangent = normal.cross(up).normalize();
        Vec3 bitangent = normal.cross(tangent).normalize();
        builder.vertex(matrix4f, (float) center.x, (float) center.y, (float) center.z).color(r, g, b, a).endVertex();
        int segments = 40;
        for (int i = 0; i <= segments; ++i) {
            double angle = Math.PI * 2 * i / segments;
            double dx = radius * Math.cos(angle);
            double dy = radius * Math.sin(angle);
            Vec3 point = center.add(tangent.scale(dx)).add(bitangent.scale(dy));
            builder.vertex(matrix4f, (float) point.x, (float) point.y, (float) point.z).color(r, g, b, a).endVertex();
        }
        BufferUploader.drawWithShader(builder.end());
    }

    private SimulationResult simulateTrajectory(float partial) {
        LocalPlayer localPlayer = mc.player;
        ArrayList<Vec3> path = new ArrayList<>();
        ItemStack stack = localPlayer.getMainHandItem();
        Item item = stack.getItem();
        if (stack.isEmpty() || !this.isThrowable(item) || !this.isHoldingThrowable(localPlayer, item)) return null;
        double x = localPlayer.xOld + (localPlayer.getX() - localPlayer.xOld) * partial;
        double y = localPlayer.yOld + (localPlayer.getY() - localPlayer.yOld) * partial + localPlayer.getEyeHeight() - 0.1;
        double z = localPlayer.zOld + (localPlayer.getZ() - localPlayer.zOld) * partial;
        double power = item instanceof ProjectileWeaponItem ? 1.0 : 0.4;
        double yawRad, pitchRad;
        if (MidPearl.INSTANCE != null && MidPearl.INSTANCE.isEnabled() && MidPearl.targetRotation != null) {
            yawRad = Math.toRadians(MidPearl.targetRotation.getYaw());
            pitchRad = Math.toRadians(MidPearl.targetRotation.getPitch());
        } else {
            yawRad = Math.toRadians(Mth.lerp(partial, localPlayer.yRotO, localPlayer.getYRot()));
            pitchRad = Math.toRadians(Mth.lerp(partial, localPlayer.xRotO, localPlayer.getXRot()));
        }
        double dx = -Math.sin(yawRad) * Math.cos(pitchRad) * power;
        double dy = -Math.sin(pitchRad) * power;
        double dz = Math.cos(yawRad) * Math.cos(pitchRad) * power;
        double speed = Math.sqrt(dx * dx + dy * dy + dz * dz);
        dx /= speed;
        dy /= speed;
        dz /= speed;
        if (item instanceof ProjectileWeaponItem) {
            float pull = (72000 - localPlayer.getUseItemRemainingTicks()) / 20.0f;
            pull = (pull * pull + pull * 2.0f) / 3.0f;
            if (pull > 1.0f || pull <= 0.1f) pull = 1.0f;
            pull *= 3.0f;
            dx *= pull;
            dy *= pull;
            dz *= pull;
        } else {
            dx *= 1.5;
            dy *= 1.5;
            dz *= 1.5;
        }
        double gravity = this.getProjectileGravity(item);
        for (int i = 0; i < 1000; ++i) {
            Vec3 start = new Vec3(x, y, z);
            Vec3 end = new Vec3(x + dx, y + dy, z + dz);
            path.add(start);
            ClipContext clip = new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);
            BlockHitResult blockHit = mc.level.clip(clip);
            if (blockHit.getType() != HitResult.Type.MISS) {
                return new SimulationResult(path, blockHit);
            }
            Arrow arrow = new Arrow(mc.level, x, y, z);
            EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(mc.level, arrow, start, end,
                    arrow.getBoundingBox().expandTowards(new Vec3(dx, dy, dz)).inflate(1.0),
                    e -> Projectiles.isPlayerOwnedProjectile(localPlayer, e));
            if (entityHit != null && entityHit.getType() == HitResult.Type.ENTITY) {
                return new SimulationResult(path, entityHit);
            }
            x += dx;
            y += dy;
            z += dz;
            dx *= 0.99;
            dy *= 0.99;
            dz *= 0.99;
            dy -= gravity;
        }
        return new SimulationResult(path, null);
    }

    private double getProjectileGravity(Item item) {
        if (item instanceof BowItem || item instanceof CrossbowItem) return 0.05;
        if (item instanceof PotionItem) return 0.4;
        if (item instanceof FishingRodItem) return 0.15;
        if (item instanceof TridentItem) return 0.015;
        return 0.03;
    }

    private boolean isThrowable(Item item) {
        return item instanceof BowItem || item instanceof CrossbowItem
                || item instanceof SnowballItem || item instanceof EggItem
                || item instanceof EnderpearlItem || item instanceof SplashPotionItem
                || item instanceof LingeringPotionItem || item instanceof FishingRodItem
                || item instanceof TridentItem;
    }

    private boolean isHoldingThrowable(Player player, Item item) {
        if (item instanceof BowItem || item instanceof CrossbowItem) {
            return player.isUsingItem();
        }
        return true;
    }

    private void renderProjectileEntity(PoseStack poseStack, Entity entity, EspColorProvider provider) {
        if (entity == null) return;
        LocalPlayer localPlayer = mc.player;
        ClientLevel level = mc.level;
        Color color = provider.getColor(entity);
        if (color == null) color = new Color(255, 255, 255);
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.DEBUG_LINE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        double x = entity.getX(), y = entity.getY(), z = entity.getZ();
        double dx = entity.getDeltaMovement().x;
        double dy = entity.getDeltaMovement().y;
        double dz = entity.getDeltaMovement().z;
        this.drawVertex(color, builder, poseStack, x, y, z);
        for (int step = 0; step < 1000; ++step) {
            float halfWidth = provider.getFillAlpha();
            float height = provider.getOutlineAlpha();
            AABB box = new AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + height, z + halfWidth);
            Vec3 start = new Vec3(x, y, z);
            Vec3 end = new Vec3(x + dx, y + dy, z + dz);
            HitResult hit = RayTraceUtil.clipWithEntity(start, end, false, entity instanceof Arrow, false, entity);
            if (hit != null && !hit.getType().equals(HitResult.Type.MISS)) {
                end = new Vec3(hit.getLocation().x(), hit.getLocation().y(), hit.getLocation().z());
            }
            List<Entity> entities = level.getEntities(localPlayer, box.contract(dx, dy, dz).expandTowards(1.0, 1.0, 1.0));
            double bestDist = 0.0;
            for (Entity other : entities) {
                if (!(other instanceof LivingEntity) || other instanceof EnderMan
                        || !other.canBeCollidedWith() || other.equals(localPlayer)) continue;
                AABB otherBox = other.getBoundingBox().expandTowards(0.3, 0.3, 0.3);
                EntityHitResult ehit = RayTraceUtil.getEntityHit(otherBox, start, end);
                if (ehit == null) continue;
                double dist = start.distanceTo(ehit.getLocation());
                if (dist < bestDist || bestDist == 0.0) {
                    bestDist = dist;
                    hit = ehit;
                }
            }
            x += dx;
            y += dy;
            z += dz;
            if (hit != null && !hit.getType().equals(HitResult.Type.MISS)) {
                x = hit.getLocation().x();
                y = hit.getLocation().y();
                z = hit.getLocation().z();
                break;
            }
            if (y < -128.0) break;
            double drag = entity.isInWater() ? 0.8 : 0.99;
            dy = dy * drag - provider.getLineWidth();
            dx *= drag;
            dz *= drag;
            this.drawVertex(color, builder, poseStack, x, y, z);
        }
        BufferUploader.drawWithShader(builder.end());
    }

    private void drawVertex(Color color, BufferBuilder builder, PoseStack poseStack, double x, double y, double z) {
        builder.vertex(poseStack.last().pose(), (float) x, (float) y, (float) z).color(color.getRGB()).endVertex();
    }

    private EspColorProvider getColorProvider(Entity entity) {
        if (entity.onGround()) return null;
        if (entity.getX() == entity.xOld && entity.getZ() == entity.zOld) return null;
        for (EspColorProvider provider : this.getProjectileInfos()) {
            if (provider.shouldHighlight(entity)) return provider;
        }
        return null;
    }

    private List<EspColorProvider> getProjectileInfos() {
        ArrayList<EspColorProvider> list = new ArrayList<>();
        if (this.showArrows.getValue()) list.add(this.arrowsColor);
        if (this.showPotions.getValue()) list.add(this.potionsColor);
        if (this.showPearls.getValue()) list.add(this.enderPearlColor);
        if (this.showEggs.getValue()) list.add(this.eggColor);
        if (this.showSnowballs.getValue()) list.add(this.snowballColor);
        return list;
    }

    private static boolean isPlayerOwnedProjectile(Player player, Entity entity) {
        return entity != player && entity instanceof LivingEntity;
    }
}
