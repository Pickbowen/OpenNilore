package shit.lizz.modules.impl.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Quaternionf;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.modules.impl.combat.KillAura;
import shit.lizz.settings.impl.ModeSetting;
import shit.lizz.settings.impl.NumberSetting;

public class OldHitting
extends Module {
    private final ModeSetting animationModeSetting = new ModeSetting("Animation", "Vanilla", "Leaked", "Slide").withDefault("Leaked");
    private final NumberSetting sizeSetting = new NumberSetting("Size", 1.0, 0.1, 3.0, 0.1);
    private final NumberSetting speedSetting = new NumberSetting("Speed", 1.0, 0.1, 5.0, 0.1);
    private final NumberSetting yOffsetSetting = new NumberSetting("Y-Offset", 0.0, -1.0, 1.0, 0.1);
    public static OldHitting INSTANCE;

    public OldHitting() {
        super("OldHitting", Category.RENDER);
        INSTANCE = this;
    }

    public boolean isKillAuraAttacking() {
        return KillAura.INSTANCE != null
                && KillAura.INSTANCE.isEnabled()
                && KillAura.INSTANCE.fakeAutoBlock.getValue()
                && KillAura.aimingTarget != null;
    }

    public static void applyTranslate(double tx, double ty, double tz, PoseStack poseStack) {
        poseStack.translate(tx, ty, tz);
    }

    public static void applyRotate(float angle, float ax, float ay, float az, PoseStack poseStack) {
        poseStack.mulPose(new Quaternionf().rotationAxis(angle * ((float)Math.PI / 180), ax, ay, az));
    }

    public static void applyScale(float sx, float sy, float sz, PoseStack poseStack) {
        poseStack.scale(sx, sy, sz);
    }

    public void applyHitAnimation(PoseStack poseStack, float progress, HumanoidArm humanoidArm, float equipProgress) {
        float scaledProgress = progress * this.speedSetting.getValue().floatValue();
        float size = this.sizeSetting.getValue().floatValue();
        poseStack.translate(0.0f, this.yOffsetSetting.getValue().floatValue(), 0.0f);
        if (this.animationModeSetting.getValue().equalsIgnoreCase("vanilla")) {
            int side = humanoidArm == HumanoidArm.RIGHT ? 1 : -1;
            OldHitting.applyTranslate((float)side * 0.56f, -0.52f + equipProgress * -0.6f, -0.72, poseStack);
            OldHitting.applyTranslate((float)side * -0.1414214f, 0.08f, 0.1414213925600052, poseStack);
            OldHitting.applyRotate(-102.25f, 1.0f, 0.0f, 0.0f, poseStack);
            OldHitting.applyRotate((float)side * 13.365f, 0.0f, 1.0f, 0.0f, poseStack);
            OldHitting.applyRotate((float)side * 78.05f, 0.0f, 0.0f, 1.0f, poseStack);
            double sinSquared = Math.sin((double)(scaledProgress * scaledProgress) * Math.PI);
            double sinSqrt = Math.sin(Math.sqrt(scaledProgress) * Math.PI);
            OldHitting.applyRotate((float)(sinSquared * -20.0), 0.0f, 1.0f, 0.0f, poseStack);
            OldHitting.applyRotate((float)(sinSqrt * -20.0), 0.0f, 0.0f, 1.0f, poseStack);
            OldHitting.applyRotate((float)(sinSqrt * -80.0), 1.0f, 0.0f, 0.0f, poseStack);
            OldHitting.applyScale(size, size, size, poseStack);
        }
        if (this.animationModeSetting.getValue().equalsIgnoreCase("leaked")) {
            this.setupLeakedAnim(poseStack, equipProgress, scaledProgress, size);
            this.setupLeakedArmPos(poseStack);
            float pulse = Mth.sin(Mth.sqrt(scaledProgress) * (float)Math.PI) / 8.0f;
            poseStack.translate(0.008, 0.24, 0.03);
            poseStack.translate(-0.16, -0.25, 0.0);
            poseStack.scale((float)(0.8 + (double)pulse) * size, (float)(0.8 + (double)pulse) * size, (float)(0.8 + (double)pulse) * size);
            OldHitting.applyRotate(-Mth.sin((float)((double)Mth.sqrt(scaledProgress) * Math.PI)) * 20.0f, 0.0f, 1.2f, -0.8f, poseStack);
            OldHitting.applyRotate(-Mth.sin((float)((double)Mth.sqrt(scaledProgress) * Math.PI)) * 30.0f, 1.0f, 0.0f, 0.0f, poseStack);
            poseStack.scale(2.4f * size, 2.4f * size, 2.4f * size);
            OldHitting.applyRotate(-38.4f, 0.0f, 1.0f, 0.0f, poseStack);
            OldHitting.applyScale(size, size, size, poseStack);
        }
        if (this.animationModeSetting.getValue().equalsIgnoreCase("slide")) {
            float slideSwing = Mth.sin(Mth.sqrt(scaledProgress) * (float)Math.PI);
            OldHitting.applyTranslate(0.648f, -0.55f, -0.7199999690055847, poseStack);
            OldHitting.applyTranslate(0.0, 0.0, 0.0, poseStack);
            OldHitting.applyRotate(77.0f, 0.0f, 1.0f, 0.0f, poseStack);
            OldHitting.applyRotate(-10.0f, 0.0f, 0.0f, 1.0f, poseStack);
            OldHitting.applyRotate(-80.0f, 1.0f, 0.0f, 0.0f, poseStack);
            OldHitting.applyRotate(-slideSwing * 20.0f, 1.0f, 0.0f, 0.0f, poseStack);
            OldHitting.applyScale(1.2f * size, 1.2f * size, 1.2f * size, poseStack);
            OldHitting.applyScale(size, size, size, poseStack);
        }
    }

    private void setupLeakedAnim(PoseStack poseStack, float equipProgress, float scaledProgress, float size) {
        poseStack.translate(0.56f, -0.52f, -0.71999997f);
        OldHitting.applyRotate(45.0f, 0.0f, 1.0f, 0.0f, poseStack);
        float sinSquared = Mth.sin(scaledProgress * scaledProgress * (float)Math.PI);
        float sinSqrt = Mth.sin(Mth.sqrt(scaledProgress) * (float)Math.PI);
        OldHitting.applyRotate(sinSquared * -20.0f, 0.0f, 1.0f, 0.0f, poseStack);
        OldHitting.applyRotate(sinSqrt * -20.0f, 0.0f, 0.0f, 1.0f, poseStack);
        OldHitting.applyRotate(sinSqrt * -80.0f, 1.0f, 0.0f, 0.0f, poseStack);
        poseStack.scale(0.4f * size, 0.4f * size, 0.4f * size);
    }

    private void setupLeakedArmPos(PoseStack poseStack) {
        poseStack.translate(-0.5f, 0.2f, 0.0f);
        OldHitting.applyRotate(30.0f, 0.0f, 1.0f, 0.0f, poseStack);
        OldHitting.applyRotate(-80.0f, 1.0f, 0.0f, 0.0f, poseStack);
        OldHitting.applyRotate(60.0f, 0.0f, 1.0f, 0.0f, poseStack);
    }
}