package shit.lizz.modules.impl.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemNameBlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import org.apache.commons.lang3.tuple.Pair;
import shit.lizz.event.impl.MotionEvent;
import shit.lizz.event.impl.PacketEvent;
import shit.lizz.event.impl.SprintEvent;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.modules.impl.movement.GuiMove;
import shit.lizz.modules.impl.movement.Scaffold;
import shit.lizz.settings.impl.BooleanSetting;
import shit.lizz.settings.impl.ModeSetting;
import shit.lizz.settings.impl.NumberSetting;
import shit.lizz.utils.animation.Timer;
import shit.lizz.utils.game.BlockUtil;
import shit.lizz.utils.game.ItemUtil;
import shit.lizz.utils.game.MovementUtil;
import shit.lizz.utils.misc.PacketUtil;
import shit.lizz.event.EventTarget;

public class InventoryManager
extends Module {
    public static InventoryManager INSTANCE;
    private final NumberSetting actionDelaySetting = new NumberSetting("Delay", 200, 0, 500, 10);
    private final NumberSetting sprintDelayTicksSetting = new NumberSetting("Open Delay", 2, 0, 10, 1);
    private final NumberSetting dropDelaySetting = new NumberSetting("Drop Delay", 200, 0, 500, 10);
    private final BooleanSetting autoArmorSetting = new BooleanSetting("Auto Armor", true);
    private final BooleanSetting throwItemsSetting = new BooleanSetting("Throw Items", true);
    private final ModeSetting offhandItemSetting = new ModeSetting("Offhand Items", "Golden Apple", "Fishing Rod", "None").withDefault("Projectile");
    private final ModeSetting bowPrioritySetting = new ModeSetting("Bow Priority", "Crossbow", "Punch Bow").withDefault("Crossbow");
    private final BooleanSetting inventoryOnlySetting = new BooleanSetting("Inventory Only", true);
    private final BooleanSetting fastThrowSetting = new BooleanSetting("Fast Throw", false);
    private final NumberSetting maxEggsSnowballsSetting = new NumberSetting("Max Eggs & Snowballs Size", 64, 16, 256, 16);
    public final NumberSetting maxBlockSizeSetting = new NumberSetting("Max Block Size", 256, 64, 512, 64);
    public final BooleanSetting functionalBlocksFix = new BooleanSetting("Functional Blocks Fix", true);
    private final NumberSetting maxFoodSizeSetting = new NumberSetting("Max Food Size", 128, 32, 256, 32);
    private final NumberSetting maxRodSizeSetting = new NumberSetting("Max Rod Size", 1, 1, 16, 1);
    private final NumberSetting swordSlotSetting = new NumberSetting("Sword Slot", 0, 0, 9, 1);
    private final NumberSetting blockSlotSetting = new NumberSetting("Block Slot", 0, 0, 9, 1);
    private final NumberSetting axeSlotSetting = new NumberSetting("Axe Slot", 0, 0, 9, 1);
    private final NumberSetting pickaxeSlotSetting = new NumberSetting("Pickaxe Slot", 0, 0, 9, 1);
    private final NumberSetting bowSlotSetting = new NumberSetting("Bow Slot", 0, 0, 9, 1);
    private final NumberSetting waterBucketSlotSetting = new NumberSetting("Water Bucket Slot", 0, 0, 9, 1);
    private final NumberSetting pearlSlotSetting = new NumberSetting("Ender Pearl Slot", 0, 0, 9, 1);
    private final NumberSetting goldenAppleSlotSetting = new NumberSetting("Golden Apple Slot", 0, 0, 9, 1);
    private final NumberSetting eggsSnowballsSlotSetting = new NumberSetting("Eggs & Snowballs Slot", 0, 0, 9, 1);
    private final NumberSetting slimeBallSlotSetting = new NumberSetting("Slime Ball Slot", 0, 0, 9, 1);
    private final NumberSetting crystalSlotSetting = new NumberSetting("Crystal Slot", 0, 0, 9, 1);
    private static final Timer actionTimer;
    private boolean didInventoryAction = false;
    private boolean pendingOffhandPlace = false;
    private int idleTicks = 0;
    private int sprintWaitTicks = 0;
    public static boolean isPerformingAction;
    private boolean wasSprinting = false;
    private boolean skipNextTick = false;
    private boolean justClosedInventory = false;
    private final Queue<Packet<ServerGamePacketListener>> pendingPackets = new ConcurrentLinkedQueue<>();
    private int sprintDelayTicks = 0;

    public InventoryManager() {
        super("InventoryManager", Category.PLAYER, 66);
        INSTANCE = this;
    }

    @Override
    protected void onDisable() {
        this.sprintDelayTicks = 0;
        isPerformingAction = false;
        this.skipNextTick = false;
        this.justClosedInventory = false;
        this.wasSprinting = false;
        super.onDisable();
    }

    @EventTarget
    public void onSprint(SprintEvent sprintEvent) {
        if (!this.inventoryOnlySetting.getValue() && GuiMove.INSTANCE.isEnabled() && (!this.pendingPackets.isEmpty() || isPerformingAction) && mc.player != null) {
            mc.options.keySprint.setDown(false);
            mc.player.setSprinting(false);
        }
    }

    /*
     * Enabled force condition propagation
     * Lifted jumps to return sites
     */
    @EventTarget
    public void onPacket(PacketEvent packetEvent) {
        Packet<?> packet = packetEvent.getPacket();
        if (!packetEvent.isIncomingRaw()) return;
        if (mc.player == null) return;
        if (mc.getConnection() == null) return;
        if (packetEvent.getPacket() instanceof ServerboundContainerClosePacket) {
            this.didInventoryAction = false;
            this.sprintWaitTicks = 0;
        }
        if (!GuiMove.INSTANCE.isEnabled()) {
            if (!this.didInventoryAction) return;
            if (this.inventoryOnlySetting.getValue() != false) return;
            if (packetEvent.getPacket() instanceof ServerboundMovePlayerPacket) {
                if (!MovementUtil.isInputActive()) return;
                mc.getConnection().send(new ServerboundContainerClosePacket(mc.player.inventoryMenu.containerId));
                return;
            } else {
                if (!(packetEvent.getPacket() instanceof ServerboundUseItemOnPacket || packetEvent.getPacket() instanceof ServerboundUseItemPacket || packetEvent.getPacket() instanceof ServerboundInteractPacket)) {
                    if (!(packetEvent.getPacket() instanceof ServerboundPlayerActionPacket)) return;
                }
                mc.getConnection().send(new ServerboundContainerClosePacket(mc.player.inventoryMenu.containerId));
            }
        } else {
            Screen screen;
            AbstractContainerScreen containerScreen;
            ServerboundPlayerCommandPacket commandPacket;
            boolean hasPendingPackets = !this.pendingPackets.isEmpty();
            if (packet instanceof ServerboundPlayerCommandPacket) {
                commandPacket = (ServerboundPlayerCommandPacket)packet;
                if (commandPacket.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING) {
                    this.wasSprinting = true;
                } else if (commandPacket.getAction() == ServerboundPlayerCommandPacket.Action.STOP_SPRINTING) {
                    this.wasSprinting = false;
                }
            }
            if (isPerformingAction && packet instanceof ServerboundPlayerInputPacket inputPacket) {
                if (mc.player.isSprinting()) {
                    mc.player.setSprinting(false);
                    packetEvent.setCancelled(true);
                    PacketUtil.sendQueued(new ServerboundPlayerInputPacket(inputPacket.getXxa(), inputPacket.getZza(), inputPacket.isJumping(), inputPacket.isShiftKeyDown()));
                    return;
                }
            }
            if ((screen = mc.screen) instanceof AbstractContainerScreen) {
                containerScreen = (AbstractContainerScreen)screen;
                if (containerScreen.getMenu().containerId != mc.player.inventoryMenu.containerId) {
                    return;
                }
            }
            boolean shouldSkip = false;
            if (shouldSkip) return;
            if (!(packet instanceof ServerboundContainerClickPacket)) {
                if (!(packet instanceof ServerboundContainerClosePacket)) return;
            }
            packetEvent.setCancelled(true);
            this.pendingPackets.add((Packet<ServerGamePacketListener>) packet);
        }
    }

    @EventTarget
    public void onMotion(MotionEvent motionEvent) {
        if (motionEvent.isPost() && !this.inventoryOnlySetting.getValue() && GuiMove.INSTANCE.isEnabled() && mc.player != null) {
            boolean hasPendingPackets = !this.pendingPackets.isEmpty();
            if (hasPendingPackets) {
                if (this.wasSprinting || mc.player.isSprinting()) {
                    this.skipNextTick = true;
                    this.sprintDelayTicks = 2 + this.sprintDelayTicksSetting.getValue().intValue();
                    return;
                }
                if (this.sprintDelayTicks > 0) {
                    --this.sprintDelayTicks;
                    return;
                }
                while (!this.pendingPackets.isEmpty()) {
                    PacketUtil.sendQueued((Packet<ServerGamePacketListener>) this.pendingPackets.poll());
                }
                PacketUtil.sendQueued(new ServerboundContainerClosePacket(mc.player.inventoryMenu.containerId));
                boolean jumpDown = mc.options.keyJump.isDown();
                boolean shiftDown = mc.options.keyShift.isDown();
                this.justClosedInventory = true;
            } else {
                this.sprintDelayTicks = 0;
                if (this.justClosedInventory) {
                    boolean jumpDown = mc.options.keyJump.isDown();
                    boolean shiftDown = mc.options.keyShift.isDown();
                    this.justClosedInventory = false;
                }
            }
        }
    }

    private boolean validateSlotConfig() {
        ArrayList<Pair<Boolean, NumberSetting>> slotEntries = new ArrayList<>();
        slotEntries.add(Pair.of(this.swordSlotSetting.getValue().intValue() != 0, this.swordSlotSetting));
        slotEntries.add(Pair.of(this.axeSlotSetting.getValue().intValue() != 0, this.axeSlotSetting));
        slotEntries.add(Pair.of(this.pickaxeSlotSetting.getValue().intValue() != 0, this.pickaxeSlotSetting));
        slotEntries.add(Pair.of(this.bowSlotSetting.getValue().intValue() != 0, this.bowSlotSetting));
        slotEntries.add(Pair.of(this.waterBucketSlotSetting.getValue().intValue() != 0, this.waterBucketSlotSetting));
        slotEntries.add(Pair.of(this.pearlSlotSetting.getValue().intValue() != 0, this.pearlSlotSetting));
        slotEntries.add(Pair.of(this.slimeBallSlotSetting.getValue().intValue() != 0, this.slimeBallSlotSetting));
        slotEntries.add(Pair.of(this.crystalSlotSetting.getValue().intValue() != 0, this.crystalSlotSetting));
        slotEntries.add(Pair.of(this.eggsSnowballsSlotSetting.getValue().intValue() != 0, this.eggsSnowballsSlotSetting));
        if (!"Golden Apple".equals(this.offhandItemSetting.getValue())) {
            slotEntries.add(Pair.of(this.goldenAppleSlotSetting.getValue().intValue() != 0, this.goldenAppleSlotSetting));
        }
        if (!"Block".equals(this.offhandItemSetting.getValue())) {
            slotEntries.add(Pair.of(this.blockSlotSetting.getValue().intValue() != 0, this.blockSlotSetting));
        }
        HashSet<Integer> usedSlots = new HashSet<>();
        for (Pair<Boolean, NumberSetting> entry : slotEntries) {
            if (!entry.getKey()) continue;
            int slot = entry.getValue().getValue().intValue() - 1;
            if (!usedSlots.contains(slot)) {
                usedSlots.add(slot);
                continue;
            }
            return false;
        }
        return true;
    }

    @EventTarget
    public void onMotionManage(MotionEvent motionEvent) {
        if (motionEvent.isPost() && mc.player != null && mc.getConnection() != null && mc.gameMode != null) {
            // Sync functional blocks flag to ItemUtil
            ItemUtil.excludeFunctionalBlocks = this.functionalBlocksFix.getValue();
            ContainerScreen containerScreen;
            if (!this.validateSlotConfig()) {
                isPerformingAction = false;
                this.setEnabled(false);
                this.skipNextTick = true;
                return;
            }
            if (ItemUtil.hasServerItem()) {
                isPerformingAction = false;
                this.skipNextTick = true;
                return;
            }
            this.idleTicks = MovementUtil.isInputActive() ? 0 : ++this.idleTicks;
            boolean isContainerOpen = false;
            AbstractContainerMenu containerMenu = mc.player.containerMenu;
            Object screen = mc.screen;
            if (screen instanceof ContainerScreen) {
                containerScreen = (ContainerScreen)screen;
                String title = containerScreen.getTitle().getString();
                String chestTitle = Component.translatable("container.chest").getString();
                String doubleChestTitle = Component.translatable("container.chestDouble").getString();
                String enderChestTitle = Component.translatable("container.enderchest").getString();
                ChestMenu chestMenu = containerScreen.getMenu();
                if (title.equals(chestTitle) || title.equals(doubleChestTitle) || title.equals("Chest")) {
                    isContainerOpen = true;
                }
            }
            if (containerMenu instanceof FurnaceMenu || containerMenu instanceof BrewingStandMenu) {
                isContainerOpen = true;
            }
            if (isContainerOpen || ChestStealer.isRateLimited() || Scaffold.INSTANCE.isEnabled() || (this.inventoryOnlySetting.getValue() != false ? !(mc.screen instanceof InventoryScreen) : !GuiMove.INSTANCE.isEnabled() && this.idleTicks <= 1)) {
                this.pendingOffhandPlace = false;
                this.sprintWaitTicks = 0;
                isPerformingAction = false;
                this.skipNextTick = true;
                return;
            }
            screen = mc.screen;
            if (screen instanceof AbstractContainerScreen acs) {
                if (acs.getMenu().containerId != mc.player.inventoryMenu.containerId) {
                    return;
                }
            }
            if (this.inventoryOnlySetting.getValue() && mc.screen instanceof InventoryScreen) {
                ++this.sprintWaitTicks;
                if (this.sprintWaitTicks < this.sprintDelayTicksSetting.getValue().intValue()) {
                    return;
                }
            }
            if (this.performInventoryAction()) {
                isPerformingAction = true;
            } else {
                isPerformingAction = false;
                this.skipNextTick = true;
            }
        }
    }

    /*
     * Enabled aggressive block sorting
     */
    private boolean performInventoryAction() {
        Integer dropSlot;
        ItemStack dropStack;
        ItemStack worstProjectile;
        ItemStack fishingRodStack;
        ItemStack bestFoodStack;
        ItemStack worstBlock;
        if (this.handleAutoArmor()) {
            return true;
        }
        if (this.pendingOffhandPlace && actionTimer.hasPassed(this.actionDelaySetting.getValue().intValue())) {
            mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, 45, 0, ClickType.PICKUP, mc.player);
            this.didInventoryAction = true;
            this.pendingOffhandPlace = false;
            actionTimer.reset();
        }
        String offhandPreference = this.offhandItemSetting.getValue();
        if ("Golden Apple".equals(offhandPreference)) {
            if (this.handleGoldenAppleOffhand()) {
                return true;
            }
        } else if ("Projectile".equals(offhandPreference)) {
            if (this.handleProjectileOffhand()) {
                return true;
            }
        } else if ("Fishing Rod".equals(offhandPreference)) {
            if (this.handleFishingRodOffhand()) {
                return true;
            }
        } else if ("Block".equals(offhandPreference)) {
            if (this.handleBlockOffhand()) {
                return true;
            }
        }
        if (!this.offhandItemSetting.getValue().equals("Golden Apple") && this.goldenAppleSlotSetting.getValue().intValue() != 0) {
            this.swapItemToSlot(this.goldenAppleSlotSetting.getValue().intValue() - 1, Items.GOLDEN_APPLE);
        }
        if (this.blockSlotSetting.getValue().intValue() != 0) {
            int blockSlot = this.blockSlotSetting.getValue().intValue() - 1;
            ItemStack currentBlock = mc.player.getInventory().items.get(blockSlot);
            ItemStack bestBlock = ItemUtil.getBestBlock();
            if (!(bestBlock == null || bestBlock.getCount() <= currentBlock.getCount() && BlockUtil.isPlaceable(currentBlock) || this.offhandItemSetting.getValue().equals("Block") || !this.swapToSlot(blockSlot, bestBlock))) {
                return true;
            }
        }
        if (ItemUtil.countBlocks() > this.maxBlockSizeSetting.getValue().intValue() && this.throwItem(worstBlock = ItemUtil.getWorstBlock())) {
            return true;
        }
        if (ItemUtil.countFood() > this.maxFoodSizeSetting.getValue().intValue() && this.throwItem(bestFoodStack = ItemUtil.getBestFoodStack())) {
            return true;
        }
        if (ItemUtil.countFishingRods() > this.maxRodSizeSetting.getValue().intValue() && this.throwItem(fishingRodStack = ItemUtil.getFishingRodStack())) {
            return true;
        }
        if (ItemUtil.countItem(Items.EGG) + ItemUtil.countItem(Items.SNOWBALL) > this.maxEggsSnowballsSetting.getValue().intValue() && this.throwItem(worstProjectile = ItemUtil.getWorstProjectile())) {
            return true;
        }
        if (this.swordSlotSetting.getValue().intValue() != 0) {
            ItemStack bestSword = ItemUtil.getBestSword();
            int swordSlot = this.swordSlotSetting.getValue().intValue() - 1;
            ItemStack currentSword = mc.player.getInventory().items.get(swordSlot);
            ItemStack bestSharpAxe = ItemUtil.getBestSharpAxe();
            if (ItemUtil.getAxeDamage(bestSharpAxe) > ItemUtil.getSwordDamage(bestSword)) {
                bestSword = bestSharpAxe;
            }
            if (bestSword != null) {
                float currentDamage = currentSword.getItem() instanceof SwordItem ? ItemUtil.getSwordDamage(currentSword) : ItemUtil.getAxeDamage(currentSword);
                float candidateDamage = bestSword.getItem() instanceof SwordItem ? ItemUtil.getSwordDamage(bestSword) : ItemUtil.getAxeDamage(bestSword);
                if (candidateDamage > currentDamage && this.swapToSlot(swordSlot, bestSword)) {
                    return true;
                }
            }
        }
        if (this.pickaxeSlotSetting.getValue().intValue() != 0) {
            int pickaxeSlot = this.pickaxeSlotSetting.getValue().intValue() - 1;
            ItemStack bestPickaxe = ItemUtil.getBestPickaxe();
            ItemStack currentPickaxe = mc.player.getInventory().items.get(pickaxeSlot);
            if (bestPickaxe != null && bestPickaxe.getItem() instanceof PickaxeItem && (ItemUtil.getDigSpeed(bestPickaxe) > ItemUtil.getDigSpeed(currentPickaxe) || !(currentPickaxe.getItem() instanceof PickaxeItem)) && this.swapToSlot(pickaxeSlot, bestPickaxe)) {
                return true;
            }
        }
        if (this.bowSlotSetting.getValue().intValue() != 0) {
            ItemStack arrowStack;
            float currentScore;
            float bestScore;
            ItemStack bestBow;
            int bowSlot = this.bowSlotSetting.getValue().intValue() - 1;
            ItemStack currentBow = mc.player.getInventory().items.get(bowSlot);
            if (this.bowPrioritySetting.getValue().equals("Crossbow")) {
                bestBow = ItemUtil.getBestCrossbow();
                bestScore = ItemUtil.getCrossbowScore(bestBow);
                currentScore = ItemUtil.getCrossbowScore(currentBow);
            } else if (this.bowPrioritySetting.getValue().equals("Power Bow")) {
                bestBow = ItemUtil.getBestBowAlt();
                bestScore = ItemUtil.getBowScoreAlt(bestBow);
                currentScore = ItemUtil.getBowScoreAlt(currentBow);
            } else {
                bestBow = ItemUtil.getBestBow();
                bestScore = ItemUtil.getBowScore(bestBow);
                currentScore = ItemUtil.getBowScore(currentBow);
            }
            if (bestBow == null) {
                bestBow = ItemUtil.getBestCrossbow();
                bestScore = ItemUtil.getCrossbowScore(bestBow);
                currentScore = ItemUtil.getCrossbowScore(currentBow);
            }
            if (bestBow == null) {
                bestBow = ItemUtil.getBestBowAlt();
                bestScore = ItemUtil.getBowScoreAlt(bestBow);
                currentScore = ItemUtil.getBowScoreAlt(currentBow);
            }
            if (bestBow == null) {
                bestBow = ItemUtil.getBestBow();
                bestScore = ItemUtil.getBowScore(bestBow);
                currentScore = ItemUtil.getBowScore(currentBow);
            }
            if (bestBow != null && bestScore > currentScore && this.swapToSlot(bowSlot, bestBow)) {
                return true;
            }
            if (ItemUtil.countItem(Items.ARROW) > 256 && this.throwItem(arrowStack = ItemUtil.getArrowStack())) {
                return true;
            }
        }
        if (this.axeSlotSetting.getValue().intValue() != 0) {
            ItemStack bestAxe = ItemUtil.getBestAxe();
            if (this.swapToSlot(this.axeSlotSetting.getValue().intValue() - 1, bestAxe)) {
                return true;
            }
        }
        if (this.eggsSnowballsSlotSetting.getValue().intValue() != 0 && this.swapToSlot(this.eggsSnowballsSlotSetting.getValue().intValue() - 1, ItemUtil.getBestProjectile())) {
            return true;
        }
        if (this.pearlSlotSetting.getValue().intValue() != 0 && this.swapItemToSlot(this.pearlSlotSetting.getValue().intValue() - 1, Items.ENDER_PEARL)) {
            return true;
        }
        if (this.waterBucketSlotSetting.getValue().intValue() != 0 && this.swapItemToSlot(this.waterBucketSlotSetting.getValue().intValue() - 1, Items.WATER_BUCKET)) {
            return true;
        }
        if (this.slimeBallSlotSetting.getValue().intValue() != 0 && this.swapItemToSlot(this.slimeBallSlotSetting.getValue().intValue() - 1, Items.SLIME_BALL)) {
            return true;
        }
        if (this.crystalSlotSetting.getValue().intValue() != 0 && this.swapItemToSlot(this.crystalSlotSetting.getValue().intValue() - 1, Items.END_CRYSTAL)) {
            return true;
        }
        List<Integer> slotIndices = IntStream.range(0, mc.player.getInventory().items.size()).boxed().collect(Collectors.toList());
        Collections.shuffle(slotIndices);
        Iterator<Integer> slotIterator = slotIndices.iterator();
        do {
            if (slotIterator.hasNext()) continue;
            return false;
        } while ((dropStack = mc.player.getInventory().items.get((dropSlot = slotIterator.next()).intValue())).isEmpty() || this.isUsefulItem(dropStack));
        this.throwItem(dropStack);
        return true;
    }

    private boolean handleAutoArmor() {
        if (!this.autoArmorSetting.getValue()) {
            return false;
        }
        for (int slot = 0; slot < mc.player.getInventory().armor.size(); ++slot) {
            ItemStack armorStack = mc.player.getInventory().armor.get(slot);
            if (!(armorStack.getItem() instanceof ArmorItem armorItem)) continue;
            if (armorStack.isEmpty()
                    || !actionTimer.hasPassed(this.actionDelaySetting.getValue().intValue())
                    || !(ItemUtil.getBestArmorScore(armorItem.getEquipmentSlot()) > ItemUtil.getArmorScore(armorStack))) {
                continue;
            }
            mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, 4 + (4 - slot), 1, ClickType.THROW, mc.player);
            this.didInventoryAction = true;
            actionTimer.reset();
            return true;
        }
        for (int slot = 0; slot < mc.player.getInventory().items.size(); ++slot) {
            ItemStack candidate = mc.player.getInventory().items.get(slot);
            if (candidate.isEmpty() || !(candidate.getItem() instanceof ArmorItem armorItem)) continue;
            float candidateScore = ItemUtil.getArmorScore(candidate);
            boolean isBestArmor = ItemUtil.getBestArmorScore(armorItem.getEquipmentSlot()) == candidateScore;
            boolean betterThanEquipped = ItemUtil.getEquippedArmorScore(armorItem.getEquipmentSlot()) < candidateScore;
            if (!isBestArmor || !betterThanEquipped
                    || !actionTimer.hasPassed(this.actionDelaySetting.getValue().intValue())) {
                continue;
            }
            int targetSlot = slot < 9 ? slot + 36 : slot;
            mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, targetSlot, 0, ClickType.QUICK_MOVE, mc.player);
            this.didInventoryAction = true;
            actionTimer.reset();
            return true;
        }
        return false;
    }

    private boolean handleGoldenAppleOffhand() {
        ItemStack offhand = mc.player.getInventory().offhand.get(0);
        int slot = ItemUtil.getSlot(Items.GOLDEN_APPLE);
        if (slot == -1 || !actionTimer.hasPassed(this.actionDelaySetting.getValue().intValue())) {
            return false;
        }
        if (offhand.getItem() != Items.GOLDEN_APPLE) {
            this.moveToOffhand(slot);
            return true;
        }
        ItemStack invStack = mc.player.getInventory().items.get(slot);
        if (offhand.getCount() + invStack.getCount() > 64) {
            return false;
        }
        int targetSlot = slot < 9 ? slot + 36 : slot;
        mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, targetSlot, 0, ClickType.PICKUP, mc.player);
        this.didInventoryAction = true;
        this.pendingOffhandPlace = true;
        actionTimer.reset();
        return true;
    }

    private boolean handleProjectileOffhand() {
        ItemStack offhand = mc.player.getInventory().offhand.get(0);
        ItemStack bestProjectile = ItemUtil.getBestProjectile();
        if (bestProjectile == null) {
            return false;
        }
        int slot = ItemUtil.getSlot(bestProjectile);
        boolean shouldSwap;
        if (offhand.getItem() == Items.EGG || offhand.getItem() == Items.SNOWBALL) {
            shouldSwap = offhand.getCount() < bestProjectile.getCount();
        } else {
            shouldSwap = true;
        }
        if (!shouldSwap || slot == -1 || !actionTimer.hasPassed(this.actionDelaySetting.getValue().intValue())) {
            return false;
        }
        this.moveToOffhand(slot);
        return true;
    }

    private boolean handleFishingRodOffhand() {
        ItemStack offhand = mc.player.getInventory().offhand.get(0);
        int slot = ItemUtil.getSlot(Items.FISHING_ROD);
        if (slot == -1 || !actionTimer.hasPassed(this.actionDelaySetting.getValue().intValue())
                || offhand.getItem() == Items.FISHING_ROD) {
            return false;
        }
        this.moveToOffhand(slot);
        return true;
    }

    private boolean handleBlockOffhand() {
        ItemStack offhand = mc.player.getInventory().offhand.get(0);
        ItemStack bestBlock = ItemUtil.getBestBlock();
        if (bestBlock == null) {
            return false;
        }
        int slot = ItemUtil.getSlot(bestBlock);
        boolean shouldSwap;
        if (BlockUtil.isPlaceable(offhand)) {
            shouldSwap = offhand.getCount() < bestBlock.getCount();
        } else {
            shouldSwap = true;
        }
        if (!shouldSwap || slot == -1 || !actionTimer.hasPassed(this.actionDelaySetting.getValue().intValue())) {
            return false;
        }
        this.moveToOffhand(slot);
        return true;
    }

    private void moveToOffhand(int slot) {
        if (mc.gameMode == null || mc.player == null) {
            return;
        }
        if (slot < 9) {
            mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, slot + 36, 40, ClickType.SWAP, mc.player);
        } else {
            mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, slot, 40, ClickType.SWAP, mc.player);
        }
        this.didInventoryAction = true;
        actionTimer.reset();
    }

    private boolean throwItem(ItemStack itemStack) {
        int slot;
        if (mc.gameMode == null || mc.player == null) {
            return false;
        }
        if (this.throwItemsSetting.getValue() && ItemUtil.isUsable(itemStack) && (actionTimer.hasPassed(this.dropDelaySetting.getValue().intValue()) || this.fastThrowSetting.getValue()) && (slot = ItemUtil.getSlot(itemStack)) != -1) {
            if (slot < 9) {
                mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, slot + 36, 1, ClickType.THROW, mc.player);
            } else {
                mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, slot, 1, ClickType.THROW, mc.player);
            }
            this.didInventoryAction = true;
            actionTimer.reset();
            return true;
        }
        return false;
    }

    private boolean swapToSlot(int targetSlot, ItemStack itemStack) {
        int sourceSlot;
        if (mc.gameMode == null || mc.player == null) {
            return false;
        }
        ItemStack currentStack = mc.player.getInventory().items.get(targetSlot);
        if (ItemUtil.isUsable(currentStack) && itemStack != currentStack && actionTimer.hasPassed(this.actionDelaySetting.getValue().intValue()) && (sourceSlot = ItemUtil.getSlot(itemStack)) != -1) {
            if (sourceSlot < 9) {
                mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, sourceSlot + 36, targetSlot, ClickType.SWAP, mc.player);
            } else {
                mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, sourceSlot, targetSlot, ClickType.SWAP, mc.player);
            }
            this.didInventoryAction = true;
            actionTimer.reset();
            return true;
        }
        return false;
    }

    private boolean swapItemToSlot(int targetSlot, Item item) {
        int sourceSlot;
        if (mc.gameMode == null || mc.player == null) {
            return false;
        }
        ItemStack currentStack = mc.player.getInventory().items.get(targetSlot);
        if (ItemUtil.isUsable(currentStack) && actionTimer.hasPassed(this.actionDelaySetting.getValue().intValue()) && (sourceSlot = ItemUtil.getSlot(item)) != -1) {
            ItemStack sourceStack = mc.player.getInventory().items.get(sourceSlot);
            if (currentStack.getItem() != item || currentStack.getItem() == item && currentStack.getCount() < sourceStack.getCount()) {
                if (sourceSlot < 9) {
                    mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, sourceSlot + 36, targetSlot, ClickType.SWAP, mc.player);
                } else {
                    mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, sourceSlot, targetSlot, ClickType.SWAP, mc.player);
                }
                this.didInventoryAction = true;
                actionTimer.reset();
                return true;
            }
        }
        return false;
    }

    public static int getMaxBlockSize() {
        return InventoryManager.INSTANCE.maxBlockSizeSetting.getValue().intValue();
    }

    public static int getMaxEggsSnowballsSize() {
        return InventoryManager.INSTANCE.maxEggsSnowballsSetting.getValue().intValue();
    }

    public static int getMaxArrows() {
        return 256;
    }

    public static int getMaxWaterBuckets() {
        return 1;
    }

    public static int getMaxLavaBuckets() {
        return 1;
    }

    public boolean isUsefulItem(ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return false;
        }
        if (ItemUtil.isWeaponItem(itemStack)) {
            return true;
        }
        if (itemStack.getDisplayName().getString().contains("点击使用")) {
            return true;
        }
        if (itemStack.getItem() == Items.COBWEB) {
            return true;
        }
        // Functional blocks fix: always keep TNT (not counted as regular block)
        if (this.functionalBlocksFix.getValue() && itemStack.getItem() == Items.TNT) {
            return true;
        }
        Item item = itemStack.getItem();
        if (item instanceof ArmorItem armorItem) {
            float candidateScore = ItemUtil.getArmorScore(itemStack);
            if (ItemUtil.getEquippedArmorScore(armorItem.getEquipmentSlot()) >= candidateScore) {
                return false;
            }
            float bestScore = ItemUtil.getBestArmorScore(armorItem.getEquipmentSlot());
            return !(candidateScore < bestScore);
        }
        if (itemStack.getItem() instanceof SwordItem) {
            return ItemUtil.getBestSword() == itemStack;
        }
        if (itemStack.getItem() instanceof PickaxeItem) {
            return ItemUtil.getBestPickaxe() == itemStack;
        }
        if (itemStack.getItem() instanceof AxeItem && !ItemUtil.isLegitAxe(itemStack)) {
            return ItemUtil.getBestAxe() == itemStack;
        }
        if (itemStack.getItem() instanceof ShovelItem) {
            return ItemUtil.getBestShovel() == itemStack;
        }
        if (itemStack.getItem() instanceof CrossbowItem) {
            return ItemUtil.getBestCrossbow() == itemStack;
        }
        if (itemStack.getItem() instanceof BowItem && ItemUtil.isGoodBow(itemStack)) {
            return ItemUtil.getBestBow() == itemStack;
        }
        if (itemStack.getItem() instanceof BowItem && ItemUtil.isGoodBowAlt(itemStack)) {
            return ItemUtil.getBestBowAlt() == itemStack;
        }
        if (itemStack.getItem() instanceof BowItem && ItemUtil.countItem(Items.BOW) > 1) {
            return false;
        }
        if (itemStack.getItem() == Items.WATER_BUCKET && ItemUtil.countItem(Items.WATER_BUCKET) > InventoryManager.getMaxWaterBuckets()) {
            return false;
        }
        if (itemStack.getItem() == Items.LAVA_BUCKET && ItemUtil.countItem(Items.LAVA_BUCKET) > InventoryManager.getMaxLavaBuckets()) {
            return false;
        }
        if (itemStack.getItem() instanceof FishingRodItem && ItemUtil.countItem(Items.FISHING_ROD) > 1) {
            return false;
        }
        if (itemStack.getItem() instanceof ItemNameBlockItem) {
            return false;
        }
        return ItemUtil.isUsableItem(itemStack);
    }

    static {
        actionTimer = new Timer();
        isPerformingAction = false;
    }
}