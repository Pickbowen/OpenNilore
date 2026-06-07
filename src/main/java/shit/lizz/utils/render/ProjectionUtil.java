package shit.lizz.utils.render;

import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import lombok.Generated;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import shit.lizz.ClientBase;
import shit.lizz.utils.math.Vector2f;
import shit.lizz.utils.misc.ReflectionUtil;

public final class ProjectionUtil
extends ClientBase {
    private static final Matrix4f modelViewMatrix = new Matrix4f();
    private static final Matrix4f projectionMatrix = new Matrix4f();
    private static final Vector4f tempVec4a = new Vector4f();
    private static final Vector4f tempVec4b = new Vector4f();
    private static final FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(16);
    private static final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private static final Vector3f tempVec3 = new Vector3f();
    private static final Quaternionf tempQuat = new Quaternionf();

    public static void updateMatrices() {
        floatBuffer.clear();
        RenderSystem.getProjectionMatrix().get(floatBuffer);
        projectionMatrix.set(floatBuffer);
        floatBuffer.clear();
        RenderSystem.getModelViewMatrix().get(floatBuffer);
        modelViewMatrix.set(floatBuffer);
        viewport.clear();
        GL11.glGetIntegerv(2978, viewport);
        tempVec4a.set((float)viewport.get(0), (float)viewport.get(1), (float)viewport.get(2), (float)viewport.get(3));
    }

    public static Vector2f project(double worldX, double worldY, double worldZ, float partialTicks) {
        Entity cameraEntity;
        Vec3 cameraPos = mc.getEntityRenderDispatcher().camera.getPosition();
        Quaternionf cameraRotation = new Quaternionf(mc.getEntityRenderDispatcher().cameraOrientation());
        cameraRotation.conjugate();
        Vector3f relativePos = new Vector3f((float)(cameraPos.x - worldX), (float)(cameraPos.y - worldY), (float)(cameraPos.z - worldZ));
        relativePos.rotate(cameraRotation);
        if (mc.options.bobView().get() && (cameraEntity = mc.getCameraEntity()) instanceof Player) {
            Player player = (Player)cameraEntity;
            ProjectionUtil.applyBobbing(player, relativePos, partialTicks);
        }
        double fov = 1.2f;
        try {
            Method method = mc.gameRenderer.getClass().getDeclaredMethod(ReflectionUtil.getMappedMethodName(mc.gameRenderer.getClass(), "getFov", "(Lnet/minecraft/client/Camera;FZ)D"), Camera.class, Float.TYPE, Boolean.TYPE);
            method.setAccessible(true);
            fov = (Double)method.invoke(mc.gameRenderer, new Object[]{mc.getEntityRenderDispatcher().camera, partialTicks, true});
        } catch (Exception exception) {
            exception.printStackTrace();
        }
        return ProjectionUtil.projectInternal(relativePos, fov);
    }

    private static void applyBobbing(Player player, Vector3f relativePos, float partialTicks) {
        float walkDist = player.walkDist;
        float walkDelta = walkDist - player.walkDistO;
        float walkProgress = -(walkDist + walkDelta * partialTicks);
        float bobAmount = Mth.lerp(partialTicks, player.oBob, player.bob);
        Quaternionf pitchRotation = new Quaternionf().rotationX(Math.abs(Mth.cos(walkProgress * (float)Math.PI - 0.2f) * bobAmount) * 5.0f * ((float)Math.PI / 180));
        pitchRotation.conjugate();
        relativePos.rotate(pitchRotation);
        Quaternionf rollRotation = new Quaternionf().rotationZ(Mth.sin(walkProgress * (float)Math.PI) * bobAmount * 3.0f * ((float)Math.PI / 180));
        rollRotation.conjugate();
        relativePos.rotate(rollRotation);
        Vector3f bobOffset = new Vector3f(Mth.sin(walkProgress * (float)Math.PI) * bobAmount * 0.5f, -Math.abs(Mth.cos(walkProgress * (float)Math.PI) * bobAmount), 0.0f);
        bobOffset.y = -bobOffset.y;
        relativePos.add(bobOffset);
    }

    private static Vector2f projectInternal(Vector3f relativePos, double fov) {
        float halfHeight = (float)mc.getWindow().getGuiScaledHeight() / 2.0f;
        float scale = halfHeight / (relativePos.z() * (float)Math.tan(Math.toRadians(fov / 2.0)));
        if (relativePos.z() < 0.0f) {
            return new Vector2f(-relativePos.x() * scale + (float)mc.getWindow().getGuiScaledWidth() / 2.0f, (float)mc.getWindow().getGuiScaledHeight() / 2.0f - relativePos.y() * scale);
        }
        return new Vector2f(Float.MAX_VALUE, Float.MAX_VALUE);
    }

    public static Vector2f project(double worldX, double worldY, double worldZ) {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        tempVec3.set((float)(worldX - cameraPos.x), (float)(worldY - cameraPos.y), (float)(worldZ - cameraPos.z));
        tempQuat.set(camera.rotation()).conjugate();
        tempVec3.rotate(tempQuat);
        ProjectionUtil.tempVec3.x = -ProjectionUtil.tempVec3.x;
        tempVec4b.set(ProjectionUtil.tempVec3.x, ProjectionUtil.tempVec3.y, -ProjectionUtil.tempVec3.z, 1.0f);
        projectionMatrix.transform(tempVec4b);
        if (ProjectionUtil.tempVec4b.w <= 0.0f) {
            return null;
        }
        float ndcX = ProjectionUtil.tempVec4b.x / ProjectionUtil.tempVec4b.w;
        float ndcY = ProjectionUtil.tempVec4b.y / ProjectionUtil.tempVec4b.w;
        if (Float.isNaN(ndcX) || Float.isNaN(ndcY) || ndcX < -1.2f || ndcX > 1.2f || ndcY < -1.2f || ndcY > 1.2f) {
            return null;
        }
        float screenX = tempVec4a.x() + (1.0f + ndcX) * tempVec4a.z() / 2.0f;
        float screenY = tempVec4a.y() + (1.0f - ndcY) * tempVec4a.w() / 2.0f;
        double guiScale = mc.getWindow().getGuiScale();
        if (guiScale == 0.0) {
            guiScale = 1.0;
        }
        screenX = (float)((double)screenX / guiScale);
        screenY = (float)((double)screenY / guiScale);
        return new Vector2f(screenX, screenY);
    }

    @Generated
    private ProjectionUtil() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
