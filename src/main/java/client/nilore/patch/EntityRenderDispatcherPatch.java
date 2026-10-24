package client.nilore.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import client.nilore.NiloreClient;
import client.nilore.modules.impl.render.EntityDuang;
import client.nilore.modules.impl.render.EntityEditor;
import client.nilore.modules.impl.render.FakeAntiAim;
import client.nilore.utils.render.EntityUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Patch(EntityRenderDispatcher.class)
public class EntityRenderDispatcherPatch {

    private static final ThreadLocal<Boolean> IS_RENDERING_FAKE = ThreadLocal.withInitial(() -> false);
    private static final Map<Integer, float[]> LAUNCH_ROTATION_CACHE = new ConcurrentHashMap<>();

    @Inject(
            method = "render",
            desc = "(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(At.Type.HEAD)
    )
    public static void onRender(
            EntityRenderDispatcher dispatcher, Entity entity,
            double x, double y, double z, float yaw, float partialTick,
            com.mojang.blaze3d.vertex.PoseStack poseStack,
            net.minecraft.client.renderer.MultiBufferSource buffer, int light,
            CallbackInfo ci
    ) {
        if (IS_RENDERING_FAKE.get()) return;

        Minecraft mc = Minecraft.getInstance();
        if (!NiloreClient.isReady()) return;

        boolean shouldProcessEditor = EntityEditor.INSTANCE != null && EntityEditor.INSTANCE.isEnabled();

        if (shouldProcessEditor) {
            if (!(entity instanceof net.minecraft.world.entity.player.Player) && !EntityEditor.INSTANCE.getAllEntity()) shouldProcessEditor = false;
            if (entity.isInvisible()) shouldProcessEditor = false;
            if (entity == mc.player && EntityEditor.INSTANCE.getIgnoreSelf()) shouldProcessEditor = false;
            if (EntityEditor.INSTANCE.getExcludeDrops() && (entity instanceof ItemEntity || entity instanceof ExperienceOrb)) shouldProcessEditor = false;

            if (shouldProcessEditor) {
                String target = EntityEditor.INSTANCE.getTargetEntity();
                Entity fake = EntityUtil.getOrCreateSyncedEntity(entity, target);
                if (fake != null) {
                    ci.cancel();

                    IS_RENDERING_FAKE.set(true);
                    try {
                        poseStack.pushPose();

                        if ((entity instanceof ItemEntity || entity instanceof ExperienceOrb) && EntityEditor.INSTANCE.getBetterItemView()) {
                            poseStack.translate(x, y, z);
                            float speedFactor = (entity instanceof net.minecraft.world.entity.ExperienceOrb) ? 20.0F : 10.0F;
                            float itemRotation = (entity.tickCount + partialTick) * speedFactor;
                            poseStack.mulPose(Axis.YP.rotationDegrees(itemRotation));
                            float scaleFactor = (entity instanceof net.minecraft.world.entity.ExperienceOrb) ? 0.2F : 0.45F;
                            poseStack.scale(scaleFactor, scaleFactor, scaleFactor);
                            poseStack.translate(-x, -y, -z);
                        }
                        else if (entity instanceof Projectile projectile && EntityEditor.INSTANCE.getBetterItemView()) {
                            int id = projectile.getId();
                            if (projectile.isRemoved()) {
                                LAUNCH_ROTATION_CACHE.remove(id);
                            } else {
                                if (projectile.tickCount <= 1 && !LAUNCH_ROTATION_CACHE.containsKey(id)) {
                                    Entity owner = projectile.getOwner();
                                    if (owner != null) {
                                        LAUNCH_ROTATION_CACHE.put(id, new float[]{owner.getYRot(), owner.getXRot()});
                                    }
                                }
                            }

                            float[] launchRot = LAUNCH_ROTATION_CACHE.get(id);
                            if (launchRot != null) {
                                float launchYaw = launchRot[0];
                                float launchPitch = launchRot[1];

                                poseStack.translate(x, y, z);
                                poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - launchYaw));
                                poseStack.mulPose(Axis.XP.rotationDegrees(launchPitch));

                                boolean isLongProjectile = (projectile instanceof AbstractArrow) || (projectile instanceof ThrownTrident);
                                if (isLongProjectile) {
                                    poseStack.scale(0.45F, 0.45F, 1.2F);
                                } else {
                                    poseStack.scale(0.45F, 0.45F, 0.45F);
                                }
                                poseStack.translate(-x, -y, -z);
                                yaw = launchYaw;
                            } else {
                                poseStack.translate(x, y, z);
                                poseStack.scale(0.45F, 0.45F, 0.45F);
                                poseStack.translate(-x, -y, -z);
                            }
                        }

                        // 视觉宽高 / duang: 以脚底为锚点缩放, 所以"变高"是向上长, 而不是向两侧撑开。
                        applyVisualScale(poseStack, entity, partialTick, x, y, z);

                        float[] savedHead = NO_HEAD_SAVE;
                        if (entity == mc.player && FakeAntiAim.INSTANCE != null && FakeAntiAim.INSTANCE.isEnabled()) {
                            savedHead = new float[4];
                            float time = entity.tickCount + partialTick;
                            yaw = applyFakeAntiAim(FakeAntiAim.INSTANCE, fake, poseStack, x, y, z, time, entity.tickCount, yaw, savedHead);
                        }

                        dispatcher.render(fake, x, y, z, yaw, partialTick, poseStack, buffer, light);
                        restoreHead(fake, savedHead);
                        poseStack.popPose();
                    } catch (Exception ignored) {
                    } finally {
                        IS_RENDERING_FAKE.set(false);
                    }
                    return;
                }
            }
        }

        if (FakeAntiAim.INSTANCE != null && FakeAntiAim.INSTANCE.isEnabled() && entity == mc.player) {
            ci.cancel();

            IS_RENDERING_FAKE.set(true);
            try {
                poseStack.pushPose();

                applyVisualScale(poseStack, entity, partialTick, x, y, z);

                float[] savedHead = new float[4];
                float time = entity.tickCount + partialTick;
                float bodyYaw = applyFakeAntiAim(FakeAntiAim.INSTANCE, entity, poseStack, x, y, z, time, entity.tickCount, yaw, savedHead);

                dispatcher.render(entity, x, y, z, bodyYaw, partialTick, poseStack, buffer, light);
                restoreHead(entity, savedHead);

                poseStack.popPose();
            } catch (Exception ignored) {
            } finally {
                IS_RENDERING_FAKE.set(false);
            }
            return;
        }

        // 只开 EntityDuang、不换模型的情况: 这里必须自己 cancel 再重渲染一次, 否则没有任何
        // 位置能插入 push/scale/pop —— EntityRenderDispatcher.render 的 PoseStack 是调用方
        // 传进来的, 我们既不能在 HEAD 直接改它(没有对应的 pop 点), 也不能等 TAIL(多个出口)。
        // 重入由 IS_RENDERING_FAKE 挡掉, 和上面两个分支同一套路。
        float[] visualOnly = visualScaleOf(entity, partialTick);
        if (visualOnly[0] != 1.0f || visualOnly[1] != 1.0f) {
            ci.cancel();

            IS_RENDERING_FAKE.set(true);
            try {
                poseStack.pushPose();
                applyVisualScale(poseStack, entity, partialTick, x, y, z);
                dispatcher.render(entity, x, y, z, yaw, partialTick, poseStack, buffer, light);
                poseStack.popPose();
            } catch (Exception ignored) {
            } finally {
                IS_RENDERING_FAKE.set(false);
            }
        }
    }

    /** 取当前该用的视觉缩放; 模块没开或不需要缩放时返回单位值。 */
    private static float[] visualScaleOf(Entity entity, float partialTick) {
        if (EntityDuang.INSTANCE == null || !EntityDuang.INSTANCE.isEnabled()) {
            return UNIT_SCALE;
        }
        return EntityDuang.INSTANCE.renderScale(entity, partialTick);
    }

    /** 以脚底为锚点套一层缩放: 变高时向上长, 压扁时贴地, 不会整体浮起来或陷进地里。 */
    private static void applyVisualScale(com.mojang.blaze3d.vertex.PoseStack poseStack, Entity entity,
                                         float partialTick, double x, double y, double z) {
        float[] scale = visualScaleOf(entity, partialTick);
        if (scale[0] == 1.0f && scale[1] == 1.0f) {
            return;
        }
        poseStack.translate(x, y, z);
        poseStack.scale(scale[0], scale[1], scale[0]);
        poseStack.translate(-x, -y, -z);
    }

    private static final float[] UNIT_SCALE = new float[]{1.0f, 1.0f};

    private static final float[] NO_HEAD_SAVE = new float[0];

    private static float applyFakeAntiAim(
            FakeAntiAim faa, Entity rendered,
            com.mojang.blaze3d.vertex.PoseStack poseStack,
            double x, double y, double z, float time, int tick, float baseYaw, float[] savedHead) {

        float yawAngle = faa.entityYawAngle(time, tick);
        float pitchAngle = faa.entityPitchAngle(time, tick);

        if (yawAngle != 0.0f || pitchAngle != 0.0f) {
            poseStack.translate(x, y, z);
            if (yawAngle != 0.0f) poseStack.mulPose(Axis.YP.rotationDegrees(yawAngle));
            if (pitchAngle != 0.0f) poseStack.mulPose(Axis.XP.rotationDegrees(pitchAngle));
            poseStack.translate(-x, -y, -z);
        }

        savedHead[0] = Float.NaN;
        boolean isRealPlayer = rendered == Minecraft.getInstance().player;
        boolean headYaw = !isRealPlayer && faa.headYawActive();
        boolean headPitch = !isRealPlayer && faa.headPitchActive();
        if ((headYaw || headPitch) && rendered instanceof LivingEntity le) {
            savedHead[0] = le.yHeadRot;
            savedHead[1] = le.yHeadRotO;
            savedHead[2] = le.getXRot();
            savedHead[3] = le.xRotO;
            if (headYaw) {
                float a = faa.headYawOffset(time, tick);
                le.yHeadRot += a;
                le.yHeadRotO += a;
            }
            if (headPitch) {
                float a = faa.headPitchOffset(time, tick);
                le.setXRot(a);
                le.xRotO = a;
            }
        }

        return baseYaw + yawAngle;
    }

    private static void restoreHead(Entity rendered, float[] savedHead) {
        if (savedHead.length == 4 && !Float.isNaN(savedHead[0]) && rendered instanceof LivingEntity le) {
            le.yHeadRot = savedHead[0];
            le.yHeadRotO = savedHead[1];
            le.setXRot(savedHead[2]);
            le.xRotO = savedHead[3];
        }
    }
}
