package shit.lizz.modules.impl.player;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import shit.lizz.event.impl.MotionEvent;
import shit.lizz.event.impl.RenderEvent;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.utils.animation.TickTimer;
import shit.lizz.utils.game.ItemUtil;
import shit.lizz.utils.game.PlayerUtil;
import shit.lizz.utils.game.RotationUtil;
import shit.lizz.utils.misc.ChatUtil;
import shit.lizz.utils.misc.PacketUtil;
import shit.lizz.utils.render.RenderUtil;
import shit.lizz.utils.rotation.Rotation;
import shit.lizz.event.EventTarget;

public class AntiWeb extends Module {
    public static AntiWeb INSTANCE;
    public static Rotation targetRotation;

    public enum Phase { IDLE, PLACING, RECYCLING }

    public static Phase currentPhase = Phase.IDLE;

    private final TickTimer webCheckTimer = new TickTimer();
    private final TickTimer placementTimer = new TickTimer();
    private final TickTimer pickupTimer = new TickTimer();
    private BlockPos webPos;
    private int savedHotbarSlot = -1;
    private int waterBucketSlot = -1;
    private boolean sentUsePacket = false;
    private BlockPos waterSourcePos = null;

    public AntiWeb() {
        super("AntiWeb", Category.PLAYER);
        INSTANCE = this;
    }

    public Phase getPhase() {
        return currentPhase;
    }

    @Override
    public void onEnable() {
        this.reset();
    }

    @Override
    public void onDisable() {
        targetRotation = null;
        if (mc != null && mc.options.keyUse.isDown()) {
            mc.options.keyUse.setDown(false);
        }
        if (this.savedHotbarSlot != -1 && mc.player != null) {
            mc.player.getInventory().selected = this.savedHotbarSlot;
            PlayerUtil.sendCarriedItem();
        }
        this.reset();
    }

    private boolean isInCobweb() {
        if (mc.player == null || mc.level == null) return false;
        AABB box = mc.player.getBoundingBox().inflate(0.1);
        for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); ++x) {
            for (int y = Mth.floor(box.minY); y <= Mth.floor(box.maxY); ++y) {
                for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); ++z) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (mc.level.getBlockState(pos).is(Blocks.COBWEB)) {
                        this.webPos = pos;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private BlockPos findNearestWaterSource(BlockPos origin) {
        if (mc.level == null) return null;
        if (mc.level.getBlockState(origin).is(Blocks.WATER)
                && mc.level.getFluidState(origin).getAmount() == 8) {
            return origin;
        }
        for (int dx = -1; dx <= 1; ++dx) {
            for (int dy = -1; dy <= 1; ++dy) {
                for (int dz = -1; dz <= 1; ++dz) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (mc.level.getBlockState(pos).is(Blocks.WATER)
                            && mc.level.getFluidState(pos).getAmount() == 8) {
                        return pos;
                    }
                }
            }
        }
        for (int dx = -1; dx <= 1; ++dx) {
            for (int dy = -1; dy <= 1; ++dy) {
                for (int dz = -1; dz <= 1; ++dz) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (mc.level.getBlockState(pos).is(Blocks.WATER)) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    @EventTarget
    public void onMotion(MotionEvent event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (!event.isPost()) return;

        if (currentPhase == Phase.PLACING && mc.level.getBlockState(this.webPos).is(Blocks.WATER)) {
            this.waterSourcePos = this.findNearestWaterSource(this.webPos);
            if (this.waterSourcePos != null) {
                Helper.markWaterPlaced(this.waterSourcePos);
                currentPhase = Phase.RECYCLING;
                this.sentUsePacket = false;
                this.placementTimer.reset();
                this.pickupTimer.reset();
            } else {
                ChatUtil.print("Could not find water source!");
                this.reset();
            }
        }

        switch (currentPhase) {
            case IDLE -> {
                if (mc.player.isInWater() || !this.webCheckTimer.hasPassed(5) || !this.isInCobweb()) return;
                this.waterBucketSlot = ItemUtil.getSlot(Items.WATER_BUCKET);
                if (this.waterBucketSlot == -1 || this.waterBucketSlot > 8) return;
                currentPhase = Phase.PLACING;
            }
            case PLACING -> {
                if (!this.isInCobweb()) {
                    this.reset();
                    return;
                }
                if (this.sentUsePacket) break;
                if (this.savedHotbarSlot == -1) {
                    this.savedHotbarSlot = mc.player.getInventory().selected;
                }
                mc.player.getInventory().selected = this.waterBucketSlot;
                PlayerUtil.sendCarriedItem();
                Vec3 eye = mc.player.getEyePosition();
                Vec3 target = Vec3.atCenterOf(this.webPos).add(0.0, 0.5, 0.0);
                targetRotation = RotationUtil.exactRotation(eye, target);
                PacketUtil.sendQueued(new ServerboundMovePlayerPacket.Rot(
                        targetRotation.getYaw(), targetRotation.getPitch(), mc.player.onGround()));
                PacketUtil.sendPredictive(n -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, n));
                this.sentUsePacket = true;
            }
            case RECYCLING -> {
                if (this.pickupTimer.hasPassed(20)) {
                    ChatUtil.print("§cPickup water timeout after 20 ticks, giving up!");
                    this.reset();
                    return;
                }
                if (mc.player.getMainHandItem().getItem() == Items.WATER_BUCKET) {
                    this.reset();
                    return;
                }
                if (this.waterSourcePos == null || !mc.level.getBlockState(this.waterSourcePos).is(Blocks.WATER)) {
                    ChatUtil.print("Failed to recycle water!");
                    this.reset();
                    return;
                }
                if (mc.player.getMainHandItem().getItem() != Items.BUCKET) {
                    mc.player.getInventory().selected = this.waterBucketSlot;
                    PlayerUtil.sendCarriedItem();
                    targetRotation = null;
                    return;
                }
                targetRotation = RotationUtil.rotationToBlock(this.waterSourcePos, 0.0f);
                if (this.sentUsePacket && this.placementTimer.hasPassed(10)) {
                    this.sentUsePacket = false;
                    this.placementTimer.reset();
                }
                if (this.sentUsePacket && !this.placementTimer.hasPassed(5)) break;
                ChatUtil.print("Trying to recycle water...");
                PacketUtil.sendPredictive(n -> new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, n));
                this.sentUsePacket = true;
                this.placementTimer.reset();
            }
        }
    }

    @EventTarget
    public void onRender(RenderEvent renderEvent) {
        if (currentPhase == Phase.IDLE || mc.gameRenderer == null) return;
        PoseStack poseStack = renderEvent.poseStack();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        if (this.webPos != null) {
            AABB box = new AABB(this.webPos);
            Color color = new Color(0, 150, 255);
            RenderSystem.setShaderColor(color.getRed() / 255.0f, color.getGreen() / 255.0f, color.getBlue() / 255.0f, 0.25f);
            RenderUtil.drawSolidBox(box, poseStack);
            RenderSystem.setShaderColor(color.getRed() / 255.0f, color.getGreen() / 255.0f, color.getBlue() / 255.0f, 0.75f);
            RenderUtil.drawOutlineBox(box, poseStack);
        }
        if (this.waterSourcePos != null && currentPhase == Phase.RECYCLING) {
            AABB box = new AABB(this.waterSourcePos);
            Color color = new Color(0, 255, 0);
            RenderSystem.setShaderColor(color.getRed() / 255.0f, color.getGreen() / 255.0f, color.getBlue() / 255.0f, 0.25f);
            RenderUtil.drawSolidBox(box, poseStack);
            RenderSystem.setShaderColor(color.getRed() / 255.0f, color.getGreen() / 255.0f, color.getBlue() / 255.0f, 0.75f);
            RenderUtil.drawOutlineBox(box, poseStack);
        }
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        poseStack.popPose();
    }

    private void reset() {
        if (mc.options.keyUse.isDown()) {
            mc.options.keyUse.setDown(false);
        }
        if (this.savedHotbarSlot != -1 && mc.player != null) {
            mc.player.getInventory().selected = this.savedHotbarSlot;
            PlayerUtil.sendCarriedItem();
            this.savedHotbarSlot = -1;
        }
        currentPhase = Phase.IDLE;
        targetRotation = null;
        this.webPos = null;
        this.waterSourcePos = null;
        this.sentUsePacket = false;
        this.webCheckTimer.reset();
        this.placementTimer.reset();
        this.pickupTimer.reset();
    }
}
