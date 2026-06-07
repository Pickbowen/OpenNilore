package shit.lizz.modules.impl.player;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import shit.lizz.LizzClient;
import shit.lizz.event.impl.RenderEvent;
import shit.lizz.event.impl.TickEvent;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.modules.impl.combat.KillAura;
import shit.lizz.settings.impl.BooleanSetting;
import shit.lizz.settings.impl.NumberSetting;
import shit.lizz.utils.animation.TickTimer;
import shit.lizz.utils.game.PlayerUtil;
import shit.lizz.utils.game.RotationUtil;
import shit.lizz.utils.misc.ChatUtil;
import shit.lizz.utils.misc.PacketUtil;
import shit.lizz.utils.render.RenderUtil;
import shit.lizz.utils.rotation.Rotation;
import shit.lizz.event.EventTarget;

public class AutoWebPlace extends Module {
    public static AutoWebPlace INSTANCE;
    public static Rotation targetRotation;

    public final NumberSetting rangeSetting = new NumberSetting("Range", 4.0, 3.0, 5.0, 0.1);
    public final NumberSetting delaySetting = new NumberSetting("Delay", 4.0, 1.0, 20.0, 1.0);
    public final BooleanSetting renderSetting = new BooleanSetting("Render", true);
    public final BooleanSetting groundWebSetting = new BooleanSetting("Ground Web", false);
    public final BooleanSetting lavaSetting = new BooleanSetting("Lava", true);
    public final BooleanSetting lavaWaitWaterBucketSetting = new BooleanSetting("Lava Wait Water Bucket", true);
    public final BooleanSetting debugSetting = new BooleanSetting("Debug", true);

    private final TickTimer placementTimer = new TickTimer();
    private Phase placementPhase = Phase.IDLE;
    private PlacementInfo currentPlacement;
    private PlacementType activePlacementType;
    private Entity target;
    private BlockPos targetBlockPos;
    private int waitTicks;
    private int retrieveTicks;
    private int retrieveAttempts;
    private int lavaReapplyCount;
    private int sourceWaitCount;
    private boolean lavaPlaced;
    private boolean retriedLavaPlace;
    private GroundWebData currentGroundWebData;
    private GroundWebPhase currentGroundWebPhase = GroundWebPhase.IDLE;
    private BlockPos groundWebFootPos;
    private int groundWebBreakTicks;
    private boolean groundWebBreakStarted;
    private BlockPos lastDebugSupportPos;
    private Direction lastDebugFace;
    private Vec3 lastDebugAimPoint;

    public AutoWebPlace() {
        super("AutoWebPlace", Category.PLAYER);
        INSTANCE = this;
    }

    @Override
    protected void onEnable() {
        this.reset();
        super.onEnable();
    }

    @Override
    protected void onDisable() {
        this.reset();
        super.onDisable();
    }

    private void reset() {
        this.placementPhase = Phase.IDLE;
        targetRotation = null;
        this.currentPlacement = null;
        this.activePlacementType = null;
        this.target = null;
        this.targetBlockPos = null;
        this.waitTicks = 0;
        this.retrieveTicks = 0;
        this.retrieveAttempts = 0;
        this.lavaReapplyCount = 0;
        this.sourceWaitCount = 0;
        this.lavaPlaced = false;
        this.retriedLavaPlace = false;
        this.currentGroundWebData = null;
        this.currentGroundWebPhase = GroundWebPhase.IDLE;
        this.groundWebFootPos = null;
        this.groundWebBreakTicks = 0;
        this.groundWebBreakStarted = false;
        this.lastDebugSupportPos = null;
        this.lastDebugFace = null;
        this.lastDebugAimPoint = null;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!LizzClient.isReady() || mc.gameMode == null) {
            return;
        }
        if (!this.isPhaseActive() && this.isRotationBlocked()) {
            this.reset();
            return;
        }
        switch (this.placementPhase) {
            case LAVA_RETRIEVE -> this.doLavaRetrieve();
            case GROUND_WEB_BREAK -> this.doGroundWebBreak();
            case IDLE -> this.tickSearch();
            case PLACE -> this.tickPlace();
        }
    }

    private void tickSearch() {
        if (this.findCobwebSlot() == -1 && (!this.lavaSetting.getValue() || this.findLavaBucketSlot() == -1)) {
            return;
        }
        if (!this.placementTimer.hasPassed(this.delaySetting.getValue().intValue())) {
            return;
        }
        Optional<PlacementTarget> targetOpt = this.findPlacementTarget();
        if (targetOpt.isEmpty()) {
            return;
        }
        PlacementTarget target = targetOpt.get();
        this.target = target.target;
        this.currentPlacement = target.placement;
        this.activePlacementType = target.type;
        this.targetBlockPos = target.placement.place;
        this.lavaReapplyCount = 0;
        if (target.groundWebData != null) {
            this.applyGroundWebData(target.groundWebData);
        }
        targetRotation = RotationUtil.exactRotation(mc.player.getEyePosition(), this.currentPlacement.aim);
        this.placementPhase = Phase.PLACE;
    }

    private void tickPlace() {
        if (!this.isPlacementValid()) {
            this.reset();
            return;
        }
        if (!this.executePlacement()) {
            this.reset();
            return;
        }
        if (this.isGroundWebMode()) {
            if (!this.advanceGroundWebBreak()) {
                this.placementTimer.reset();
            }
            return;
        }
        if (this.activePlacementType == PlacementType.WEB_EXIT_PREDICT) {
            this.placementTimer.reset();
            this.reset();
            return;
        }
        if (this.activePlacementType == PlacementType.LAVA) {
            this.placementTimer.reset();
            targetRotation = RotationUtil.rotationToBlock(this.currentPlacement.support, 0.0f);
            this.waitTicks = 1;
            this.retrieveTicks = 0;
            this.retrieveAttempts = 0;
            this.sourceWaitCount = 0;
            this.lavaPlaced = false;
            this.retriedLavaPlace = false;
            this.placementPhase = Phase.LAVA_RETRIEVE;
            return;
        }
        if (!this.tryChainLava()) {
            this.placementTimer.reset();
            this.reset();
        } else {
            this.placementPhase = Phase.PLACE;
        }
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (!this.renderSetting.getValue() || (this.targetBlockPos == null && this.lastDebugSupportPos == null) || mc.gameRenderer == null) {
            return;
        }
        Color tint = this.activePlacementType == PlacementType.LAVA ? new Color(255, 100, 0) : new Color(255, 255, 255);
        PoseStack poseStack = event.poseStack();
        Vec3 cameraPos = mc.gameRenderer.getMainCamera().getPosition();
        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        if (this.targetBlockPos != null) {
            AABB box = new AABB(this.targetBlockPos);
            RenderSystem.setShaderColor(tint.getRed() / 255.0f, tint.getGreen() / 255.0f, tint.getBlue() / 255.0f, 0.25f);
            RenderUtil.drawSolidBox(box, poseStack);
            RenderSystem.setShaderColor(tint.getRed() / 255.0f, tint.getGreen() / 255.0f, tint.getBlue() / 255.0f, 0.75f);
            RenderUtil.drawOutlineBox(box, poseStack);
        }
        if (this.currentPlacement != null) {
            this.renderFaceHighlight(poseStack, this.currentPlacement.support, this.currentPlacement.face, new Color(255, 230, 0));
            this.renderPoint(poseStack, this.currentPlacement.aim, new Color(255, 230, 0));
        } else if (this.lastDebugSupportPos != null && this.lastDebugFace != null) {
            this.renderFaceHighlight(poseStack, this.lastDebugSupportPos, this.lastDebugFace, new Color(255, 40, 40));
            if (this.lastDebugAimPoint != null) {
                this.renderPoint(poseStack, this.lastDebugAimPoint, new Color(255, 40, 40));
            }
        }
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        poseStack.popPose();
    }

    private void renderFaceHighlight(PoseStack poseStack, BlockPos pos, Direction face, Color color) {
        AABB faceAabb = this.getFaceAabb(pos, face);
        RenderSystem.setShaderColor(color.getRed() / 255.0f, color.getGreen() / 255.0f, color.getBlue() / 255.0f, 0.45f);
        RenderUtil.drawSolidBox(faceAabb, poseStack);
        RenderSystem.setShaderColor(color.getRed() / 255.0f, color.getGreen() / 255.0f, color.getBlue() / 255.0f, 0.95f);
        RenderUtil.drawOutlineBox(faceAabb, poseStack);
    }

    private void renderPoint(PoseStack poseStack, Vec3 point, Color color) {
        double r = 0.04;
        AABB box = new AABB(point.x - r, point.y - r, point.z - r, point.x + r, point.y + r, point.z + r);
        RenderSystem.setShaderColor(color.getRed() / 255.0f, color.getGreen() / 255.0f, color.getBlue() / 255.0f, 0.85f);
        RenderUtil.drawSolidBox(box, poseStack);
    }

    private AABB getFaceAabb(BlockPos pos, Direction face) {
        double minX = pos.getX();
        double minY = pos.getY();
        double minZ = pos.getZ();
        double maxX = minX + 1.0;
        double maxY = minY + 1.0;
        double maxZ = minZ + 1.0;
        double t = 0.002;
        return switch (face) {
            case UP -> new AABB(minX, maxY - t, minZ, maxX, maxY + t, maxZ);
            case DOWN -> new AABB(minX, minY - t, minZ, maxX, minY + t, maxZ);
            case EAST -> new AABB(maxX - t, minY, minZ, maxX + t, maxY, maxZ);
            case WEST -> new AABB(minX - t, minY, minZ, minX + t, maxY, maxZ);
            case SOUTH -> new AABB(minX, minY, maxZ - t, maxX, maxY, maxZ + t);
            case NORTH -> new AABB(minX, minY, minZ - t, maxX, maxY, minZ + t);
        };
    }

    private Optional<PlacementTarget> findPlacementTarget() {
        if (mc.level == null || mc.player == null) {
            return Optional.empty();
        }
        float range = this.rangeSetting.getValue().floatValue();
        List<Entity> candidates = StreamSupport.<Entity>stream(mc.level.entitiesForRendering().spliterator(), false)
                .filter(e -> e instanceof Player && e != mc.player)
                .filter(e -> e.distanceTo(mc.player) <= range)
                .filter(e -> KillAura.INSTANCE != null && KillAura.INSTANCE.isValidTarget(e))
                .sorted(Comparator.comparingDouble(e -> e.distanceTo(mc.player)))
                .toList();
        int cobwebSlot = this.findCobwebSlot();
        int lavaSlot = this.lavaSetting.getValue() ? this.findLavaBucketSlot() : -1;

        for (Entity entity : candidates) {
            boolean inCobweb = this.isEntityInCobweb(entity);
            if (inCobweb) {
                Optional<PlacementTarget> webExit = this.findWebExitPlacement(entity, cobwebSlot);
                if (webExit.isPresent()) {
                    return webExit;
                }
                this.debugLog("Skip cobweb because target stays in cobweb: " + entity.getName().getString());
            }

            if (!inCobweb && this.groundWebSetting.getValue() && cobwebSlot != -1) {
                Optional<GroundWebData> groundWeb = this.findGroundWebPlacement(entity);
                if (groundWeb.isPresent()) {
                    GroundWebData data = groundWeb.get();
                    this.debugLog("Ground web target=" + entity.getName().getString()
                            + " foot=" + this.formatBlockPos(data.footPos)
                            + " head=" + this.formatBlockPos(data.headPos));
                    return Optional.of(new PlacementTarget(entity, data.footPlacement, PlacementType.GROUND_WEB, data));
                }
            }

            Optional<PlacementInfo> cobwebPlace = !inCobweb && cobwebSlot != -1 ? this.findLavaPlacement(entity) : Optional.empty();
            if (cobwebPlace.isPresent()) {
                PlacementInfo info = cobwebPlace.get();
                this.debugLog("Cobweb target=" + entity.getName().getString()
                        + " place=" + this.formatBlockPos(info.place)
                        + " support=" + this.formatBlockPos(info.support)
                        + " face=" + info.face
                        + " aim=" + this.formatVec3(info.aim));
                return Optional.of(new PlacementTarget(entity, info, PlacementType.COBWEB, null));
            }

            if (lavaSlot != -1 && this.shouldPlaceLavaEx(entity, inCobweb)) {
                Optional<PlacementInfo> lavaPlace = this.findLavaSourcePlacement(entity);
                if (lavaPlace.isPresent()) {
                    PlacementInfo info = lavaPlace.get();
                    this.debugLog("Lava target=" + entity.getName().getString()
                            + " place=" + this.formatBlockPos(info.place)
                            + " support=" + this.formatBlockPos(info.support)
                            + " face=" + info.face
                            + " aim=" + this.formatVec3(info.aim));
                    return Optional.of(new PlacementTarget(entity, info, PlacementType.LAVA, null));
                }
            }
        }
        return Optional.empty();
    }

    private Optional<PlacementTarget> findWebExitPlacement(Entity entity, int cobwebSlot) {
        if (cobwebSlot == -1) {
            return Optional.empty();
        }
        Vec3 movement = this.getEntityMovement(entity);
        if (movement.x * movement.x + movement.z * movement.z < 4.0e-4) {
            return Optional.empty();
        }
        for (int tickAhead = 2; tickAhead <= 3; tickAhead++) {
            AABB predicted = entity.getBoundingBox().move(movement.x * tickAhead, 0.0, movement.z * tickAhead);
            if (this.isAllCobweb(predicted)) {
                continue;
            }
            Optional<PlacementTarget> placement = this.tryPlacements(entity, this.getPositionsForAabb(predicted, tickAhead), movement);
            if (placement.isPresent()) {
                return placement;
            }
        }
        return Optional.empty();
    }

    private Optional<PlacementTarget> tryPlacements(Entity entity, List<PredictionTick> ticks, Vec3 movement) {
        for (PredictionTick tick : ticks) {
            Optional<PlacementInfo> place = this.findPlacementForTick(entity, tick);
            if (place.isPresent()) {
                PlacementInfo info = place.get();
                this.debugLog("Predicted web exit target=" + entity.getName().getString()
                        + " ticks=" + tick.ticks
                        + " place=" + this.formatBlockPos(info.place)
                        + " support=" + this.formatBlockPos(info.support)
                        + " face=" + info.face
                        + " move=" + this.formatVec3(movement));
                return Optional.of(new PlacementTarget(entity, info, PlacementType.WEB_EXIT_PREDICT, null));
            }
            Optional<GroundWebData> groundWeb = this.findGroundWebAt(entity, tick.footPos, tick.headPos, "predicted web exit");
            if (groundWeb.isPresent()) {
                GroundWebData data = groundWeb.get();
                this.debugLog("Predicted web exit ground web target=" + entity.getName().getString()
                        + " ticks=" + tick.ticks
                        + " foot=" + this.formatBlockPos(data.footPos)
                        + " head=" + this.formatBlockPos(data.headPos)
                        + " move=" + this.formatVec3(movement));
                return Optional.of(new PlacementTarget(entity, data.footPlacement, PlacementType.GROUND_WEB, data));
            }
        }
        return Optional.empty();
    }

    private Optional<PlacementInfo> findPlacementForTick(Entity entity, PredictionTick tick) {
        if (mc.level.getBlockState(tick.headPos).is(Blocks.COBWEB)) {
            return Optional.empty();
        }
        return this.findLavaPlacementAt(entity, tick.headPos);
    }

    private List<PredictionTick> getPositionsForAabb(AABB aabb, int tickAhead) {
        ArrayList<PredictionTick> list = new ArrayList<>();
        int minX = (int) Math.floor(aabb.minX);
        int maxX = (int) Math.floor(aabb.maxX);
        int minY = (int) Math.floor(aabb.minY);
        int maxY = (int) Math.floor(aabb.maxY);
        int minZ = (int) Math.floor(aabb.minZ);
        int maxZ = (int) Math.floor(aabb.maxZ);
        int footY = (int) Math.floor(aabb.minY + 0.001);
        double centerX = (aabb.minX + aabb.maxX) * 0.5;
        double centerZ = (aabb.minZ + aabb.maxZ) * 0.5;
        BlockPos center = BlockPos.containing(centerX, aabb.minY, centerZ);
        if (this.isAabbIntersecting(aabb, center)) {
            this.addPositionToList(list, tickAhead, center);
        }
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (aabb.intersects(new AABB(pos)) && !mc.level.getBlockState(pos).is(Blocks.COBWEB)) {
                        int chosenY = y <= footY ? footY : y - 1;
                        this.addPositionToList(list, tickAhead, new BlockPos(x, chosenY, z));
                    }
                }
            }
        }
        list.sort(Comparator.comparingDouble(t -> Vec3.atCenterOf(t.footPos).distanceToSqr(centerX, footY + 0.5, centerZ)));
        return list;
    }

    private boolean isAabbIntersecting(AABB aabb, BlockPos pos) {
        BlockPos above = pos.above();
        return this.isCobwebIntersecting(aabb, pos) || this.isCobwebIntersecting(aabb, above);
    }

    private boolean isCobwebIntersecting(AABB aabb, BlockPos pos) {
        return aabb.intersects(new AABB(pos)) && !mc.level.getBlockState(pos).is(Blocks.COBWEB);
    }

    private void addPositionToList(List<PredictionTick> list, int tickAhead, BlockPos foot) {
        BlockPos head = foot.above();
        if (mc.level.getBlockState(foot).is(Blocks.COBWEB) && mc.level.getBlockState(head).is(Blocks.COBWEB)) {
            return;
        }
        PredictionTick tick = new PredictionTick(tickAhead, foot, head);
        if (!list.contains(tick)) {
            list.add(tick);
        }
    }

    private boolean tryChainLava() {
        if (this.target == null || !this.lavaSetting.getValue() || this.findLavaBucketSlot() == -1) {
            return false;
        }
        if (!this.shouldPlaceLava(this.target)) {
            return false;
        }
        Optional<PlacementInfo> placement = this.findLavaSourcePlacement(this.target);
        if (placement.isEmpty()) {
            this.debugLog("Chain lava skipped: no placement target=" + this.target.getName().getString());
            return false;
        }
        this.lavaReapplyCount = 0;
        this.applyLavaPlacement(placement.get());
        this.debugLog("Chain lava after cobweb target=" + this.target.getName().getString()
                + " place=" + this.formatBlockPos(this.currentPlacement.place)
                + " support=" + this.formatBlockPos(this.currentPlacement.support)
                + " face=" + this.currentPlacement.face
                + " aim=" + this.formatVec3(this.currentPlacement.aim));
        return true;
    }

    private void applyLavaPlacement(PlacementInfo placement) {
        this.currentPlacement = placement;
        this.activePlacementType = PlacementType.LAVA;
        this.targetBlockPos = placement.place;
        targetRotation = RotationUtil.exactRotation(mc.player.getEyePosition(), placement.aim);
    }

    private void applyGroundWebData(GroundWebData data) {
        this.currentGroundWebData = data;
        this.currentGroundWebPhase = GroundWebPhase.FOOT_PLACED;
        this.groundWebFootPos = data.footPos;
        this.groundWebBreakTicks = 0;
        this.groundWebBreakStarted = false;
    }

    private boolean isGroundWebMode() {
        return this.activePlacementType == PlacementType.GROUND_WEB;
    }

    private boolean advanceGroundWebBreak() {
        if (this.currentGroundWebData == null) {
            this.reset();
            return false;
        }
        switch (this.currentGroundWebPhase) {
            case FOOT_PLACED -> {
                this.currentGroundWebPhase = GroundWebPhase.HEAD_PLACED;
                this.applyPlacement(this.currentGroundWebData.headPlacement, PlacementType.GROUND_WEB);
                return true;
            }
            case HEAD_PLACED -> {
                this.startGroundWebBreak();
                return true;
            }
            default -> {
                this.reset();
                return false;
            }
        }
    }

    private void applyPlacement(PlacementInfo placement, PlacementType type) {
        this.currentPlacement = placement;
        this.activePlacementType = type;
        this.targetBlockPos = placement.place;
        targetRotation = RotationUtil.exactRotation(mc.player.getEyePosition(), placement.aim);
    }

    private void startGroundWebBreak() {
        if (this.findSwordSlot() == -1) {
            this.debugLog("Ground web break skipped: no sword");
            this.reset();
            return;
        }
        this.currentPlacement = null;
        this.activePlacementType = null;
        this.targetBlockPos = this.groundWebFootPos;
        targetRotation = RotationUtil.exactRotation(mc.player.getEyePosition(), Vec3.atCenterOf(this.groundWebFootPos));
        this.groundWebBreakTicks = 0;
        this.groundWebBreakStarted = false;
        this.placementPhase = Phase.GROUND_WEB_BREAK;
    }

    private boolean shouldPlaceLava(Entity entity) {
        return this.shouldPlaceLavaEx(entity, false);
    }

    private boolean shouldPlaceLavaEx(Entity entity, boolean targetInCobweb) {
        if (!this.lavaSetting.getValue()) {
            return false;
        }
        if (entity.isOnFire()) {
            this.debugLog("Skip lava because target is on fire: " + entity.getName().getString());
            return false;
        }
        boolean inWeb = targetInCobweb && this.isEntityInCobweb(entity);
        if (!inWeb && !this.isEntityGrounded(entity)) {
            this.debugLog("Skip lava because target is not grounded: " + entity.getName().getString()
                    + " y=" + String.format("%.2f", entity.getY())
                    + " motion=" + this.formatVec3(entity.getDeltaMovement()));
            return false;
        }
        if (!inWeb && this.isEntityMoving(entity)) {
            this.debugLog("Skip lava because target is moving: " + entity.getName().getString()
                    + " move=" + this.formatVec3(this.getEntityMovement(entity)));
            return false;
        }
        return true;
    }

    private boolean isEntityMoving(Entity entity) {
        Vec3 movement = this.getEntityMovement(entity);
        return movement.x * movement.x + movement.z * movement.z >= 0.01;
    }

    private Vec3 getEntityMovement(Entity entity) {
        return new Vec3(entity.getX() - entity.xo, 0.0, entity.getZ() - entity.zo);
    }

    private boolean isEntityGrounded(Entity entity) {
        double yFrac = entity.getY() - Math.floor(entity.getY());
        double yDistance = Math.min(yFrac, 1.0 - yFrac);
        return entity.onGround() || (yDistance < 0.08 && Math.abs(entity.getDeltaMovement().y) < 0.12);
    }

    private Optional<PlacementInfo> findLavaPlacement(Entity entity) {
        for (BlockPos pos : this.getLavaPositions(entity)) {
            Optional<PlacementInfo> placement = this.findLavaPlacementAt(entity, pos);
            if (placement.isPresent()) {
                return placement;
            }
        }
        return Optional.empty();
    }

    private Optional<PlacementInfo> findLavaPlacementAt(Entity entity, BlockPos place) {
        if (!mc.level.getBlockState(place).canBeReplaced()) {
            return Optional.empty();
        }
        if (this.intersectsPlayer(place)) {
            return Optional.empty();
        }
        Vec3 eye = mc.player.getEyePosition();
        double dx = entity.getX() - eye.x;
        double dz = entity.getZ() - eye.z;
        Direction nearest = Direction.getNearest((float) dx, 0.0f, (float) dz);
        List<Direction> directions = new ArrayList<>();
        directions.add(nearest);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (direction != nearest) {
                directions.add(direction);
            }
        }
        directions.add(Direction.DOWN);
        directions.add(Direction.UP);

        for (Direction direction : directions) {
            BlockPos supportPos = place.relative(direction);
            Direction face = direction.getOpposite();
            Optional<Vec3> hit = this.findFaceHitPoint(supportPos, face);
            if (hit.isEmpty()) continue;
            return Optional.of(new PlacementInfo(supportPos, face, hit.get(), place));
        }
        return Optional.empty();
    }

    private List<BlockPos> getLavaPositions(Entity entity) {
        ArrayList<BlockPos> list = new ArrayList<>();
        this.addBlockPosToList(list, BlockPos.containing(entity.getX(), entity.getY() + 1.0, entity.getZ()));
        AABB box = entity.getBoundingBox();
        int minX = (int) Math.floor(box.minX - 0.45);
        int maxX = (int) Math.floor(box.maxX + 0.45);
        int minZ = (int) Math.floor(box.minZ - 0.45);
        int maxZ = (int) Math.floor(box.maxZ + 0.45);
        int headY = (int) Math.floor(entity.getY() + 1.0);
        int topY = (int) Math.floor(box.maxY - 0.05);
        for (int y = Math.min(headY, topY); y <= Math.max(headY, topY); y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    this.addBlockPosToList(list, new BlockPos(x, y, z));
                }
            }
        }
        return list;
    }

    private void addBlockPosToList(List<BlockPos> list, BlockPos pos) {
        if (!list.contains(pos)) {
            list.add(pos);
        }
    }

    private Optional<GroundWebData> findGroundWebPlacement(Entity entity) {
        if (!this.canPlaceGroundWeb(entity)) {
            return Optional.empty();
        }
        if (this.findSwordSlot() == -1) {
            this.debugLog("Ground web skipped: no sword");
            return Optional.empty();
        }
        BlockPos foot = BlockPos.containing(entity.getX(), entity.getY(), entity.getZ());
        return this.findGroundWebAt(entity, foot, foot.above(), "ground web");
    }

    private Optional<GroundWebData> findGroundWebAt(Entity entity, BlockPos footPos, BlockPos headPos, String label) {
        if (!this.isBlockReplaceable(footPos) || !this.isBlockReplaceable(headPos)) {
            return Optional.empty();
        }
        Optional<PlacementInfo> footPlacement = this.raycastPlacement(footPos.below(), footPos, entity.getX(), entity.getZ(), label + " foot");
        if (footPlacement.isEmpty()) {
            return Optional.empty();
        }
        PlacementInfo headPlacement = this.createPlacementInfo(footPos, headPos);
        return Optional.of(new GroundWebData(footPos, headPos, footPlacement.get(), headPlacement));
    }

    private boolean canPlaceGroundWeb(Entity entity) {
        if (!this.isEntityGrounded(entity)) {
            this.debugLog("Skip ground web because target is not grounded: " + entity.getName().getString()
                    + " y=" + String.format("%.2f", entity.getY())
                    + " motion=" + this.formatVec3(entity.getDeltaMovement()));
            return false;
        }
        if (this.isEntityMoving(entity)) {
            this.debugLog("Skip ground web because target is moving: " + entity.getName().getString()
                    + " move=" + this.formatVec3(this.getEntityMovement(entity)));
            return false;
        }
        return true;
    }

    private boolean isBlockReplaceable(BlockPos pos) {
        return mc.level.getBlockState(pos).canBeReplaced()
                && mc.level.getFluidState(pos).isEmpty()
                && !this.intersectsPlayer(pos);
    }

    private Optional<PlacementInfo> findLavaSourcePlacement(Entity entity) {
        Vec3 foot = new Vec3(entity.getX(), entity.getY(), entity.getZ());
        Optional<PlacementInfo> placement = this.findLavaGroundPlacement(entity.getX(), entity.getY(), entity.getZ(), "current");
        if (placement.isPresent()) {
            return placement;
        }
        this.debugLog("Lava fail: no lava placement foot=" + this.formatVec3(foot) + " motion=" + this.formatVec3(entity.getDeltaMovement()));
        return Optional.empty();
    }

    private Optional<PlacementInfo> findLavaGroundPlacement(double x, double y, double z, String label) {
        Vec3 start = new Vec3(x, y + 0.2, z);
        Vec3 end = new Vec3(x, y - 2.2, z);
        BlockHitResult hit = mc.level.clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return Optional.empty();
        }
        if (hit.getDirection() != Direction.UP) {
            this.debugLog("Lava fail: " + label + " ground probe face not up face=" + hit.getDirection()
                    + " support=" + this.formatBlockPos(hit.getBlockPos())
                    + " foot=" + this.formatVec3(new Vec3(x, y, z)));
            return Optional.empty();
        }
        return this.findAimAtSupport(hit.getBlockPos(), x, z, label);
    }

    private Vec3 clampAimPoint(BlockPos pos, double x, double z) {
        return new Vec3(
                this.clampValue(x, pos.getX() + 0.1, pos.getX() + 0.9),
                pos.getY() + 0.99,
                this.clampValue(z, pos.getZ() + 0.1, pos.getZ() + 0.9));
    }

    private double clampValue(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private Optional<PlacementInfo> findAimAtSupport(BlockPos supportPos, double x, double z, String label) {
        Vec3 aim = this.clampAimPoint(supportPos, x, z);
        return this.aimAndCheckPlacement(aim, supportPos, label);
    }

    private Optional<PlacementInfo> raycastPlacement(BlockPos supportPos, BlockPos placePos, double targetX, double targetZ, String label) {
        if (!this.isValidSupport(supportPos)) {
            return Optional.empty();
        }
        BlockHitResult lastHit = null;
        Vec3 lastAim = null;
        for (Vec3 candidate : this.getAimCandidates(supportPos, targetX, targetZ)) {
            if (mc.player.getEyePosition().distanceToSqr(candidate) > 25.0) continue;
            Vec3 offset = this.offsetTowardsFace(candidate, Direction.UP);
            BlockHitResult hit = mc.level.clip(new ClipContext(mc.player.getEyePosition(), offset, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != HitResult.Type.BLOCK) {
                lastAim = offset;
                continue;
            }
            lastHit = hit;
            lastAim = offset;
            if (hit.getBlockPos().equals(supportPos) && hit.getDirection() == Direction.UP) {
                this.lastDebugSupportPos = supportPos;
                this.lastDebugFace = Direction.UP;
                this.lastDebugAimPoint = hit.getLocation();
                return Optional.of(new PlacementInfo(supportPos, Direction.UP, hit.getLocation(), placePos));
            }
        }
        if (lastHit != null) {
            this.lastDebugSupportPos = lastHit.getBlockPos();
            this.lastDebugFace = lastHit.getDirection();
            this.lastDebugAimPoint = lastAim;
            this.debugLog("Placement fail: " + label + " support mismatch expected=" + this.formatBlockPos(supportPos)
                    + " hit=" + this.formatBlockPos(lastHit.getBlockPos())
                    + " face=" + lastHit.getDirection()
                    + " aim=" + this.formatVec3(lastAim));
        } else if (lastAim != null) {
            this.lastDebugSupportPos = supportPos;
            this.lastDebugFace = Direction.UP;
            this.lastDebugAimPoint = lastAim;
            this.debugLog("Placement fail: " + label + " ray miss support=" + this.formatBlockPos(supportPos) + " aim=" + this.formatVec3(lastAim));
        }
        return Optional.empty();
    }

    private List<Vec3> getAimCandidates(BlockPos pos, double targetX, double targetZ) {
        double y = pos.getY() + 1.0;
        ArrayList<Vec3> list = new ArrayList<>();
        list.add(new Vec3(pos.getX() + 0.5, y, pos.getZ() + 0.5));
        list.add(new Vec3(
                this.clampValue(targetX, pos.getX() + 0.25, pos.getX() + 0.75),
                y,
                this.clampValue(targetZ, pos.getZ() + 0.25, pos.getZ() + 0.75)));
        list.add(new Vec3(pos.getX() + 0.35, y, pos.getZ() + 0.35));
        list.add(new Vec3(pos.getX() + 0.65, y, pos.getZ() + 0.35));
        list.add(new Vec3(pos.getX() + 0.35, y, pos.getZ() + 0.65));
        list.add(new Vec3(pos.getX() + 0.65, y, pos.getZ() + 0.65));
        return list;
    }

    private PlacementInfo createPlacementInfo(BlockPos supportPos, BlockPos placePos) {
        Vec3 aim = new Vec3(supportPos.getX() + 0.5, supportPos.getY() + 1.0, supportPos.getZ() + 0.5);
        return new PlacementInfo(supportPos, Direction.UP, aim, placePos);
    }

    private Optional<PlacementInfo> aimAndCheckPlacement(Vec3 aim, BlockPos expectedSupport, String label) {
        BlockHitResult hit = mc.level.clip(new ClipContext(mc.player.getEyePosition(), aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return Optional.empty();
        }
        if (hit.getDirection() != Direction.UP) {
            this.debugLog("Lava fail: " + label + " face not up face=" + hit.getDirection()
                    + " support=" + this.formatBlockPos(hit.getBlockPos())
                    + " aim=" + this.formatVec3(aim));
            return Optional.empty();
        }
        if (expectedSupport != null && !hit.getBlockPos().equals(expectedSupport)) {
            this.debugLog("Lava fail: support ray mismatch expected=" + this.formatBlockPos(expectedSupport)
                    + " hit=" + this.formatBlockPos(hit.getBlockPos())
                    + " aim=" + this.formatVec3(aim));
            return Optional.empty();
        }
        BlockPos supportPos = hit.getBlockPos();
        BlockPos placePos = supportPos.relative(hit.getDirection());
        if (this.isWater(placePos)) {
            this.debugLog("Lava fail: " + label + " place is water place=" + this.formatBlockPos(placePos));
            return Optional.empty();
        }
        if (!mc.level.getBlockState(placePos).canBeReplaced()) {
            this.debugLog("Lava fail: " + label + " place not replaceable place=" + this.formatBlockPos(placePos));
            return Optional.empty();
        }
        if (this.intersectsPlayer(placePos)) {
            this.debugLog("Lava fail: " + label + " place intersects self place=" + this.formatBlockPos(placePos));
            return Optional.empty();
        }
        if (!mc.level.getFluidState(placePos).isEmpty()) {
            this.debugLog("Lava fail: " + label + " place has fluid place=" + this.formatBlockPos(placePos));
            return Optional.empty();
        }
        if (!this.isValidSupport(supportPos)) {
            this.debugLog("Lava fail: " + label + " support invalid support=" + this.formatBlockPos(supportPos));
            return Optional.empty();
        }
        if (mc.player.getEyePosition().distanceToSqr(aim) > 25.0) {
            this.debugLog("Lava fail: " + label + " aim too far support=" + this.formatBlockPos(supportPos)
                    + " aim=" + this.formatVec3(aim));
            return Optional.empty();
        }
        return Optional.of(new PlacementInfo(supportPos, hit.getDirection(), aim, placePos));
    }

    private Optional<Vec3> findFaceHitPoint(BlockPos pos, Direction face) {
        for (Vec3 point : this.getFacePoints(pos, face)) {
            if (mc.player.getEyePosition().distanceToSqr(point) > 25.0) continue;
            Optional<Vec3> hit = this.raycastFace(pos, face, point);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }

    private Optional<Vec3> raycastFace(BlockPos pos, Direction face, Vec3 aim) {
        if (mc.player == null || mc.level == null) {
            return Optional.empty();
        }
        Vec3 offset = this.offsetTowardsFace(aim, face);
        BlockHitResult hit = mc.level.clip(new ClipContext(mc.player.getEyePosition(), offset, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return Optional.empty();
        }
        return hit.getBlockPos().equals(pos) && hit.getDirection() == face ? Optional.of(hit.getLocation()) : Optional.empty();
    }

    private Vec3 offsetTowardsFace(Vec3 point, Direction face) {
        double step = 0.01;
        return point.add(-face.getStepX() * step, -face.getStepY() * step, -face.getStepZ() * step);
    }

    private List<Vec3> getFacePoints(BlockPos pos, Direction face) {
        ArrayList<Vec3> list = new ArrayList<>();
        this.findFaceCenter(pos, face).ifPresent(list::add);
        AABB faceBox = this.getFaceBoundingBox(pos, face);
        if (faceBox == null) {
            return list;
        }
        double[] fractions = {0.5, 0.25, 0.75};
        for (double u : fractions) {
            for (double v : fractions) {
                Vec3 point = switch (face) {
                    case UP -> new Vec3(pos.getX() + lerp(faceBox.minX, faceBox.maxX, u), pos.getY() + faceBox.maxY, pos.getZ() + lerp(faceBox.minZ, faceBox.maxZ, v));
                    case DOWN -> new Vec3(pos.getX() + lerp(faceBox.minX, faceBox.maxX, u), pos.getY() + faceBox.minY, pos.getZ() + lerp(faceBox.minZ, faceBox.maxZ, v));
                    case EAST -> new Vec3(pos.getX() + faceBox.maxX, pos.getY() + lerp(faceBox.minY, faceBox.maxY, u), pos.getZ() + lerp(faceBox.minZ, faceBox.maxZ, v));
                    case WEST -> new Vec3(pos.getX() + faceBox.minX, pos.getY() + lerp(faceBox.minY, faceBox.maxY, u), pos.getZ() + lerp(faceBox.minZ, faceBox.maxZ, v));
                    case SOUTH -> new Vec3(pos.getX() + lerp(faceBox.minX, faceBox.maxX, u), pos.getY() + lerp(faceBox.minY, faceBox.maxY, v), pos.getZ() + faceBox.maxZ);
                    case NORTH -> new Vec3(pos.getX() + lerp(faceBox.minX, faceBox.maxX, u), pos.getY() + lerp(faceBox.minY, faceBox.maxY, v), pos.getZ() + faceBox.minZ);
                };
                this.addUniquePoint(list, point);
            }
        }
        return list;
    }

    private void addUniquePoint(List<Vec3> list, Vec3 point) {
        for (Vec3 existing : list) {
            if (existing.distanceToSqr(point) < 1.0e-6) {
                return;
            }
        }
        list.add(point);
    }

    private AABB getFaceBoundingBox(BlockPos pos, Direction face) {
        VoxelShape shape = this.getBlockShape(pos);
        if (shape.isEmpty()) {
            return null;
        }
        AABB result = null;
        double bestValue = isPositiveFace(face) ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (AABB box : shape.toAabbs()) {
            double faceCoord = switch (face) {
                case UP -> box.maxY;
                case DOWN -> box.minY;
                case EAST -> box.maxX;
                case WEST -> box.minX;
                case SOUTH -> box.maxZ;
                case NORTH -> box.minZ;
            };
            boolean better = isPositiveFace(face) ? faceCoord > bestValue : faceCoord < bestValue;
            if (better) {
                bestValue = faceCoord;
                result = box;
            }
        }
        return result;
    }

    private static boolean isPositiveFace(Direction face) {
        return face == Direction.UP || face == Direction.EAST || face == Direction.SOUTH;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private Optional<Vec3> findFaceCenter(BlockPos pos, Direction face) {
        VoxelShape shape = this.getBlockShape(pos);
        if (shape.isEmpty()) {
            return Optional.empty();
        }
        List<AABB> aabbs = shape.toAabbs();
        if (aabbs.isEmpty()) {
            return Optional.empty();
        }
        AABB chosen = null;
        double bestValue = isPositiveFace(face) ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (AABB box : aabbs) {
            double faceCoord = switch (face) {
                case UP -> box.maxY;
                case DOWN -> box.minY;
                case EAST -> box.maxX;
                case WEST -> box.minX;
                case SOUTH -> box.maxZ;
                case NORTH -> box.minZ;
            };
            boolean better = isPositiveFace(face) ? faceCoord > bestValue : faceCoord < bestValue;
            if (better) {
                bestValue = faceCoord;
                chosen = box;
            }
        }
        if (chosen == null) {
            return Optional.empty();
        }
        double cx = pos.getX() + (chosen.minX + chosen.maxX) * 0.5;
        double cy = pos.getY() + (chosen.minY + chosen.maxY) * 0.5;
        double cz = pos.getZ() + (chosen.minZ + chosen.maxZ) * 0.5;
        switch (face) {
            case UP -> cy = pos.getY() + chosen.maxY;
            case DOWN -> cy = pos.getY() + chosen.minY;
            case EAST -> cx = pos.getX() + chosen.maxX;
            case WEST -> cx = pos.getX() + chosen.minX;
            case SOUTH -> cz = pos.getZ() + chosen.maxZ;
            case NORTH -> cz = pos.getZ() + chosen.minZ;
        }
        return Optional.of(new Vec3(cx, cy, cz));
    }

    private VoxelShape getBlockShape(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        VoxelShape shape = state.getShape(mc.level, pos);
        return !shape.isEmpty() ? shape : state.getCollisionShape(mc.level, pos);
    }

    private boolean isValidSupport(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        return !state.canBeReplaced() && !this.getBlockShape(pos).isEmpty();
    }

    private boolean isPlacementValid() {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return false;
        if (this.target == null || !this.target.isAlive() || this.target.isRemoved()) return false;
        if (this.currentPlacement == null) return false;
        if (this.activePlacementType == PlacementType.LAVA) {
            if (!this.shouldPlaceLavaEx(this.target, this.isEntityInCobweb(this.target))) return false;
            if (this.intersectsPlayer(this.currentPlacement.place)) return false;
            if (this.isWater(this.currentPlacement.place)) return false;
            if (!mc.level.getBlockState(this.currentPlacement.place).canBeReplaced()) return false;
            if (!mc.level.getFluidState(this.currentPlacement.place).isEmpty()) return false;
            return this.isValidSupport(this.currentPlacement.support);
        }
        if (this.activePlacementType != PlacementType.GROUND_WEB
                && this.activePlacementType != PlacementType.WEB_EXIT_PREDICT
                && this.isEntityInCobweb(this.target)) {
            return false;
        }
        if (this.intersectsPlayer(this.currentPlacement.place)) return false;
        if (!mc.level.getBlockState(this.currentPlacement.place).canBeReplaced()) return false;
        if (!mc.level.getFluidState(this.currentPlacement.place).isEmpty()) return false;
        return this.isValidSupport(this.currentPlacement.support);
    }

    private boolean executePlacement() {
        int slot = this.activePlacementType == PlacementType.LAVA ? this.findLavaBucketSlot() : this.findCobwebSlot();
        if (slot == -1) {
            this.debugLog("Execute fail: item slot missing type=" + this.activePlacementType);
            return false;
        }
        int previousSlot = mc.player.getInventory().selected;
        mc.player.getInventory().selected = slot;
        PlayerUtil.sendCarriedItem();
        if (this.activePlacementType == PlacementType.LAVA) {
            Rotation rotation = RotationUtil.exactRotation(mc.player.getEyePosition(), this.currentPlacement.aim);
            targetRotation = rotation;
            this.debugLog("Lava place use bucket slot=" + slot
                    + " place=" + this.formatBlockPos(this.currentPlacement.place)
                    + " support=" + this.formatBlockPos(this.currentPlacement.support));
            this.useBucket(rotation);
        } else {
            BlockHitResult hit = new BlockHitResult(this.currentPlacement.aim, this.currentPlacement.face, this.currentPlacement.support, false);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
        mc.player.getInventory().selected = previousSlot;
        PlayerUtil.sendCarriedItem();
        return true;
    }

    private boolean useBucket(Rotation rotation) {
        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();
        mc.player.setYRot(rotation.getYaw());
        mc.player.setXRot(rotation.getPitch());
        PacketUtil.sendQueued(new ServerboundMovePlayerPacket.Rot(rotation.getYaw(), rotation.getPitch(), mc.player.onGround()));
        InteractionResult result = mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        this.debugLog("Bucket use result=" + result + " yaw=" + rotation.getYaw() + " pitch=" + rotation.getPitch());
        if (result.consumesAction()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        return result.consumesAction();
    }

    private void doGroundWebBreak() {
        if (mc.player == null || mc.level == null || mc.gameMode == null || this.groundWebFootPos == null) {
            this.reset();
            return;
        }
        if (!mc.level.getBlockState(this.groundWebFootPos).is(Blocks.COBWEB)) {
            this.debugLog("Ground web break done foot=" + this.formatBlockPos(this.groundWebFootPos));
            if (!this.tryLavaAfterBreak()) {
                this.reset();
            }
            return;
        }
        int swordSlot = this.findSwordSlot();
        if (swordSlot == -1) {
            this.debugLog("Ground web break abort: no sword foot=" + this.formatBlockPos(this.groundWebFootPos));
            this.reset();
            return;
        }
        mc.player.getInventory().selected = swordSlot;
        PlayerUtil.sendCarriedItem();
        targetRotation = RotationUtil.exactRotation(mc.player.getEyePosition(), Vec3.atCenterOf(this.groundWebFootPos));
        if (!this.groundWebBreakStarted) {
            mc.gameMode.startDestroyBlock(this.groundWebFootPos, Direction.UP);
            this.groundWebBreakStarted = true;
        } else {
            mc.gameMode.continueDestroyBlock(this.groundWebFootPos, Direction.UP);
        }
        mc.player.swing(InteractionHand.MAIN_HAND);
        if (++this.groundWebBreakTicks >= 24) {
            this.debugLog("Ground web break abort: timeout foot=" + this.formatBlockPos(this.groundWebFootPos));
            this.reset();
        }
    }

    private boolean tryLavaAfterBreak() {
        if (this.target == null || !this.lavaSetting.getValue() || this.findLavaBucketSlot() == -1) return false;
        if (!this.shouldPlaceLavaEx(this.target, true)) return false;
        Optional<PlacementInfo> placement = this.findLavaSourcePlacement(this.target);
        if (placement.isEmpty()) {
            this.debugLog("Ground web lava skipped: no lava placement target=" + this.target.getName().getString());
            return false;
        }
        this.lavaReapplyCount = 0;
        this.applyLavaPlacement(placement.get());
        this.currentGroundWebData = null;
        this.currentGroundWebPhase = GroundWebPhase.IDLE;
        this.groundWebFootPos = null;
        this.groundWebBreakTicks = 0;
        this.groundWebBreakStarted = false;
        this.placementPhase = Phase.PLACE;
        this.debugLog("Ground web lava queued target=" + this.target.getName().getString()
                + " place=" + this.formatBlockPos(this.currentPlacement.place)
                + " support=" + this.formatBlockPos(this.currentPlacement.support)
                + " face=" + this.currentPlacement.face
                + " aim=" + this.formatVec3(this.currentPlacement.aim));
        return true;
    }

    private void doLavaRetrieve() {
        if (mc.player == null || mc.level == null || mc.gameMode == null || this.currentPlacement == null) {
            this.reset();
            return;
        }
        this.retrieveTicks++;
        BlockPos expectedSource = this.currentPlacement.place;
        BlockPos actualSource = this.findNearbyLavaSource(expectedSource);
        BlockPos probe = actualSource != null ? actualSource : expectedSource;
        boolean flowingLava = this.hasNearbyFlowingLava(probe);
        boolean targetHasWaterBucket = this.targetHasWaterBucket(this.target);
        boolean timedOut = this.retrieveTicks >= this.getLavaTickDelay();
        boolean readyToRetrieve = targetHasWaterBucket || flowingLava || timedOut;
        boolean shouldAct = !this.lavaWaitWaterBucketSetting.getValue() || readyToRetrieve;
        if (this.waitTicks > 0 && !readyToRetrieve) {
            this.waitTicks--;
            return;
        }
        if (actualSource != null) {
            this.lavaPlaced = true;
            this.sourceWaitCount = 0;
            this.targetBlockPos = actualSource;
            targetRotation = RotationUtil.rotationToBlock(actualSource, 0.0f);
            if (!shouldAct) {
                this.debugLog("Lava retrieve wait target water bucket ticks=" + this.retrieveTicks + "/" + this.getLavaTickDelay()
                        + " source=" + this.formatBlockPos(actualSource));
                this.waitTicks = 1;
                return;
            }
            int emptyBucketSlot = this.findEmptyBucketSlot();
            if (emptyBucketSlot == -1) {
                if (++this.retrieveAttempts >= 8) {
                    this.debugLog("Lava retrieve abort: no empty bucket source=" + this.formatBlockPos(actualSource));
                    this.reset();
                    return;
                }
                this.debugLog("Lava retrieve wait bucket attempt=" + this.retrieveAttempts
                        + " source=" + this.formatBlockPos(actualSource)
                        + " lavaBucketSlot=" + this.findLavaBucketSlot());
                this.waitTicks = 1;
                return;
            }
            int attempt = this.retrieveAttempts + 1;
            this.debugLog("Lava retrieve use bucket attempt=" + attempt
                    + " bucketSlot=" + emptyBucketSlot
                    + " source=" + this.formatBlockPos(actualSource)
                    + " reason=" + this.getRetrieveReason(targetHasWaterBucket, flowingLava, timedOut));
            int previousSlot = mc.player.getInventory().selected;
            mc.player.getInventory().selected = emptyBucketSlot;
            PlayerUtil.sendCarriedItem();
            this.useBucket(targetRotation);
            mc.player.getInventory().selected = previousSlot;
            PlayerUtil.sendCarriedItem();
            if (++this.retrieveAttempts >= 8) {
                this.debugLog("Lava retrieve abort: max attempts source=" + this.formatBlockPos(actualSource));
                this.reset();
            } else {
                this.waitTicks = 2;
            }
            return;
        }
        if (this.lavaPlaced) {
            this.debugLog("Lava retrieve done: source disappeared expected=" + this.formatBlockPos(expectedSource));
            if (!this.tryReapplyLava()) {
                this.reset();
            }
            return;
        }
        if (++this.sourceWaitCount >= 8) {
            this.debugLog("Lava retrieve abort: source not found expected=" + this.formatBlockPos(expectedSource)
                    + " support=" + this.formatBlockPos(this.currentPlacement.support));
            this.reset();
            return;
        }
        this.debugLog("Lava retrieve wait source attempt=" + this.sourceWaitCount
                + " expected=" + this.formatBlockPos(expectedSource)
                + " block=" + mc.level.getBlockState(expectedSource).getBlock());
        if (!this.retriedLavaPlace && this.sourceWaitCount >= 2 && this.findLavaBucketSlot() != -1 && this.isPlacementValid()) {
            this.debugLog("Lava retry place after missing source expected=" + this.formatBlockPos(expectedSource));
            this.retriedLavaPlace = true;
            this.executePlacement();
        }
        this.waitTicks = 1;
    }

    private boolean isLavaSource(BlockPos pos) {
        return mc.level.getBlockState(pos).is(Blocks.LAVA) && mc.level.getFluidState(pos).isSource();
    }

    private boolean hasNearbyFlowingLava(BlockPos center) {
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (this.isFlowingLava(center.offset(x, y, z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean tryReapplyLava() {
        if (this.target == null || this.target.isRemoved() || !this.target.isAlive()) return false;
        if (this.target.isOnFire()) return false;
        if (this.lavaReapplyCount >= 1) return false;
        if (this.findLavaBucketSlot() == -1) return false;
        if (!this.shouldPlaceLavaEx(this.target, true)) return false;
        Optional<PlacementInfo> placement = this.findLavaSourcePlacement(this.target);
        if (placement.isEmpty()) {
            this.debugLog("Lava reapply skipped: no placement target=" + this.target.getName().getString());
            return false;
        }
        this.lavaReapplyCount++;
        this.applyLavaPlacement(placement.get());
        this.placementPhase = Phase.PLACE;
        this.debugLog("Lava reapply queued target=" + this.target.getName().getString()
                + " attempt=" + this.lavaReapplyCount
                + " place=" + this.formatBlockPos(this.currentPlacement.place)
                + " support=" + this.formatBlockPos(this.currentPlacement.support)
                + " face=" + this.currentPlacement.face
                + " aim=" + this.formatVec3(this.currentPlacement.aim));
        return true;
    }

    private int getLavaTickDelay() {
        return mc.level == null ? 9 : Math.max(1, Fluids.LAVA.getTickDelay(mc.level) - 1);
    }

    private boolean isFlowingLava(BlockPos pos) {
        FluidState fluidState = mc.level.getFluidState(pos);
        Fluid fluid = fluidState.getType();
        return (fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA) && !fluidState.isSource();
    }

    private boolean targetHasWaterBucket(Entity entity) {
        if (!(entity instanceof Player player)) return false;
        return player.getMainHandItem().getItem() == Items.WATER_BUCKET
                || player.getOffhandItem().getItem() == Items.WATER_BUCKET;
    }

    private String getRetrieveReason(boolean targetHasWaterBucket, boolean flowing, boolean timedOut) {
        if (flowing) return "flowing";
        if (targetHasWaterBucket) return "target_water_bucket";
        return timedOut ? "before_flow" : "normal";
    }

    private boolean isWater(BlockPos pos) {
        Fluid fluid = mc.level.getFluidState(pos).getType();
        return mc.level.getBlockState(pos).is(Blocks.WATER) || fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER;
    }

    private boolean intersectsPlayer(BlockPos pos) {
        return mc.player != null && mc.player.getBoundingBox().intersects(new AABB(pos));
    }

    private boolean isEntityInCobweb(Entity entity) {
        return entity != null && mc.level != null && this.hasAnyCobweb(entity.getBoundingBox());
    }

    private boolean isAllCobweb(AABB aabb) {
        if (mc.level == null) return false;
        boolean any = false;
        int minX = (int) Math.floor(aabb.minX);
        int maxX = (int) Math.floor(aabb.maxX);
        int minY = (int) Math.floor(aabb.minY);
        int maxY = (int) Math.floor(aabb.maxY);
        int minZ = (int) Math.floor(aabb.minZ);
        int maxZ = (int) Math.floor(aabb.maxZ);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!aabb.intersects(new AABB(pos))) continue;
                    if (!mc.level.getBlockState(pos).is(Blocks.COBWEB)) return false;
                    any = true;
                }
            }
        }
        return any;
    }

    private boolean hasAnyCobweb(AABB aabb) {
        if (mc.level == null) return false;
        int minX = (int) Math.floor(aabb.minX);
        int maxX = (int) Math.floor(aabb.maxX);
        int minY = (int) Math.floor(aabb.minY);
        int maxY = (int) Math.floor(aabb.maxY);
        int minZ = (int) Math.floor(aabb.minZ);
        int maxZ = (int) Math.floor(aabb.maxZ);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (mc.level.getBlockState(pos).is(Blocks.COBWEB) && aabb.intersects(new AABB(pos))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private BlockPos findNearbyLavaSource(BlockPos center) {
        if (this.isLavaSource(center)) {
            return center;
        }
        BlockPos best = null;
        double bestDistanceSq = Double.POSITIVE_INFINITY;
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    if (!this.isLavaSource(pos)) continue;
                    double distanceSq = Vec3.atCenterOf(center).distanceToSqr(Vec3.atCenterOf(pos));
                    if (distanceSq < bestDistanceSq) {
                        best = pos;
                        bestDistanceSq = distanceSq;
                    }
                }
            }
        }
        return best;
    }

    private void debugLog(String message) {
        if (this.debugSetting.getValue()) {
            ChatUtil.print("[AutoWebPlace] " + message);
        }
    }

    private String formatBlockPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private String formatVec3(Vec3 vec) {
        return String.format("%.2f,%.2f,%.2f", vec.x, vec.y, vec.z);
    }

    private int findCobwebSlot() {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == Items.COBWEB) {
                return slot;
            }
        }
        return -1;
    }

    private int findLavaBucketSlot() {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == Items.LAVA_BUCKET) {
                return slot;
            }
        }
        return -1;
    }

    private int findEmptyBucketSlot() {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == Items.BUCKET) {
                return slot;
            }
        }
        return -1;
    }

    private int findSwordSlot() {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof SwordItem) {
                return slot;
            }
        }
        return -1;
    }

    private boolean isRotationBlocked() {
        if (Helper.INSTANCE != null && Helper.INSTANCE.isEnabled() && Helper.targetRotation != null) return true;
        if (AntiWeb.INSTANCE != null && AntiWeb.INSTANCE.isEnabled()
                && AntiWeb.currentPhase != AntiWeb.Phase.IDLE && AntiWeb.targetRotation != null) {
            return true;
        }
        if (AntiTNT.INSTANCE != null && AntiTNT.INSTANCE.isEnabled() && AntiTNT.targetRotation != null) return true;
        return MidPearl.INSTANCE != null && MidPearl.INSTANCE.isEnabled() && MidPearl.targetRotation != null;
    }

    private boolean isPhaseActive() {
        return this.placementPhase == Phase.LAVA_RETRIEVE
                || this.placementPhase == Phase.GROUND_WEB_BREAK
                || this.currentGroundWebPhase != GroundWebPhase.IDLE
                || this.activePlacementType == PlacementType.GROUND_WEB;
    }

    private enum Phase {
        IDLE,
        PLACE,
        LAVA_RETRIEVE,
        GROUND_WEB_BREAK
    }

    private enum PlacementType {
        COBWEB,
        WEB_EXIT_PREDICT,
        GROUND_WEB,
        LAVA
    }

    private enum GroundWebPhase {
        IDLE,
        FOOT_PLACED,
        HEAD_PLACED
    }

    private static final class PlacementInfo {
        final BlockPos support;
        final Direction face;
        final Vec3 aim;
        final BlockPos place;

        PlacementInfo(BlockPos support, Direction face, Vec3 aim, BlockPos place) {
            this.support = support;
            this.face = face;
            this.aim = aim;
            this.place = place;
        }
    }

    private record PlacementTarget(Entity target, PlacementInfo placement, PlacementType type, GroundWebData groundWebData) {
    }

    private record GroundWebData(BlockPos footPos, BlockPos headPos, PlacementInfo footPlacement, PlacementInfo headPlacement) {
    }

    private record PredictionTick(int ticks, BlockPos footPos, BlockPos headPos) {
    }
}
