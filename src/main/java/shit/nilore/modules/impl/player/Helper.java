package shit.nilore.modules.impl.player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import shit.nilore.event.impl.MotionEvent;
import shit.nilore.event.impl.PreMotionEvent;
import shit.nilore.event.impl.RenderEvent;
import shit.nilore.event.impl.TickEvent;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.modules.impl.movement.FireballBlink;
import shit.nilore.modules.impl.player.helper.HelperBase;
import shit.nilore.modules.impl.player.helper.impl.BlockLava;
import shit.nilore.modules.impl.player.helper.impl.BlockWater;
import shit.nilore.modules.impl.player.helper.impl.ExtinguishFire;
import shit.nilore.modules.impl.player.helper.impl.SelfExtinguish;
import shit.nilore.settings.impl.BooleanSetting;
import shit.nilore.settings.impl.MultiSelectSetting;
import shit.nilore.settings.impl.NumberSetting;
import shit.nilore.utils.game.RotationUtil;
import shit.nilore.utils.rotation.Rotation;
import shit.nilore.utils.rotation.RotationHandler;
import shit.nilore.event.EventTarget;

public class Helper extends Module {
    public static Helper INSTANCE;
    public static Rotation targetRotation;

    private static final Map<BlockPos, Integer> waterPlacements = new HashMap<>();
    private static final Map<BlockPos, Integer> lavaPlacements = new HashMap<>();
    private static FluidTracker fluidTracker;

    private final MultiSelectSetting modulesSetting = new MultiSelectSetting(
            "Mode", "Self Extinguish", "Extinguish Fire", "Block Lava", "Block Water")
            .withDefaults("Self Extinguish", "Extinguish Fire", "Block Lava", "Block Water");
    private final BooleanSetting smoothRotationSetting = new BooleanSetting("Legit", false);
    private final NumberSetting rotationSpeedSetting = new NumberSetting(
            "Speed", 45, 2, 180, 1, this.smoothRotationSetting::getValue);
    private final NumberSetting rotationFovSetting = new NumberSetting(
            "FOV", 90, 30, 180, 1, this.smoothRotationSetting::getValue);

    private final List<HelperBase> subModuleList = new ArrayList<>();
    private Rotation lastTargetRotation;

    public Helper() {
        super("Helper", Category.PLAYER);
        INSTANCE = this;
        this.subModuleList.add(new SelfExtinguish());
        this.subModuleList.add(new ExtinguishFire());
        this.subModuleList.add(new BlockLava());
        this.subModuleList.add(new BlockWater());
    }

    @Override
    protected void onEnable() {
        this.subModuleList.forEach(HelperBase::onEnable);
    }

    @Override
    protected void onDisable() {
        targetRotation = null;
        this.lastTargetRotation = null;
        waterPlacements.clear();
        lavaPlacements.clear();
        fluidTracker = null;
        this.subModuleList.forEach(HelperBase::onDisable);
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (FireballBlink.INSTANCE != null && FireballBlink.INSTANCE.isEnabled()) {
            return;
        }
        processBucketTracker();
        cleanupPlacementMaps();
        this.subModuleList.stream()
                .filter(sub -> this.modulesSetting.isSelected(sub.getName()))
                .forEach(sub -> sub.onTick(event));
    }

    @EventTarget
    public void onMotion(MotionEvent event) {
        if (FireballBlink.INSTANCE != null && FireballBlink.INSTANCE.isEnabled()) {
            return;
        }
        targetRotation = null;
        this.subModuleList.stream()
                .filter(sub -> this.modulesSetting.isSelected(sub.getName()))
                .forEach(sub -> {
                    sub.onMotion(event);
                    if (sub.isActive() && sub.getTargetRotation() != null) {
                        targetRotation = sub.getTargetRotation();
                    }
                });
        if (this.smoothRotationSetting.getValue() && targetRotation != null && event.isPost()) {
            this.lastTargetRotation = targetRotation;
            Rotation prev = RotationHandler.prevRotation;
            if (prev != null) {
                targetRotation = RotationUtil.smoothRotation(prev, targetRotation,
                        this.rotationSpeedSetting.getValue().doubleValue());
            }
        } else if (event.isPost()) {
            this.lastTargetRotation = null;
        }
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (FireballBlink.INSTANCE != null && FireballBlink.INSTANCE.isEnabled()) {
            return;
        }
        this.subModuleList.stream()
                .filter(sub -> this.modulesSetting.isSelected(sub.getName()))
                .forEach(sub -> sub.onRender(event));
    }

    @EventTarget
    public void onPreMotion(PreMotionEvent event) {
        if (FireballBlink.INSTANCE != null && FireballBlink.INSTANCE.isEnabled()) {
            return;
        }
        updateBucketTracker();
        this.subModuleList.stream()
                .filter(sub -> this.modulesSetting.isSelected(sub.getName()))
                .forEach(sub -> sub.onPreMotion(event));
    }

    public boolean hasTargetRotation() {
        if (FireballBlink.INSTANCE != null && FireballBlink.INSTANCE.isEnabled()) {
            return false;
        }
        return targetRotation != null;
    }

    public static boolean isRotationNearTarget() {
        if (INSTANCE == null || !INSTANCE.smoothRotationSetting.getValue()) {
            return false;
        }
        if (INSTANCE.lastTargetRotation == null) {
            return false;
        }
        Rotation prev = RotationHandler.prevRotation;
        if (prev == null) {
            return false;
        }
        return INSTANCE.lastTargetRotation.distanceTo(prev) > 2.0;
    }

    public static boolean isPositionInFov(Vec3 pos) {
        if (INSTANCE == null || !INSTANCE.smoothRotationSetting.getValue()) {
            return true;
        }
        if (mc.player == null) {
            return true;
        }
        Rotation current = new Rotation(mc.player.getYRot(), mc.player.getXRot());
        Rotation target = RotationUtil.exactRotation(mc.player.getEyePosition(), pos);
        return current.distanceTo(target) <= INSTANCE.rotationFovSetting.getValue().doubleValue() / 2.0;
    }

    public static void markWaterPlaced(BlockPos pos) {
        addToPlacementMap(waterPlacements, pos);
    }

    public static void markLavaPlaced(BlockPos pos) {
        addToPlacementMap(lavaPlacements, pos);
    }

    public static void removeWaterPlacement(BlockPos pos) {
        if (pos != null) {
            waterPlacements.remove(pos);
        }
    }

    public static void removeLavaPlacement(BlockPos pos) {
        if (pos != null) {
            lavaPlacements.remove(pos);
        }
    }

    public static boolean hasWaterPlacement(BlockPos pos) {
        return pos != null && waterPlacements.containsKey(pos);
    }

    public static boolean hasLavaPlacement(BlockPos pos) {
        return pos != null && lavaPlacements.containsKey(pos);
    }

    private static void updateBucketTracker() {
        if (mc.player == null || mc.level == null) {
            return;
        }
        Item item = mc.player.getMainHandItem().getItem();
        if (item != Items.WATER_BUCKET && item != Items.LAVA_BUCKET) {
            item = mc.player.getOffhandItem().getItem();
        }
        if (item == Items.WATER_BUCKET || item == Items.LAVA_BUCKET) {
            Block block = item == Items.WATER_BUCKET ? Blocks.WATER : Blocks.LAVA;
            fluidTracker = new FluidTracker(block, mc.player.blockPosition(),
                    findFluidBlocks(mc.player.blockPosition(), block), 20);
        }
    }

    private static void processBucketTracker() {
        if (fluidTracker == null || mc.level == null) {
            return;
        }
        Set<BlockPos> newSources = findFluidBlocks(fluidTracker.sourcePos, fluidTracker.fluidBlock);
        newSources.removeAll(fluidTracker.connectedPositions);
        newSources.forEach(pos -> {
            if (fluidTracker.fluidBlock == Blocks.WATER) {
                markWaterPlaced(pos);
            } else {
                markLavaPlaced(pos);
            }
        });
        if (!newSources.isEmpty() || --fluidTracker.tickCount <= 0) {
            fluidTracker = null;
        }
    }

    private static Set<BlockPos> findFluidBlocks(BlockPos center, Block block) {
        HashSet<BlockPos> set = new HashSet<>();
        if (mc.level == null || center == null) {
            return set;
        }
        for (int dx = -6; dx <= 6; ++dx) {
            for (int dy = -5; dy <= 5; ++dy) {
                for (int dz = -6; dz <= 6; ++dz) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (isFluidSourceAt(pos, block)) {
                        set.add(pos.immutable());
                    }
                }
            }
        }
        return set;
    }

    private static void addToPlacementMap(Map<BlockPos, Integer> map, BlockPos pos) {
        if (pos != null) {
            map.put(pos.immutable(), 20);
        }
    }

    private static void cleanupPlacementMaps() {
        if (mc.level == null) {
            waterPlacements.clear();
            lavaPlacements.clear();
            return;
        }
        updatePlacementMap(waterPlacements, Blocks.WATER);
        updatePlacementMap(lavaPlacements, Blocks.LAVA);
    }

    private static void updatePlacementMap(Map<BlockPos, Integer> map, Block block) {
        map.entrySet().removeIf(entry -> {
            if (isFluidSourceAt(entry.getKey(), block)) {
                entry.setValue(0);
                return false;
            }
            int remaining = entry.getValue();
            if (remaining <= 0) {
                return true;
            }
            entry.setValue(remaining - 1);
            return false;
        });
    }

    private static boolean isFluidSourceAt(BlockPos pos, Block block) {
        return mc.level.getBlockState(pos).is(block) && mc.level.getFluidState(pos).isSource();
    }

    private static final class FluidTracker {
        final Block fluidBlock;
        final BlockPos sourcePos;
        final Set<BlockPos> connectedPositions;
        int tickCount;

        FluidTracker(Block fluidBlock, BlockPos sourcePos, Set<BlockPos> connectedPositions, int tickCount) {
            this.fluidBlock = fluidBlock;
            this.sourcePos = sourcePos;
            this.connectedPositions = connectedPositions;
            this.tickCount = tickCount;
        }
    }
}
