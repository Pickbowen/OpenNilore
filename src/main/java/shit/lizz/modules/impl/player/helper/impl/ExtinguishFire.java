package shit.lizz.modules.impl.player.helper.impl;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import shit.lizz.event.impl.MotionEvent;
import shit.lizz.event.impl.RenderEvent;
import shit.lizz.modules.impl.player.Helper;
import shit.lizz.modules.impl.player.helper.HelperBase;
import shit.lizz.utils.game.RotationUtil;
import shit.lizz.utils.render.RenderUtil;
import shit.lizz.utils.rotation.Rotation;

public class ExtinguishFire
extends HelperBase {
    public enum State { NONE, FIRE }

    public Rotation targetRotation;
    private BlockPos firePos;
    private Direction hitDirection = Direction.UP;
    public ExtinguishFire.State currentState = ExtinguishFire.State.NONE;
    private boolean isAiming = false;
    private int aimDelay = 0;
    private static final String NAME = "Extinguish Fire";

    public ExtinguishFire() {
        super(NAME);
    }

    @Override
    public void onEnable() {
        this.reset();
    }

    @Override
    public void onDisable() {
        this.reset();
    }

    private void reset() {
        this.firePos = null;
        this.hitDirection = Direction.UP;
        this.currentState = ExtinguishFire.State.NONE;
        this.targetRotation = null;
    }

    @Override
    public void onMotion(MotionEvent motionEvent) {
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.player.isUsingItem()) {
            return;
        }
        if (motionEvent.isPost()) {
            if (this.currentState == ExtinguishFire.State.FIRE) {
                if (this.firePos == null || !mc.level.getBlockState(this.firePos).is(Blocks.FIRE) || mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(this.firePos)) > 25.0) {
                    this.reset();
                } else {
                    this.targetRotation = RotationUtil.exactRotation(mc.player.getEyePosition(), Vec3.atCenterOf(this.firePos));
                    if (!this.isAiming) {
                        this.isAiming = true;
                        this.aimDelay = 2;
                    }
                }
            } else if (this.currentState == ExtinguishFire.State.NONE) {
                this.findFirePos();
                if (this.firePos != null) {
                    this.currentState = ExtinguishFire.State.FIRE;
                    this.targetRotation = RotationUtil.exactRotation(mc.player.getEyePosition(), Vec3.atCenterOf(this.firePos));
                    this.isAiming = true;
                    this.aimDelay = 2;
                }
            }
            if (this.isAiming && this.currentState == ExtinguishFire.State.FIRE && this.firePos != null) {
                if (this.aimDelay > 0) {
                    --this.aimDelay;
                    return;
                }
                if (Helper.isRotationNearTarget()) {
                    return;
                }
                mc.gameMode.startDestroyBlock(this.firePos, Direction.UP);
                mc.player.swing(InteractionHand.MAIN_HAND);
                this.isAiming = false;
            }
        }
    }

    @Override
    public void onRender(RenderEvent renderEvent) {
        if (this.currentState != ExtinguishFire.State.FIRE || this.firePos == null || mc.gameRenderer == null) {
            return;
        }
        Color color = new Color(255, 0, 0);
        PoseStack poseStack = renderEvent.poseStack();
        Vec3 vec3 = mc.gameRenderer.getMainCamera().getPosition();
        poseStack.pushPose();
        poseStack.translate(-vec3.x, -vec3.y, -vec3.z);
        AABB aABB = new AABB(this.firePos);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShaderColor((float)color.getRed() / 255.0f, (float)color.getGreen() / 255.0f, (float)color.getBlue() / 255.0f, 0.25f);
        RenderUtil.drawSolidBox(aABB, poseStack);
        RenderSystem.setShaderColor((float)color.getRed() / 255.0f, (float)color.getGreen() / 255.0f, (float)color.getBlue() / 255.0f, 0.75f);
        RenderUtil.drawOutlineBox(aABB, poseStack);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        poseStack.popPose();
    }

    private void findFirePos() {
        if (mc.player == null || mc.level == null) {
            this.firePos = null;
            return;
        }
        BlockPos blockPos2 = mc.player.blockPosition();
        ArrayList<BlockPos> arrayList = new ArrayList<>();
        Vec3 vec3 = mc.player.getEyePosition();
        for (int i = -8; i <= 8; ++i) {
            for (int j = -2; j <= 2; ++j) {
                for (int k = -8; k <= 8; ++k) {
                    BlockPos blockPos3 = blockPos2.offset(i, j, k);
                    if (!mc.level.getBlockState(blockPos3).is(Blocks.FIRE) || !(Vec3.atCenterOf(blockPos3).distanceToSqr(vec3) <= 25.0) || !Helper.isPositionInFov(Vec3.atCenterOf(blockPos3))) continue;
                    arrayList.add(blockPos3);
                }
            }
        }
        this.firePos = (BlockPos)arrayList.stream().min(Comparator.comparingDouble(blockPos -> Vec3.atCenterOf((Vec3i)blockPos).distanceToSqr(vec3))).orElse(null);
    }

    @Override
    public boolean isActive() {
        return this.targetRotation != null;
    }

    @Override
    public Rotation getTargetRotation() {
        return this.targetRotation;
    }
}