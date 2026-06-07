package shit.lizz.utils.game;

import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector4d;
import org.joml.Vector4f;
import shit.lizz.ClientBase;

public class EntityUtil
extends ClientBase {
    public static boolean isVisible(Entity entity) {
        Frustum frustum = mc.levelRenderer.getFrustum();
        return frustum.isVisible(entity.getBoundingBox());
    }

    public static Vec3 getInterpolatedPos(Entity entity, float partialTicks) {
        double x = Mth.lerp(partialTicks, entity.xOld, entity.getX());
        double y = Mth.lerp(partialTicks, entity.yOld, entity.getY());
        double z = Mth.lerp(partialTicks, entity.zOld, entity.getZ());
        return new Vec3(x, y, z);
    }

    public static AABB getInterpolatedAABB(Entity entity, float partialTicks) {
        Vec3 pos = EntityUtil.getInterpolatedPos(entity, partialTicks);
        double halfWidth = (double)entity.getBbWidth() / 2.0;
        return new AABB(pos.x - halfWidth, pos.y, pos.z - halfWidth, pos.x + halfWidth, pos.y + (double)entity.getBbHeight(), pos.z + halfWidth);
    }

    public static Vector4d getScreenBounds(AABB aABB, Matrix4f modelView, Matrix4f projection) {
        Matrix4f combined = new Matrix4f(projection).mul(modelView);
        int[] viewport = new int[]{0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight()};
        return EntityUtil.getScreenBoundsInternal(viewport, aABB, combined);
    }

    private static Vector4d getScreenBoundsInternal(int[] viewport, AABB aABB, Matrix4f mvpMatrix) {
        List<Vec3> corners = Arrays.asList(new Vec3(aABB.minX, aABB.minY, aABB.minZ), new Vec3(aABB.minX, aABB.maxY, aABB.minZ), new Vec3(aABB.maxX, aABB.minY, aABB.minZ), new Vec3(aABB.maxX, aABB.maxY, aABB.minZ), new Vec3(aABB.minX, aABB.minY, aABB.maxZ), new Vec3(aABB.minX, aABB.maxY, aABB.maxZ), new Vec3(aABB.maxX, aABB.minY, aABB.maxZ), new Vec3(aABB.maxX, aABB.maxY, aABB.maxZ));
        Vector4f projected = new Vector4f();
        Vector4d bounds = null;
        boolean anyVisible = false;
        for (Vec3 corner : corners) {
            Vector4f clipPos = new Vector4f((float)corner.x(), (float)corner.y(), (float)corner.z(), 1.0f);
            mvpMatrix.transform(clipPos);
            if (!(clipPos.w > 0.0f)) continue;
            mvpMatrix.project((float)corner.x(), (float)corner.y(), (float)corner.z(), viewport, projected);
            projected.y = (float)viewport[3] - projected.y;
            anyVisible = true;
            if (bounds == null) {
                bounds = new Vector4d(projected.x, projected.y, projected.x, projected.y);
                continue;
            }
            bounds.x = Math.min(projected.x, bounds.x);
            bounds.y = Math.min(projected.y, bounds.y);
            bounds.z = Math.max(projected.x, bounds.z);
            bounds.w = Math.max(projected.y, bounds.w);
        }
        return anyVisible ? bounds : null;
    }

    public static Vector3f getCameraRelativePos(Entity entity, float partialTicks) {
        if (mc == null || mc.gameRenderer == null || mc.gameRenderer.getMainCamera() == null) {
            return null;
        }
        Vec3 entityPos = EntityUtil.getInterpolatedPos(entity, partialTicks);
        double entityX = entityPos.x();
        double entityY = entityPos.y();
        double entityZ = entityPos.z();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        Quaternionf cameraRotation = camera.rotation();
        Quaternionf invRotation = new Quaternionf(cameraRotation).conjugate();
        Vector3f relativePos = new Vector3f((float)(entityX - cameraPos.x), (float)(entityY - cameraPos.y), (float)(entityZ - cameraPos.z));
        relativePos.rotate(invRotation);
        return relativePos;
    }
}
