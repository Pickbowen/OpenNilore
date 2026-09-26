package client.nilore.modules.impl.render.esp;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import client.nilore.modules.impl.render.ESP;

/**
 * Skeleton ESP backend.
 *
 * <p>Joints are collected while the entity is drawn: the patch on
 * {@link LivingEntityRenderer#render} reads the model matrix right after
 * {@code EntityModel.renderToBuffer}, and each joint is placed by running the vanilla
 * {@link ModelPart#translateAndRotate} on a scratch stack and stepping to an anchor
 * inside that part. Joints are kept in <b>model space</b> and the model matrix is
 * stored alongside them, then handed to the vertices at draw time — so the skeleton is
 * transformed by exactly the same matrix the model was, whatever that matrix happens to
 * contain. (Do <em>not</em> bake the model matrix into the joint coordinates and then
 * draw through {@code RenderEvent}'s pose stack: that stack already carries the view
 * rotation, so the rotation lands twice and the skeleton ends up at the right distance
 * from the camera but the wrong direction.)</p>
 *
 * <p>Anchors are mandatory because {@code HumanoidModel} gives {@code head} and
 * {@code body} the <em>same</em> {@code PartPose} origin — both sit at the neck and the
 * torso cube grows downward from there — so bare part origins would collapse the spine
 * into one point. The offsets below are the cube edges declared in
 * {@code HumanoidModel.createMesh}, in model pixels, where +Y points down.</p>
 *
 * <p>Hips come from the leg parts rather than from {@code body + (0,12,0)}: legs hang off
 * the model root, so they do not follow the torso lean that crouching/swimming applies to
 * {@code body}. Anchoring the hips to the torso made the lower body slide away from the
 * legs whenever the target leaned forward.</p>
 */
public final class SkeletonCache {

    // body 立方体 (-4,0,-2)-(4,12,4)，原点在脖子：底边 y=12、肩线 y=2。
    private static final int CHEST = 0;
    // head 立方体 (-4,-8,-4)-(4,8,8)，原点在脖子，头中心在 y=-4。
    private static final int HEAD = 1;
    // 手臂立方体 (-3,-2,-2)-(1,10,4)：原点即肩关节，末端 y=10 是手。
    private static final int RIGHT_SHOULDER = 2;
    private static final int RIGHT_HAND = 3;
    private static final int LEFT_SHOULDER = 4;
    private static final int LEFT_HAND = 5;
    // 腿立方体 (-2,0,-2)-(2,12,4)：原点即髋关节（在 root 坐标系里，不随躯干倾斜），y=12 是脚底。
    private static final int RIGHT_HIP = 6;
    private static final int RIGHT_FOOT = 7;
    private static final int LEFT_HIP = 8;
    private static final int LEFT_FOOT = 9;
    // 两髋中点，脊柱从这里往上走。
    private static final int PELVIS = 10;
    private static final int POINT_COUNT = 11;

    private static final int[][] EDGES = {
            {LEFT_HIP, RIGHT_HIP},
            {PELVIS, CHEST},
            {CHEST, HEAD},
            {CHEST, RIGHT_SHOULDER}, {RIGHT_SHOULDER, RIGHT_HAND},
            {CHEST, LEFT_SHOULDER}, {LEFT_SHOULDER, LEFT_HAND},
            {RIGHT_HIP, RIGHT_FOOT},
            {LEFT_HIP, LEFT_FOOT}
    };

    /** 关节用模型空间坐标，矩阵是渲染这个模型时用的那个。 */
    private record Skeleton(float[] points, Matrix4f modelMatrix) {
    }

    private static final Map<Entity, Skeleton> SKELETONS = new HashMap<>();
    private static final PoseStack SCRATCH = new PoseStack();

    private SkeletonCache() {
    }

    /** Called from LivingEntityRendererPatch for every rendered living entity. */
    public static void capture(LivingEntityRenderer<?, ?> renderer, LivingEntity entity, PoseStack poseStack) {
        if (ESP.INSTANCE == null || !ESP.INSTANCE.shouldTrackSkeleton(entity)) return;
        if (!(renderer.getModel() instanceof HumanoidModel<?> model)) return;
        // Babies take the other branch of AgeableListModel.renderToBuffer, which wraps every
        // part in its own scale/translate. Those fields are private, so skip babies instead of
        // guessing — an adult-sized skeleton on a baby looks worse than no skeleton.
        if (model.young) return;
        float[] points = new float[POINT_COUNT * 3];
        joint(points, CHEST, model.body, 0.0f, 2.0f, 0.0f);
        joint(points, HEAD, model.head, 0.0f, -4.0f, 0.0f);
        joint(points, RIGHT_SHOULDER, model.rightArm, 0.0f, 0.0f, 0.0f);
        joint(points, RIGHT_HAND, model.rightArm, 0.0f, 10.0f, 0.0f);
        joint(points, LEFT_SHOULDER, model.leftArm, 0.0f, 0.0f, 0.0f);
        joint(points, LEFT_HAND, model.leftArm, 0.0f, 10.0f, 0.0f);
        joint(points, RIGHT_HIP, model.rightLeg, 0.0f, 0.0f, 0.0f);
        joint(points, LEFT_HIP, model.leftLeg, 0.0f, 0.0f, 0.0f);
        joint(points, RIGHT_FOOT, model.rightLeg, 0.0f, 12.0f, 0.0f);
        joint(points, LEFT_FOOT, model.leftLeg, 0.0f, 12.0f, 0.0f);
        for (int i = 0; i < 3; i++) {
            points[PELVIS * 3 + i] = (points[RIGHT_HIP * 3 + i] + points[LEFT_HIP * 3 + i]) * 0.5f;
        }
        // Copy: the stack keeps being mutated for the next entity right after this returns.
        SKELETONS.put(entity, new Skeleton(points, new Matrix4f(poseStack.last().pose())));
    }

    /** Draws every captured skeleton and drops the frame's data. */
    public static void render() {
        if (SKELETONS.isEmpty()) return;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        for (Map.Entry<Entity, Skeleton> entry : SKELETONS.entrySet()) {
            Skeleton skeleton = entry.getValue();
            float[] points = skeleton.points();
            Matrix4f matrix = skeleton.modelMatrix();
            // 恒为纯白: 不跟随玩家配色/队伍色, 所有实体同一个颜色。
            for (int[] edge : EDGES) {
                vertex(builder, matrix, points, edge[0]);
                vertex(builder, matrix, points, edge[1]);
            }
        }
        BufferUploader.drawWithShader(builder.end());
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        SKELETONS.clear();
    }

    public static void clear() {
        SKELETONS.clear();
    }

    private static void vertex(BufferBuilder builder, Matrix4f matrix, float[] points, int joint) {
        builder.vertex(matrix, points[joint * 3], points[joint * 3 + 1], points[joint * 3 + 2])
                .color(1.0f, 1.0f, 1.0f, 1.0f)
                .endVertex();
    }

    /**
     * Model-space position of an anchor inside {@code part}: the vanilla part transform, then a
     * step of {@code (x,y,z)} model pixels along the part's own (already rotated) axes.
     */
    private static void joint(float[] points, int index, ModelPart part, float x, float y, float z) {
        SCRATCH.last().pose().identity();
        part.translateAndRotate(SCRATCH);
        SCRATCH.translate(x / 16.0f, y / 16.0f, z / 16.0f);
        Matrix4f matrix = SCRATCH.last().pose();
        points[index * 3] = matrix.m30();
        points[index * 3 + 1] = matrix.m31();
        points[index * 3 + 2] = matrix.m32();
    }
}
