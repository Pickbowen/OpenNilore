package client.nilore.modules.impl.movement;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import client.nilore.ClientBase;
import client.nilore.NiloreClient;
import client.nilore.event.impl.JumpEvent;
import client.nilore.event.impl.MotionEvent;
import client.nilore.event.impl.PacketEvent;
import client.nilore.event.impl.PreMotionEvent;
import client.nilore.event.impl.Render2DEvent;
import client.nilore.event.impl.RenderEvent;
import client.nilore.event.impl.StrafeEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.event.impl.UpdateHeldItemEvent;
import client.nilore.hud.ModuleListHud;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.render.FontPresets;
import client.nilore.render.FontRenderer;
import client.nilore.render.GlHelper;
import client.nilore.render.Paint;
import client.nilore.render.Rectangle;
import client.nilore.render.Renderer;
import client.nilore.render.RoundedRectangle;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.game.BlockUtil;
import client.nilore.utils.game.MotionSimulator;
import client.nilore.utils.game.MovementUtil;
import client.nilore.utils.game.RayTraceUtil;
import client.nilore.utils.game.RotationUtil;
import client.nilore.utils.misc.PacketUtil;
import client.nilore.utils.render.RenderUtil;

import client.nilore.utils.rotation.Rotation;
import client.nilore.utils.rotation.RotationHandler;
import client.nilore.utils.rotation.RotationSmoother;
import client.nilore.event.EventTarget;

public class Scaffold extends Module {
    public static Scaffold INSTANCE;

    public final ModeSetting mode = new ModeSetting("Mode", "Normal", "Telly Bridge", "Keep Y").withDefault("Normal");
    public final NumberSetting tellyAirTicks = new NumberSetting("AirTicks", 1, 0, 3, 0.1, () -> this.mode.is("Telly Bridge"));
    public final NumberSetting tellyPlaceDelay = new NumberSetting("PlaceDelay", 1, 0, 20, 1, () -> this.mode.is("Telly Bridge"));
    public final BooleanSetting eagle = new BooleanSetting("Eagle", false, () -> this.mode.is("Normal"));
    public final BooleanSetting renderItemSpoof = new BooleanSetting("Render Item Spoof", true);
    public final BooleanSetting clutch = new BooleanSetting("Clutch", false);
    public final NumberSetting clutchDepth = new NumberSetting("Clutch Depth", 4, 2, 5, 1, this.clutch::getValue);
    public final ModeSetting swingMode = new ModeSetting("Swing", "Both", "Server").withDefault("Both");
    public final BooleanSetting blockCounter = new BooleanSetting("Block Counter", true);
    public final ModeSetting blockCounterStyle = new ModeSetting("Block Counter Style", "Amunix", "Modern", "Naven", "Nitro").withDefault("Nitro");
    public final NumberSetting nitroOffsetY = new NumberSetting("Nitro Offset", 90, 0, 220, 5, () -> this.blockCounterStyle.is("Nitro"));
    public final BooleanSetting onTickRot = new BooleanSetting("OnTickRot", false);
    public final NumberSetting rotationSpeed = new NumberSetting("Rotation Speed", 153.9, 0, 360, 5, () -> !this.syncRotSpeed.getValue());
    public final NumberSetting jitter = new NumberSetting("Jitter", 2.5, 0.0, 5.0, 0.1);
    public final BooleanSetting syncRotSpeed = new BooleanSetting("Sync RotSpeed", false);
    public final NumberSetting turnSpeed = new NumberSetting("Turn Speed", 73.4, 0, 360, 5, this.syncRotSpeed::getValue);
    public final NumberSetting returnSpeed = new NumberSetting("Return Speed", 101.8, 0, 360, 5, this.syncRotSpeed::getValue);
    public final ModeSetting switchMode = new ModeSetting("Switch Mode", "Normal", "Hotbar", "Full").withDefault("Hotbar");
    public final BooleanSetting bpsLimit = new BooleanSetting("Limit BPS", true);
    public final NumberSetting bps = new NumberSetting("BPS", 3.5, 2.0, 10.0, 0.1, this.bpsLimit::getValue);
    public final BooleanSetting mark = new BooleanSetting("Mark", true);
    public final NumberSetting markRed = new NumberSetting("Mark Red", 255, 0, 255, 1, () -> this.mark.getValue());
    public final NumberSetting markGreen = new NumberSetting("Mark Green", 55, 0, 255, 1, () -> this.mark.getValue());
    public final NumberSetting markBlue = new NumberSetting("Mark Blue", 100, 0, 255, 1, () -> this.mark.getValue());
    public final NumberSetting markAlpha = new NumberSetting("Mark Alpha", 130, 0, 255, 1, () -> this.mark.getValue());

    public Rotation rots = new Rotation();
    public Rotation lastRots = new Rotation();
    /** 转头平滑(惯性+波动), 与 KillAura 共用同一实现。 */
    private final RotationSmoother rotSmoother = new RotationSmoother(new Random());
    /** 抖动独立随机源: 不能和 smoother 共用, 否则平滑器的白噪声会与抖动相关。 */
    private final Random jitterRandom = new Random();
    public int targetYLevel = -1;
    public int velocityDelay = 0;

    private int oldSlot;
    private int altSlot = -1;
    private boolean slotActive;
    private int slotActiveTick;
    private int slotTicks;
    private PlacementTarget currentPlacement;
    private int airTicks = 0;
    private int rotationDelay = 0;
    private final CopyOnWriteArrayList<CopyOnWriteArrayList<Packet<?>>> packetBatches = new CopyOnWriteArrayList<>();
    private boolean canBuildNow;
    private BlockPos lastC05Position;
    private int tellyPlaceDelayTimer;

    private static final FontRenderer shelfLabelFont = FontPresets.axiformaBold(12);
    private static final FontRenderer shelfBpsFont = FontPresets.axiformaBold(13);
    private static final FontRenderer shelfBlocksFont = FontPresets.axiformaBold(13);
    private final client.nilore.utils.animation.SpringAnimation shelfProgress =
            new client.nilore.utils.animation.SpringAnimation(300.0f, 1.0f, 25.0f, 1.0f);
    private int shelfInitialBlocks;
    private boolean shelfHudInitialized;
    private long hudLastFrameTime;


    public Scaffold() {
        super("Scaffold", Category.MOVEMENT);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        if (mc.player != null) {
            this.oldSlot = mc.player.getInventory().selected;
            this.altSlot = this.oldSlot;
            this.slotActive = false;
            this.slotActiveTick = 0;
            this.slotTicks = 0;
            this.rots.setYawPitch(mc.player.getYRot() - 180.0f, mc.player.getXRot());
            this.lastRots.setYawPitch(mc.player.yRotO - 180.0f, mc.player.xRotO);
            this.currentPlacement = null;
            this.targetYLevel = 10000;
            this.velocityDelay = 0;
            this.canBuildNow = true;
            this.lastC05Position = null;
            this.packetBatches.clear();
            this.packetBatches.add(new CopyOnWriteArrayList<>());
            this.rotSmoother.reset();
        }
        this.shelfHudInitialized = mc.player != null;
        this.shelfInitialBlocks = this.shelfHudInitialized ? this.getBlockSlot() : 0;
        this.shelfProgress.reset(0.0f);
        this.hudLastFrameTime = 0L;
        super.onEnable();
    }

    @Override
    public void onDisable() {
        if (mc != null && mc.player != null) {
            this.packetBatches.forEach(this::processBatchedPackets);
            this.packetBatches.clear();
            boolean jumpDown = InputConstants.isKeyDown(mc.getWindow().getWindow(), mc.options.keyJump.getKey().getValue());
            boolean shiftDown = InputConstants.isKeyDown(mc.getWindow().getWindow(), mc.options.keyShift.getKey().getValue());
            mc.options.keyJump.setDown(jumpDown);
            mc.options.keyShift.setDown(shiftDown);
            mc.options.keyUse.setDown(false);
            if (this.slotActive) {
                mc.player.getInventory().selected = this.altSlot;
                this.slotActive = false;
            }
            this.canBuildNow = true;
            this.lastC05Position = null;
            this.rotSmoother.reset();
            ClientBase.delayPackets.clear();
        }
        super.onDisable();
    }

    private void processBatchedPackets(List<Packet<?>> batch) {
        batch.forEach(packet -> {
            batch.remove(packet);
            PacketUtil.sendQueued((Packet<ServerGamePacketListener>) packet);
        });
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (event.getPacket() instanceof ClientboundSetEntityMotionPacket motion
                && motion.getId() == mc.player.getId()) {
            double length = new Vec3(motion.getXa() / 8000.0, 0.0, motion.getZa() / 8000.0).length();
            if (length >= 1.5) {
                this.velocityDelay = 60;
            }
        }
    }

    @EventTarget
    public void onUpdateHeldItem(UpdateHeldItemEvent event) {
        if (event.getHand() == InteractionHand.MAIN_HAND && mc.player != null) {
            boolean silenceActive = this.slotActive && !this.switchMode.is("Normal");
            if ((silenceActive || this.renderItemSpoof.getValue()) && this.altSlot >= 0) {
                event.setItemStack(mc.player.getInventory().getItem(this.altSlot));
            }
        }
    }

    @EventTarget
    public void onJump(JumpEvent event) {
        if (!this.canBuildNow && this.currentPlacement != null && this.rotationDelay > 0) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onMotion(MotionEvent event) {
        if (event.isPost() && mc.player != null) {
            if (mc.player.onGround()) {
                this.airTicks = 0;
            } else {
                this.airTicks++;
            }
        }
    }

    // 限制 BPS = 限制玩家移动速度: 超速时停止前进输入, 让玩家减速回限制内
    @EventTarget
    public void onStrafe(StrafeEvent strafeEvent) {
        if (mc.player == null) return;
        if (!this.bpsLimit.getValue()) return;
        double limit = this.bps.getValue().doubleValue();
        if (limit >= 10.0) return;
        double maxSpeed = limit / 20.0;
        if (MovementUtil.getSpeed() > maxSpeed) {
            strafeEvent.setForward(0.0f);
            strafeEvent.setStrafe(0.0f);
        }
    }

    @EventTarget(value = 1)
    public void onTick(TickEvent event) {
        if (mc.player == null) return;
        if (this.slotActive && this.slotActiveTick != this.slotTicks) {
            mc.player.getInventory().selected = this.altSlot;
            this.slotActive = false;
        }
        this.slotTicks++;

        this.packetBatches.add(new CopyOnWriteArrayList<>());
        if (this.velocityDelay > 0) this.velocityDelay--;
        if (mc.player.onGround() && this.velocityDelay <= 30) this.velocityDelay = 0;

        boolean offhandPlaceable = BlockUtil.isPlaceable(mc.player.getOffhandItem());
        int placeableSlot = -1;
        if (!offhandPlaceable) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                if (stack.getItem() instanceof BlockItem && BlockUtil.isPlaceable(stack)) {
                    placeableSlot = i;
                    break;
                }
            }
        }
        boolean hasBlocks = offhandPlaceable || placeableSlot != -1;

        // Switch Mode logic
        if (placeableSlot != -1 && placeableSlot != mc.player.getInventory().selected) {
            if (!this.slotActive) {
                this.altSlot = mc.player.getInventory().selected;
            }
            mc.player.getInventory().selected = placeableSlot;
            this.slotActive = true;
            this.slotActiveTick = this.slotTicks;
        }

        boolean jumpHeld = InputConstants.isKeyDown(mc.getWindow().getWindow(), mc.options.keyJump.getKey().getValue());
        if (this.targetYLevel == -1
                || this.targetYLevel > (int) Math.floor(mc.player.getY()) - 1
                || mc.player.onGround()
                || !MovementUtil.isMoving()
                || jumpHeld
                || this.mode.is("Normal")) {
            this.targetYLevel = (int) Math.floor(mc.player.getY()) - 1;
        }

        this.applyRotations();
        if (this.currentPlacement == null) {
            this.tellyPlaceDelayTimer = 0;
        } else {
            this.tellyPlaceDelayTimer++;
        }
        this.canBuildNow = true;
        if (this.currentPlacement != null && hasBlocks) {
            if (this.clutch.getValue() && mc.player.getDeltaMovement().y < -0.1) {
                MotionSimulator sim = new MotionSimulator(mc.player);
                sim.simulateWithFriction(2);
                // 预测位置需低于放置层超过 clutchDepth 才进入 clutch, 避免正常搭路时轻微下落误触发
                if (this.currentPlacement.position.getY() > sim.y + this.clutchDepth.getValue().doubleValue()) {
                    this.canBuildNow = false;
                }
            }
        }
        if (mc.player.onGround()) {
            this.canBuildNow = true;
        }
        if (this.currentPlacement == null) {
            ClientBase.delayPackets.clear();
        } else if (this.clutch.getValue() && (!this.canBuildNow || this.velocityDelay > 0) && this.rotationDelay <= 8) {
            boolean useC06 = this.onTickRot.getValue() || !this.canBuildNow;
            if (useC06) {
                // C06 模式：不转头，让 onPreMotion 中的 c06Place 处理放置
                this.rots.setYawPitch(mc.player.getYRot(), mc.player.getXRot());
                RotationHandler.setTargetRotation(this.rots);
                this.rotationDelay++;
            } else {
                Vec3 predictedEye = new Vec3(mc.player.getX() + mc.player.getDeltaMovement().x, mc.player.getY() + mc.player.getEyeHeight() + mc.player.getDeltaMovement().y, mc.player.getZ() + mc.player.getDeltaMovement().z);
                Vec3 targetVec = faceAimPoint(this.currentPlacement.position, this.currentPlacement.facing, predictedEye);
                Rotation rotationToBlock = RotationUtil.rotationFromPoints(targetVec.x, targetVec.y, targetVec.z, predictedEye.x, predictedEye.y, predictedEye.z);
                Rotation previousTarget = RotationHandler.targetRotation;
                this.rots.setYawPitch(rotationToBlock.getYaw(), rotationToBlock.getPitch());
                RotationHandler.setTargetRotation(this.rots);
                this.rotationDelay++;
                ClientBase.delayPackets.add(() -> {});
                ClientBase.delayPackets.add(() -> {
                    RotationHandler.prevSentRotation.setYawPitch(rotationToBlock.getYaw(), rotationToBlock.getPitch());
                    float yaw = rotationToBlock.getYaw();
                    if (yaw > -360.0f && yaw < 360.0f) {
                        yaw += 720.0f;
                    }
                    if (previousTarget != null) {
                        if (previousTarget.getYaw() != rotationToBlock.getYaw()) {
                            PacketUtil.sendQueued(new ServerboundMovePlayerPacket.Rot(yaw, rotationToBlock.getPitch(), mc.player.onGround()));
                        }
                    } else {
                        PacketUtil.sendQueued(new ServerboundMovePlayerPacket.Rot(yaw, rotationToBlock.getPitch(), mc.player.onGround()));
                    }
                    this.doSnap();
                    this.onTick(event);
                });
            }
        } else {
            this.canBuildNow = true;
            boolean wasInRescue = this.rotationDelay > 0;
            ClientBase.delayPackets.clear();
            this.rotationDelay = 0;

            // SkipTicks 结束后无论 onTickRot 设置如何，都发 C06 恢复服务端旋转追踪
            if (wasInRescue) {
                Rotation resumeRot = new Rotation(mc.player.getYRot() + rotationJitter(), mc.player.getXRot() + rotationJitter());
                mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                        mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                        resumeRot.getYaw(), resumeRot.getPitch(),
                        mc.player.onGround()
                ));
            }

            if (this.onTickRot.getValue()) {
                // OnTickRot: 不转头, 保持当前视角, 只靠 C06 发包欺骗
                this.rots.setYawPitch(mc.player.getYRot(), mc.player.getXRot());
            } else {
                this.calculateTargetRotation();
            }

            if (this.mode.is("Telly Bridge")) {
                mc.options.keyJump.setDown(MovementUtil.isMoving() || jumpHeld);
                if (this.airTicks < this.tellyAirTicks.getValue().intValue() && MovementUtil.isMoving()) {
                    this.rots.setYaw(mc.player.getYRot());
                    this.lastRots.setYawPitch(this.rots.getYaw(), this.rots.getPitch());
                    return;
                }
            } else if (this.mode.is("Keep Y")) {
                mc.options.keyJump.setDown(MovementUtil.isMoving() || jumpHeld);
            } else {
                if (this.eagle.getValue()) {
                    mc.options.keyShift.setDown(mc.player.onGround() && isOnBlockEdge(0.3f));
                }
            }
        }
        this.lastRots.setYawPitch(this.rots.getYaw(), this.rots.getPitch());
    }

    @EventTarget
    public void onPreMotion(PreMotionEvent event) {
        event.setCancelled(true);
        if (mc.screen != null || mc.player == null || this.currentPlacement == null) return;

        // C06 旋转欺骗模式: OnTickRot 开启或 SkipTicks 期间强制启用
        if (this.onTickRot.getValue() || (!this.canBuildNow && this.rotationDelay > 0)) {
            if (this.mode.is("Telly Bridge") && this.airTicks < this.tellyAirTicks.getValue().intValue()) return;
            if (this.mode.is("Telly Bridge") && this.tellyPlaceDelay.getValue().intValue() > 0 && this.tellyPlaceDelayTimer < this.tellyPlaceDelay.getValue().intValue()) return;
            this.c06Place();
            return;
        }

        if (this.mode.is("Telly Bridge") && this.airTicks < this.tellyAirTicks.getValue().intValue()) return;
        if (this.mode.is("Telly Bridge") && this.tellyPlaceDelay.getValue().intValue() > 0 && this.tellyPlaceDelayTimer < this.tellyPlaceDelay.getValue().intValue()) return;
        boolean canRayTrace = RayTraceUtil.canRayTrace(RotationHandler.targetRotation, this.currentPlacement.facing, this.currentPlacement.position, true);
        if (!this.canBuildNow && !this.isPlacementReachable(this.currentPlacement)) return;
        if (this.rotationDelay <= 0 && !this.mode.is("Telly Bridge") && !canRayTrace) return;

        this.doSnap();
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (!this.mark.getValue()) return;
        if (this.currentPlacement == null) return;

        PoseStack poseStack = event.poseStack();
        poseStack.pushPose();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionShader);
        Tesselator tesselator = RenderSystem.renderThreadTesselator();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        RenderSystem.setShaderColor(
                this.markRed.getValue().floatValue() / 255.0f,
                this.markGreen.getValue().floatValue() / 255.0f,
                this.markBlue.getValue().floatValue() / 255.0f,
                this.markAlpha.getValue().floatValue() / 255.0f);
        RenderUtil.drawBoxVerts(bufferBuilder, poseStack.last().pose(),
                new AABB(this.currentPlacement.position.relative(this.currentPlacement.facing)));
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        poseStack.popPose();
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (!this.blockCounter.getValue()) return;
        this.renderShelfHud(event);
    }

    private void renderShelfHud(Render2DEvent event) {
        if (mc.player == null) return;

        if (!this.shelfHudInitialized) {
            this.shelfInitialBlocks = this.getBlockSlot();
            this.shelfHudInitialized = true;
            this.shelfProgress.reset(0.0f);
            this.hudLastFrameTime = 0L;
        }

        int totalBlocks = this.getBlockSlot();
        float target = Mth.clamp((float) totalBlocks / Math.max(this.shelfInitialBlocks, 64), 0.0f, 1.0f);
        long now = System.currentTimeMillis();
        if (this.hudLastFrameTime == 0L) this.hudLastFrameTime = now;
        float deltaSec = Math.min((now - this.hudLastFrameTime) / 1000.0f, 0.05f);
        this.hudLastFrameTime = now;
        this.shelfProgress.setTargetValue(target);
        this.shelfProgress.update(deltaSec);
        float animProg = Mth.clamp(this.shelfProgress.getValue(), 0.0f, 1.0f);

        switch (this.blockCounterStyle.getValue()) {
            case "Modern" -> this.renderModernShelfHud(event, totalBlocks, animProg);
            case "Amunix" -> this.renderSimpleShelfHud(event, totalBlocks, animProg);
            case "Naven" -> this.renderNavenBlockHud(event, totalBlocks);
            case "Nitro" -> this.renderNitroBlockHud(event, totalBlocks);
        }
    }

    private void renderSimpleShelfHud(Render2DEvent event, int totalBlocks, float animProg) {
        String labelStr = "Scaffold";
        String bpsStr = String.format("%.1f b/s", MovementUtil.getSpeedBps());
        String blocksStr = totalBlocks + "blocks";
        float bpsWidth = shelfBpsFont.getWidth(bpsStr);
        float blocksWidth = shelfBlocksFont.getWidth(blocksStr);
        float screenWidth = mc.getWindow().getGuiScaledWidth();
        float screenHeight = mc.getWindow().getGuiScaledHeight();
        float barWidth = 115.0f;
        float barHeight = 2.0f;
        float barX = (screenWidth - barWidth) / 2.0f;
        float barY = screenHeight / 2.0f + 12.0f;
        float blocksX = barX + barWidth - blocksWidth;
        float bpsX = blocksX - 10.0f - bpsWidth;
        float textY = barY - 3.0f;

        Renderer.render(event.guiGraphics(), drawContext -> {
            try (Paint paint = new Paint()) {
                paint.setColor(0xFFFFFFFF);
                drawContext.drawString(labelStr, barX, textY + 1.0f, shelfLabelFont, paint);
                drawContext.drawString(blocksStr, blocksX, textY, shelfBlocksFont, paint);
                drawContext.drawString(bpsStr, bpsX, textY, shelfBpsFont, paint);
                paint.setColor(0x80333333);
                drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(barX, barY, barWidth, barHeight, 2.0f), paint);
                if (animProg > 0.01f) {
                    drawContext.save();
                    drawContext.clip(Rectangle.ofXYWH(barX, barY, barWidth * animProg, barHeight));
                    paint.setColor(0xFFFFFFFF);
                    drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(barX, barY, barWidth, barHeight, 1.0f), paint);
                    drawContext.restore();
                }
            }
        });
    }

    private void renderModernShelfHud(Render2DEvent event, int totalBlocks, float animProg) {
        String blocksStr = totalBlocks + " blocks";
        float textWidth = shelfBlocksFont.getWidth(blocksStr);
        float screenWidth = mc.getWindow().getGuiScaledWidth();
        float screenHeight = mc.getWindow().getGuiScaledHeight();
        float hudHeight = 30.0f;
        float ringDiameter = hudHeight - 10.0f;
        float hudWidth = 5.0f + ringDiameter + 8.0f + textWidth + 12.0f;
        float hudX = (screenWidth - hudWidth) / 2.0f;
        float hudY = screenHeight / 2.0f + 12.0f;
        float ringX = hudX + 5.0f;
        float ringY = hudY + (hudHeight - ringDiameter) / 2.0f;
        float textX = ringX + ringDiameter + 8.0f;
        float textY = hudY + hudHeight / 2.0f - shelfBlocksFont.getMetrics().capHeight() / 2.0f + 4.0f;

        Renderer.render(event.guiGraphics(), drawContext -> {
            try (Paint paint = new Paint()) {
                paint.setColor(0xCC10151C);
                drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(hudX, hudY, hudWidth, hudHeight, hudHeight / 2.0f), paint);

                float strokeWidth = 2.25f;
                float centerX = ringX + ringDiameter / 2.0f;
                float centerY = ringY + ringDiameter / 2.0f;
                float radius = ringDiameter / 2.0f;
                paint.setAntialias(true);
                paint.setStrokeWidth(strokeWidth);
                paint.setStrokeCap(Paint.StrokeCap.STROKE);
                paint.setStrokeJoin(Paint.StrokeJoin.ROUND);
                paint.setColor(0x5538D9FF);
                drawContext.drawArc(ringX, ringY, ringX + ringDiameter, ringY + ringDiameter, -90.0f, 360.0f, false, 64, paint);
                if (animProg > 0.001f) {
                    float sweepAngle = 360.0f * animProg;
                    paint.setColor(0xFF24D8FF);
                    drawContext.drawArc(ringX, ringY, ringX + ringDiameter, ringY + ringDiameter, -90.0f, sweepAngle, false, 64, paint);
                    if (animProg < 0.999f) {
                        float capRadius = strokeWidth / 2.0f;
                        float endAngle = (float) Math.toRadians(-90.0f + sweepAngle);
                        float endX = centerX + (float) Math.cos(endAngle) * radius;
                        float endY = centerY + (float) Math.sin(endAngle) * radius;
                        paint.setStrokeCap(Paint.StrokeCap.FILL);
                        drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(centerX - capRadius, centerY - radius - capRadius, strokeWidth, strokeWidth, capRadius), paint);
                        drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(endX - capRadius, endY - capRadius, strokeWidth, strokeWidth, capRadius), paint);
                    }
                }

                paint.setStrokeCap(Paint.StrokeCap.FILL);
                paint.setColor(0xFFFFFFFF);
                drawContext.drawString(blocksStr, textX, textY, shelfBlocksFont, paint);
            }
        });
    }

    private void renderNavenBlockHud(Render2DEvent event, int totalBlocks) {
        String text = "Blocks: " + totalBlocks;
        FontRenderer navenFont = FontPresets.openSans(15f);
        float textWidth = navenFont.getWidth(text);
        float screenWidth = mc.getWindow().getGuiScaledWidth();
        float screenHeight = mc.getWindow().getGuiScaledHeight();
        float boxW = textWidth + 12f;
        float boxH = 18f;
        float x = screenWidth / 2f - textWidth / 2f - 5f;
        float y = screenHeight / 2f + 30f;
        int bgAlpha = 190;
        float textYOff = 13f;
        int bodyColor = (bgAlpha << 24) | 0x000000;
        int headerColor = (bgAlpha << 24) | 0x962D2D;

        Renderer.render(event.guiGraphics(), drawContext -> {
            try (Paint paint = new Paint()) {
                // Dark body
                paint.setColor(bodyColor);
                drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, boxW, boxH, 5f), paint);
                // Red header bar
                drawContext.save();
                drawContext.clip(Rectangle.ofXYWH(x, y, boxW, 3f));
                paint.setColor(headerColor);
                drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(x, y, boxW, boxH, 5f), paint);
                drawContext.restore();
                // Text
                paint.setColor(0xFFFFFFFF);
                drawContext.drawString(text, x + 5f, y + textYOff, navenFont, paint);
            }
        });
    }

    private void renderNitroBlockHud(Render2DEvent event, int totalBlocks) {
        if (mc.player == null) return;
        ItemStack held = mc.player.getMainHandItem();
        if (held.isEmpty() || !(held.getItem() instanceof BlockItem)) return;
        String itemName = held.getHoverName().getString();
        String countStr = String.valueOf(held.getCount());

        ModuleListHud moduleList = NiloreClient.getInstance().getHudManager() != null
                ? NiloreClient.getInstance().getHudManager().getHudElement(ModuleListHud.class)
                : null;

        FontRenderer nameFont = FontPresets.pingfang(18f);
        FontRenderer countFont = FontPresets.openSans(18f);
        float textWidth = GlHelper.getStringWidth(itemName, nameFont)
                + GlHelper.getStringWidth(" " + countStr, countFont);
        float padX = 6f;
        float padY = 6f;
        float boxW = textWidth + padX * 2f;
        float boxH = Math.max(nameFont.getMetrics().capHeight(), countFont.getMetrics().capHeight()) + padY * 2f;
        float screenWidth = mc.getWindow().getGuiScaledWidth();
        float screenHeight = mc.getWindow().getGuiScaledHeight();
        float boxX = (screenWidth - boxW) / 2f;
        float boxY = screenHeight / 2f + this.nitroOffsetY.getValue().floatValue();
        float textX = boxX + padX;
        float textY = boxY + (boxH - GlHelper.getFontAscent(nameFont)) / 2f + 0.25f;
        float radius = Math.min(6f, boxH / 2f);

        Renderer.render(event.guiGraphics(), drawContext -> {
            try (Paint paint = new Paint()) {
                // 圆角矩形背景
                paint.setColor(0xCC10151C);
                drawContext.drawRoundedRect(RoundedRectangle.ofXYWHR(boxX, boxY, boxW, boxH, radius), paint);

                // 物品名：pingfang 逐字符跟随 ModuleListHud 渐变色（左移 1f）
                float cursor = textX - 1f;
                int len = itemName.length();
                for (int i = 0; i < len; i++) {
                    String ch = String.valueOf(itemName.charAt(i));
                    float t = len <= 1 ? 0.0f : i / (float) (len - 1);
                    int color = moduleList != null
                            ? moduleList.getThemeColor(i, t, Math.max(1, len - 1))
                            : 0xFFFFFFFF;
                    cursor = GlHelper.drawText(ch, cursor, textY + 0.75f, nameFont, color);
                }
                // 数量：openSans 白色（下移 1f、右移 1f）
                GlHelper.drawText(" " + countStr, cursor + 1f, textY + 0.75f, countFont, 0xFFFFFFFF);
            }
        });
    }

    private int getBlockSlot() {
        if (mc.player == null) return 0;
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() instanceof BlockItem && BlockUtil.isPlaceable(stack)) {
                total += stack.getCount();
            }
        }
        ItemStack offhand = mc.player.getOffhandItem();
        if (offhand.getItem() instanceof BlockItem && BlockUtil.isPlaceable(offhand)) {
            total += offhand.getCount();
        }
        return total;
    }

    private boolean isPlacementReachable(PlacementTarget target) {
        if (target == null || mc.player == null) return false;
        Vec3 blockCenter = new Vec3(target.position.getX() + 0.5, target.position.getY() + 0.5f, target.position.getZ() + 0.5);
        Vec3 hitPoint = blockCenter.add(new Vec3(
                target.facing.getNormal().getX() * 0.5,
                target.facing.getNormal().getY() * 0.5,
                target.facing.getNormal().getZ() * 0.5));
        Rotation currentTarget = RotationHandler.targetRotation;
        if (currentTarget == null) return false;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 toBlock = hitPoint.subtract(eye);
        return toBlock.lengthSqr() <= 20.25
                && toBlock.normalize().dot(Vec3.atLowerCornerOf(target.facing.getNormal().multiply(-1)).normalize()) >= 0.0;
    }

    private void calculateTargetRotation() {
        if (this.currentPlacement == null || mc.player == null) return;
        // 每 tick 一次机会: 预算在搜索开始时重置, 由 isRotationValidForPlacement 逐次扣减。
        this.raycastBudget = ROTATION_RAYCAST_BUDGET;

        // 1. 基于移动方向计算基础yaw
        float realYaw = mc.player.getYRot();
        if (mc.options.keyDown.isDown()) {
            realYaw += 180.0f;
            if (mc.options.keyLeft.isDown()) realYaw += 45.0f;
            else if (mc.options.keyRight.isDown()) realYaw -= 45.0f;
        } else if (mc.options.keyUp.isDown()) {
            if (mc.options.keyLeft.isDown()) realYaw -= 45.0f;
            else if (mc.options.keyRight.isDown()) realYaw += 45.0f;
        } else if (mc.options.keyRight.isDown()) {
            realYaw += 90.0f;
        } else if (mc.options.keyLeft.isDown()) {
            realYaw -= 90.0f;
        }

        float yaw = realYaw - 180.0f;
        float idealPitch = 75.5f;
        float currentPitch = this.rots.getPitch();

        // 2. 理想 pitch 优先: 直线搭路时 75.5° 全程有效, 旋转恒定 = 最流畅状态。
        if (isRotationValidForPlacement(new Rotation(yaw, idealPitch))) {
            applyRotationWithSpeed(yaw, idealPitch);
            return;
        }

        // 3. 75.5 失效: 当前 pitch 仍能命中就原地不动 —— 不要在理想角和就近角之间来回
        //    横跳(调试证实 75.5↔83~88 每 tick 乒乓, 这就是"放一次甩一次"的直接原因),
        //    只在几何真正变化时移动。
        if (isRotationValidForPlacement(new Rotation(yaw, currentPitch))) {
            applyRotationWithSpeed(yaw, currentPitch);
            return;
        }

        // 3.5 直接瞄放置面上离眼睛最近的点: 构造上必然命中, 且旋转量比任何扫掠都小。
        if (mc.player != null) {
            Vec3 eye = mc.player.getEyePosition();
            Rotation direct = RotationUtil.exactRotation(eye,
                    faceAimPoint(this.currentPlacement.position, this.currentPlacement.facing, eye));
            if (isRotationValidForPlacement(direct)) {
                applyRotationWithSpeed(direct.getYaw(), direct.getPitch());
                return;
            }
        }

        // 4. 当前 pitch 也失效: 从当前 pitch 双向外扩, 取最小修正步。
        Float nearest = findValidPitchNear(yaw, currentPitch);
        if (nearest != null) {
            applyRotationWithSpeed(yaw, nearest);
            return;
        }

        // 5. 当前邻域彻底无解: 从理想角就近找, 把旋转拉回基准。
        Float towardIdeal = findValidPitchNear(yaw, idealPitch);
        if (towardIdeal != null) {
            applyRotationWithSpeed(yaw, towardIdeal);
            return;
        }

        // 6. pitch 无解(yaw 偏了) -> yaw+pitch 全搜
        Rotation optimal = findOptimalRotation(yaw);
        if (optimal != null) {
            applyRotationWithSpeed(optimal.getYaw(), optimal.getPitch());
            return;
        }

        // 7. 全部失败: 保持当前旋转。绝不明知 raytrace 无效还把 75.5 发出去。
        applyRotationWithSpeed(yaw, currentPitch);
    }

    /**
     * 每 tick 允许花在旋转搜索上的 raycast 次数。
     *
     * <p>旧实现的两个搜索是死循环式的: {@link #findValidPitchNear} 从 0 到 30° 每 0.3°
     * 探一次(101×2 = 202 次 raycast), {@link #findOptimalRotation} 是 180×25×2 = 9000 次。
     * 每个 raycast 都是一次 {@code level.clip} 加一次实体查询, 一次 9000 次就是肉眼可见的
     * 卡顿, 而且这条路径在"贴着墙/天花板搭路"时每 tick 都会走到。现在统一受预算约束,
     * 搜索改成粗到细, 预算耗尽就返回"保持当前旋转"—— 少转一次远好过卡一帧。
     */
    private int raycastBudget;
    private static final int ROTATION_RAYCAST_BUDGET = 72;

    private boolean isRotationValidForPlacement(Rotation rotation) {
        if (this.currentPlacement == null) return false;
        if (this.raycastBudget <= 0) return false;
        this.raycastBudget--;
        return RayTraceUtil.canRayTrace(rotation, this.currentPlacement.facing, this.currentPlacement.position, true);
    }

    /**
     * 从 center(理想 pitch)向两侧对称外扩(向下最多 30°, 向上直到 90°), 取距 center
     * 最近的有效 pitch。先以 3° 粗扫定位区间, 再在区间内以 0.3° 回退到最小修正量 ——
     * 结果与逐 0.3° 完全一致(区间内无解时回落到已验证过的粗解), 但 raycast 从 202 次
     * 降到十几次。
     */
    private Float findValidPitchNear(float yaw, float center) {
        if (this.currentPlacement == null) return null;

        for (float offset = 0.0f; offset <= 30.0f; offset += 3.0f) {
            float down = Mth.clamp(center - offset, -90.0f, 90.0f);
            if (this.probePitch(yaw, down)) {
                return this.refinePitch(yaw, center, offset, true);
            }
            float up = center + offset;
            if (up <= 90.0f && this.probePitch(yaw, up)) {
                return this.refinePitch(yaw, center, offset, false);
            }
        }
        return null;
    }

    private boolean probePitch(float yaw, float pitch) {
        if (this.raycastBudget <= 0) return false;
        return this.isRotationValidForPlacement(new Rotation(yaw, pitch));
    }

    /** 粗扫命中后, 在 (offset-3, offset) 内以 0.3° 步长回退到最小修正量。 */
    private Float refinePitch(float yaw, float center, float coarseOffset, boolean downward) {
        float start = Math.max(0.0f, coarseOffset - 3.0f);
        for (float offset = start; offset < coarseOffset; offset += 0.3f) {
            float test = downward ? Mth.clamp(center - offset, -90.0f, 90.0f) : center + offset;
            if (test > 90.0f) break;
            if (this.probePitch(yaw, test)) {
                return test;
            }
        }
        // 回退段无解或预算耗尽: 粗解本身是通过校验的, 用它。
        return downward ? Mth.clamp(center - coarseOffset, -90.0f, 90.0f) : center + coarseOffset;
    }

    /**
     * 最后兜底的 yaw+pitch 全搜。原来的 1°/1° 逐度扫描最多 9000 次 raycast; 这里的
     * 6°/5° 粗网格已经能在剩余预算内覆盖同样的角度范围, 且 3.5 步(直接瞄放置面)在
     * 绝大多数几何下早就返回了, 这一层只在极端夹角下才会真正执行。
     */
    private Rotation findOptimalRotation(float baseYaw) {
        if (this.currentPlacement == null) return null;

        float currentPitch = this.rots.getPitch();
        for (float yawOffset = 0.0f; yawOffset < 180.0f; yawOffset += 6.0f) {
            for (float pitchOffset = 0.0f; pitchOffset <= 25.0f; pitchOffset += 5.0f) {
                for (int i = 0; i < 2; i++) {
                    if (this.raycastBudget <= 0) return null;
                    float testPitch = Mth.clamp(currentPitch + (i == 0 ? -pitchOffset : pitchOffset), -90.0f, 90.0f);

                    Rotation test1 = new Rotation(baseYaw + yawOffset, testPitch);
                    if (this.isRotationValidForPlacement(test1)) return test1;
                    if (yawOffset == 0.0f) continue;

                    Rotation test2 = new Rotation(baseYaw - yawOffset, testPitch);
                    if (this.isRotationValidForPlacement(test2)) return test2;
                }
            }
        }
        return null;
    }

    private void applyRotationWithSpeed(float yaw, float pitch) {
        if (this.currentPlacement == null || mc.player == null) return;

        float speed;
        if (this.syncRotSpeed.getValue()) {
            Rotation current = RotationHandler.targetRotation;
            if (current != null) {
                float realYaw = mc.player.getYRot();
                float currentDiffToReal = Math.abs(Mth.wrapDegrees(current.getYaw() - realYaw));
                float newDiffToReal = Math.abs(Mth.wrapDegrees(yaw - realYaw));
                if (newDiffToReal < currentDiffToReal) {
                    // 转头回真实视角（如从前进切到后退，目标偏转=玩家实际视角）→ Return Speed
                    speed = this.returnSpeed.getValue().floatValue();
                } else {
                    // 转头去目标面 → Turn Speed
                    speed = this.turnSpeed.getValue().floatValue();
                }
            } else {
                speed = this.turnSpeed.getValue().floatValue();
            }
        } else {
            speed = this.rotationSpeed.getValue().floatValue();
        }

        // 抖动作为"目标偏移"参与平滑, 而不是直接叠加到出站值上。叠加到出站值上会让增量
        // 离开灵敏度格点 —— Grim AimProcessor 用连续增量反推灵敏度, 格点外的偏移会把这个
        // 反推毁掉, 反而比不抖更容易被抓。作为目标偏移, 它改变的是角速度, 输出仍被量化。
        Rotation desired = new Rotation(yaw, pitch);
        float jitterScale = this.jitter.getValue().floatValue();
        if (jitterScale > 0.0f) {
            desired = new Rotation(yaw + jitterOffset() * jitterScale,
                    Mth.clamp(pitch + jitterOffset() * jitterScale, -90.0f, 90.0f));
        }

        Rotation rotation = desired;
        if (speed < 360f) {
            Rotation current = RotationHandler.targetRotation;
            if (current == null) {
                current = new Rotation(mc.player.getYRot(), mc.player.getXRot());
            }
            // 惯性+波动平滑, 代替旧的 clamp(diff, ±speed) 方波 —— Grim 的 DuplicateRotPlace
            // 专抓"相邻两次放置时 yaw 增量完全相同", 匀速转头正是它的靶子。
            // speed 语义不变: 度/tick 上限, speed 以内一步到位。
            rotation = this.rotSmoother.tick(current, desired, speed);
        }
        // GCD 对齐: 增量取整到灵敏度步长, 锚定在上一次发出的 rotation 上。Scaffold 之前
        // 从不量化, Grim AimProcessor 用连续增量 GCD 反推灵敏度时推不出稳定值, 和真人
        // 一眼就能区分。量化误差 <= 半个步长, 相对方块面的角尺寸可忽略。
        Rotation anchor = RotationHandler.prevRotation != null
                ? RotationHandler.prevRotation
                : new Rotation(mc.player.getYRot(), mc.player.getXRot());
        rotation = RotationSmoother.patchConstantRotation(rotation, anchor);
        // 挂在同一个方块面上时, 平滑器收敛后每 tick 输出同一个量化值。DuplicateRotPlace 的
        // 判据是"|deltaYaw| > 2 且与上一次放置的增量差 < 1e-4", 所以大转角时必须让增量
        // 本身有差别: 沿格点挪一格, 网格与幅度都不变。
        rotation = this.rotSmoother.breakDuplicateDelta(rotation, anchor);

        this.rots.setYawPitch(rotation.getYaw(), rotation.getPitch());
        RotationHandler.setTargetRotation(this.rots);
    }

    private void applyRotations() {
        if (mc.player == null || mc.level == null) return;
        Vec3 eye = mc.player.getEyePosition();
        if (!this.canBuildNow) {
            eye = mc.player.getEyePosition().add(mc.player.getDeltaMovement().multiply(2.0, 2.0, 2.0));
        }
        if (this.clutch.getValue() && mc.player.getDeltaMovement().y < 0.01) {
            MotionSimulator sim = new MotionSimulator(mc.player);
            sim.simulateWithFriction(2);
            eye = new Vec3(eye.x, Math.max(sim.y + mc.player.getEyeHeight(), eye.y), eye.z);
        }
        BlockPos belowFeet = BlockPos.containing(eye.x, this.targetYLevel + 0.1f, eye.z);
        int feetX = belowFeet.getX();
        int feetZ = belowFeet.getZ();
        if (mc.level.getBlockState(belowFeet).entityCanStandOn(mc.level, belowFeet, mc.player)) return;
        // 目标粘滞: 上一 tick 找到的支撑面如果仍然可放, 直接复用, 不再重新搜。
        // 每 tick 重搜有两个代价: 一是六个方向的 getBlockState 加距离比较, 二是在距离
        // 并列的等价方块之间来回切换 —— 后者表现为"放一次甩一次", 也是转头的最大来源。
        // 复用让旋转停在同一角上, 这是"尽量少的转头"最直接的一条。
        if (this.currentPlacement != null && this.isPlacementStillValid(this.currentPlacement, eye)) {
            return;
        }
        this.currentPlacement = null;
        if (this.isAbovePlaceable(eye, belowFeet)) return;
        for (int radius = 1; radius <= 6; radius++) {
            if (this.isAbovePlaceable(eye, new BlockPos(feetX, this.targetYLevel - radius, feetZ))) return;
            for (int x = 1; x <= radius; x++) {
                for (int z = 0; z <= radius - x; z++) {
                    int yOff = radius - x - z;
                    for (int signX = 0; signX <= 1; signX++) {
                        for (int signZ = 0; signZ <= 1; signZ++) {
                            BlockPos test = new BlockPos(
                                    feetX + (signX == 0 ? x : -x),
                                    this.targetYLevel - yOff,
                                    feetZ + (signZ == 0 ? z : -z));
                            // 先按交互距离粗剪: 半径 6 的螺旋里大部分候选方块根本不在
                            // 4.5 格的交互范围内, 每个都要走六次 getBlockState 才发现。
                            if (!this.isWithinSearchReach(eye, test)) continue;
                            if (this.isAbovePlaceable(eye, test)) return;
                        }
                    }
                }
            }
        }
    }

    /** 方块中心到眼睛的距离是否还在交互范围内(4.5 格的平方, 与服务端放置距离同量级)。 */
    private boolean isWithinSearchReach(Vec3 eye, BlockPos pos) {
        double dx = pos.getX() + 0.5 - eye.x;
        double dy = pos.getY() + 0.5 - eye.y;
        double dz = pos.getZ() + 0.5 - eye.z;
        return dx * dx + dy * dy + dz * dz <= 20.25;
    }

    /**
     * 上一 tick 的目标是否还值得继续瞄。
     *
     * <p>旧实现从不清理 {@code currentPlacement}: 方块放下去之后它仍然是"目标", 于是
     * 后续每 tick 都在瞄一个已经是实心的方块 —— RayTraceUtil 打得到它(它是方块), 但
     * 放置必然失败, 而转头一直照做。这里把三种失效情况一次判掉: 支撑面不再可站立、
     * 待放置位置已经不可替换、或眼睛到面已超出交互距离。
     */
    private boolean isPlacementStillValid(PlacementTarget target, Vec3 eye) {
        if (target == null || target.facing == null) return false;
        BlockPos support = target.position;
        if (!mc.level.getBlockState(support).entityCanStandOnFace(mc.level, support, mc.player, target.facing)) {
            return false;
        }
        if (!mc.level.getBlockState(support.relative(target.facing)).canBeReplaced()) {
            return false;
        }
        return faceAimPoint(support, target.facing, eye).distanceToSqr(eye) <= 20.25;
    }

    /**
     * 亚感知抖动源: 幅度 0.03~0.07°, 随机符号。它被加到"目标旋转"上再经过平滑与量化,
     * 所以出站值始终落在灵敏度格点上, 不会破坏 GCD 反推; 真正保证相邻两次放置增量不同的
     * 是 {@link RotationSmoother#breakDuplicateDelta}。
     */
    private float jitterOffset() {
        float offset = 0.03f + this.jitterRandom.nextFloat() * 0.04f;
        return this.jitterRandom.nextBoolean() ? offset : -offset;
    }

    private boolean isAbovePlaceable(Vec3 from, BlockPos pos) {
        if (mc.level == null || mc.player == null) return false;
        if (!(mc.level.getBlockState(pos).getBlock() instanceof AirBlock)) return false;
        Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5f, pos.getZ() + 0.5);
        BlockPos bestSupport = null;
        Direction bestFace = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction direction : Direction.values()) {
            Vec3 offsetCenter = center.add(new Vec3(
                    direction.getNormal().getX() * 0.5,
                    direction.getNormal().getY() * 0.5,
                    direction.getNormal().getZ() * 0.5));
            BlockPos offset = pos.offset(direction.getNormal());
            if (!mc.level.getBlockState(offset).entityCanStandOnFace(mc.level, offset, mc.player, direction)) {
                continue;
            }
            Vec3 delta = offsetCenter.subtract(from);
            if (delta.lengthSqr() > 20.25
                    || delta.normalize().dot(Vec3.atLowerCornerOf(direction.getNormal()).normalize()) < 0.0) {
                continue;
            }
            // Rank by the face point the view will actually aim at rather than by the block
            // centre: the nearest face means the shortest rotation and the cleanest click ray.
            Direction face = direction.getOpposite();
            double distance = faceAimPoint(offset, face, from).distanceToSqr(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestSupport = offset;
                bestFace = face;
            }
        }
        if (bestSupport == null) {
            return false;
        }
        this.currentPlacement = new PlacementTarget(
                new BlockPos(bestSupport.getX(), bestSupport.getY(), bestSupport.getZ()), bestFace);
        return true;
    }

    private boolean hasPlaceableInHand() {
        if (mc.player == null) return false;
        return BlockUtil.isPlaceable(mc.player.getMainHandItem())
                || BlockUtil.isPlaceable(mc.player.getOffhandItem());
    }

    private InteractionHand placementHand() {
        if (mc.player == null || BlockUtil.isPlaceable(mc.player.getMainHandItem())) {
            return InteractionHand.MAIN_HAND;
        }
        return BlockUtil.isPlaceable(mc.player.getOffhandItem())
                ? InteractionHand.OFF_HAND
                : InteractionHand.MAIN_HAND;
    }

    private boolean shouldBuild() {
        if (mc.player == null || mc.level == null) return false;
        BlockPos below = BlockPos.containing(mc.player.getX(), mc.player.getY() - 0.5, mc.player.getZ());
        return mc.level.isEmptyBlock(below) && this.hasPlaceableInHand();
    }

    private float rotationJitter() {
        float offset = (float) (Math.random() * 0.04 + 0.03);
        return Math.random() > 0.5 ? offset : -offset;
    }

    private void c06Place() {
        if (this.currentPlacement == null || mc.player == null || mc.gameMode == null) return;

        Direction facing = this.currentPlacement.facing;
        if (facing == null) return;
        if (facing == Direction.UP && !mc.player.onGround() && MovementUtil.isMoving()
                && !mc.options.keyJump.isDown() && !this.mode.is("Normal")) return;

        if (!this.hasPlaceableInHand()) return;
        InteractionHand hand = this.placementHand();

        // 计算朝向放置面的旋转
        Vec3 aim = faceAimPoint(this.currentPlacement.position, facing, mc.player.getEyePosition());
        Rotation targetRot = RotationUtil.exactRotation(mc.player.getEyePosition(), aim);

        // 用即将发包的旋转做射线校验，打不中就不发
        if (!RayTraceUtil.canRayTrace(targetRot, facing, this.currentPlacement.position, true)) {
            return;
        }

        // 保存原视角
        float origYaw = mc.player.getYRot();
        float origPitch = mc.player.getXRot();

        // 发包：C06 转到目标方向（带微抖）
        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                targetRot.getYaw() + rotationJitter(),
                targetRot.getPitch() + rotationJitter(),
                mc.player.onGround()
        ));

        // 放置方块
        BlockHitResult hit = new BlockHitResult(aim, facing, this.currentPlacement.position, false);
        InteractionResult result = mc.gameMode.useItemOn(mc.player, hand, hit);

        if (result == InteractionResult.SUCCESS) {
            if (!this.swingMode.is("Server")) {
                mc.player.swing(hand);
            }
        }

        // 发包：C06 恢复原视角（带微抖）
        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                origYaw + rotationJitter(),
                origPitch + rotationJitter(),
                mc.player.onGround()
        ));
    }

    private void doSnap() {
        if (this.currentPlacement == null || mc.player == null || mc.gameMode == null) return;

        if (!this.hasPlaceableInHand()) return;
        InteractionHand hand = this.placementHand();

        // SkipTicks时每次放置前都发送对准目标方块面的C05. Fix By StarSky
        if (!this.canBuildNow && this.clutch.getValue() && !this.currentPlacement.position.equals(this.lastC05Position)) {
            this.lastC05Position = this.currentPlacement.position;
            Vec3 c05Eye = new Vec3(mc.player.getX() + mc.player.getDeltaMovement().x, mc.player.getY() + mc.player.getEyeHeight() + mc.player.getDeltaMovement().y, mc.player.getZ() + mc.player.getDeltaMovement().z);
            Vec3 c05Target = faceAimPoint(this.currentPlacement.position, this.currentPlacement.facing, c05Eye);
            Rotation c05Rotation = RotationUtil.rotationFromPoints(c05Target.x, c05Target.y, c05Target.z, c05Eye.x, c05Eye.y, c05Eye.z);
            float yaw = c05Rotation.getYaw();
            if (yaw > -360.0f && yaw < 360.0f) {
                yaw += 720.0f;
            }
            PacketUtil.sendQueued(new ServerboundMovePlayerPacket.Rot(yaw, c05Rotation.getPitch(), mc.player.onGround()));
        }

        Direction facing = this.currentPlacement.facing;
        boolean jumpHeld = InputConstants.isKeyDown(mc.getWindow().getWindow(), mc.options.keyJump.getKey().getValue());
        if (facing == null) return;
        if (facing == Direction.UP && !mc.player.onGround() && MovementUtil.isMoving() && !jumpHeld && !this.mode.is("Normal")) {
            return;
        }
        if (!this.shouldBuild()) return;
        if (!this.isAimingAtPlacementFace()) return;
        BlockHitResult hit = new BlockHitResult(
                faceAimPoint(this.currentPlacement.position, facing, mc.player.getEyePosition()), facing,
                this.currentPlacement.position, false);
        InteractionResult result = mc.gameMode.useItemOn(mc.player, hand, hit);
        if (result == InteractionResult.SUCCESS && !this.swingMode.is("Server")) {
            mc.player.swing(hand);
        }
    }

    public static boolean isOnBlockEdge(float inflate) {
        if (mc.level == null || mc.player == null) return false;
        double halfWidth = mc.player.getBbWidth() / 2.0;
        if (inflate >= halfWidth) return true;
        double margin = Math.min(inflate, halfWidth * 0.99);
        Vec3 pos = mc.player.position();
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                BlockPos corner = BlockPos.containing(
                    pos.x + sx * margin,
                    pos.y - 1.0,
                    pos.z + sz * margin
                );
                BlockState state = mc.level.getBlockState(corner);
                if (!state.entityCanStandOn(mc.level, corner, mc.player)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Point on the given face closest to the eye, inset from the edges.
     *
     * <p>Aiming at the face centre makes the rotation longer than it needs to be and, for a face
     * seen at a steep angle, the ray can slip past the block. The nearest inset point is the
     * shortest valid rotation and is the same point the click should report.
     */
    public static Vec3 faceAimPoint(BlockPos pos, Direction face, Vec3 eye) {
        double inset = 0.15;
        double x = face.getAxis() == Direction.Axis.X ? pos.getX() + 0.5 + face.getStepX() * 0.5
                : Mth.clamp(eye.x, pos.getX() + inset, pos.getX() + 1.0 - inset);
        double y = face.getAxis() == Direction.Axis.Y ? pos.getY() + 0.5 + face.getStepY() * 0.5
                : Mth.clamp(eye.y, pos.getY() + inset, pos.getY() + 1.0 - inset);
        double z = face.getAxis() == Direction.Axis.Z ? pos.getZ() + 0.5 + face.getStepZ() * 0.5
                : Mth.clamp(eye.z, pos.getZ() + inset, pos.getZ() + 1.0 - inset);
        return new Vec3(x, y, z);
    }

    public static Vec3 getFaceCenter(BlockPos pos, Direction direction) {
        return new Vec3(
                pos.getX() + 0.5 + direction.getNormal().getX() * 0.5,
                pos.getY() + 0.5 + direction.getNormal().getY() * 0.5,
                pos.getZ() + 0.5 + direction.getNormal().getZ() * 0.5);
    }

    public static Vec3 getHitVec(BlockPos pos, Direction direction) {
        return getFaceCenter(pos, direction);
    }

    private boolean isAimingAtPlacementFace() {
        if (this.currentPlacement == null || mc.player == null) return false;
        Rotation rotation = RotationHandler.targetRotation;
        if (rotation == null) {
            rotation = this.rots;
        }
        if (rotation == null) return false;
        return RayTraceUtil.canRayTrace(rotation, this.currentPlacement.facing, this.currentPlacement.position, true);
    }

    private record PlacementTarget(BlockPos position, Direction facing) {
    }
}
