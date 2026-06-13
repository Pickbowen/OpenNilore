package shit.lizz.modules.impl.misc.ai;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import shit.lizz.ClientBase;
import shit.lizz.modules.impl.movement.Scaffold;
import shit.lizz.utils.render.RenderUtil;

import java.util.ArrayList;
import java.util.List;

public class AIRender extends ClientBase {

    public static void render(Blackboard bb, PoseStack poseStack) {
        if (mc.player == null || bb == null) return;

        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);

        renderPathLine(bb, poseStack);

        if (bb.renderTarget != null) {
            renderBox(bb.renderTarget, poseStack, 0.2f, 0.8f, 1.0f);
        }
        if (bb.renderBridgeTarget != null) {
            renderBox(bb.renderBridgeTarget, poseStack, 1.0f, 0.8f, 0.0f);
        }
        if (bb.nearestChest != null && bb.nearestChestDist <= 8) {
            renderBox(bb.nearestChest, poseStack, 1.0f, 0.6f, 0.0f);
        }

        poseStack.popPose();
    }

    private static void renderPathLine(Blackboard bb, PoseStack poseStack) {
        // Collect path points (block centers at Y+0.5, like Baritone's offset)
        List<BlockPos> positions = new ArrayList<>();

        if (bb.renderPath != null && !bb.renderPath.isEmpty()) {
            positions.addAll(bb.renderPath);
        }

        // If no path, draw a line to the target
        if (positions.isEmpty() && bb.renderTarget != null) {
            positions.add(mc.player.blockPosition());
            positions.add(bb.renderTarget);
        } else if (positions.isEmpty() && bb.renderBridgeTarget != null) {
            positions.add(mc.player.blockPosition());
            positions.add(bb.renderBridgeTarget);
        }

        if (positions.size() < 2) return;

        // Color: cyan for walking, yellow for bridging
        float r = 0.2f, g = 0.9f, b = 1.0f;
        if (Scaffold.INSTANCE != null && Scaffold.INSTANCE.isEnabled()) {
            r = 1.0f; g = 0.9f; b = 0.2f;
        }

        // Baritone-style rendering setup
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
        RenderSystem.lineWidth(3.0f);
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();

        Matrix4f matrix = poseStack.last().pose();
        org.joml.Matrix3f normalMat = poseStack.last().normal();
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);

        double offset = 0.5; // block center

        for (int i = 0; i < positions.size() - 1; i++) {
            BlockPos start = positions.get(i);
            BlockPos end = positions.get(i + 1);

            // Coalesce collinear segments (like Baritone)
            int dirX = end.getX() - start.getX();
            int dirY = end.getY() - start.getY();
            int dirZ = end.getZ() - start.getZ();
            int next = i + 1;
            while (next + 1 < positions.size()) {
                BlockPos nextEnd = positions.get(next + 1);
                if (nextEnd.getX() - end.getX() == dirX &&
                    nextEnd.getY() - end.getY() == dirY &&
                    nextEnd.getZ() - end.getZ() == dirZ) {
                    end = nextEnd;
                    next++;
                } else {
                    break;
                }
            }
            i = next - 1; // skip coalesced (loop will i++)

            // Baritone ribbon: 4 lines per segment
            // 1. Main horizontal line (at block bottom + offset)
            emitLine(builder, matrix, normalMat, r, g, b, 0.9f,
                    start.getX() + offset, start.getY() + offset, start.getZ() + offset,
                    end.getX() + offset, end.getY() + offset, end.getZ() + offset);
            // 2. Vertical connector at end
            double extraOffset = offset + 0.03;
            emitLine(builder, matrix, normalMat, r, g, b, 0.9f,
                    end.getX() + offset, end.getY() + offset, end.getZ() + offset,
                    end.getX() + offset, end.getY() + extraOffset, end.getZ() + offset);
            // 3. Top horizontal line (slightly above)
            emitLine(builder, matrix, normalMat, r, g, b, 0.9f,
                    end.getX() + offset, end.getY() + extraOffset, end.getZ() + offset,
                    start.getX() + offset, start.getY() + extraOffset, start.getZ() + offset);
            // 4. Vertical connector at start
            emitLine(builder, matrix, normalMat, r, g, b, 0.9f,
                    start.getX() + offset, start.getY() + extraOffset, start.getZ() + offset,
                    start.getX() + offset, start.getY() + offset, start.getZ() + offset);
        }

        Tesselator.getInstance().end();

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    private static void emitLine(BufferBuilder builder, Matrix4f matrix, org.joml.Matrix3f normalMat,
                                  float r, float g, float b, float a,
                                  double x1, double y1, double z1,
                                  double x2, double y2, double z2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float nx = len > 0 ? (float) (dx / len) : 0f;
        float ny = len > 0 ? (float) (dy / len) : 0f;
        float nz = len > 0 ? (float) (dz / len) : 0f;

        builder.vertex(matrix, (float) x1, (float) y1, (float) z1)
                .color(r, g, b, a).normal(normalMat, nx, ny, nz).endVertex();
        builder.vertex(matrix, (float) x2, (float) y2, (float) z2)
                .color(r, g, b, a).normal(normalMat, nx, ny, nz).endVertex();
    }

    private static void renderBox(BlockPos pos, PoseStack poseStack, float r, float g, float b) {
        AABB box = new AABB(pos);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);

        RenderSystem.setShaderColor(r, g, b, 0.2f);
        RenderUtil.drawSolidBox(box, poseStack);

        RenderSystem.setShaderColor(r, g, b, 0.7f);
        RenderUtil.drawOutlineBox(box, poseStack);

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
    }
}
