package client.nilore.modules.impl.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
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
import client.nilore.event.impl.MotionEvent;
import client.nilore.event.impl.PacketEvent;
import client.nilore.event.impl.SprintEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.combat.KillAura;
import client.nilore.modules.impl.movement.NoSlow;
import client.nilore.modules.impl.movement.Scaffold;
import java.util.concurrent.ThreadLocalRandom;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.animation.Timer;
import client.nilore.utils.game.BlockUtil;
import client.nilore.utils.game.ItemUtil;
import client.nilore.utils.misc.PacketUtil;
import client.nilore.event.EventTarget;

public class InventoryManager extends Module {
    public static InventoryManager INSTANCE;

    private final NumberSetting actionDelaySetting = new NumberSetting("Delay", 110, 0, 500, 10);
    private final NumberSetting sprintDelayTicksSetting = new NumberSetting("Open Delay", 2, 0, 10, 1);
    private final NumberSetting dropDelaySetting = new NumberSetting("Drop Delay", 40, 0, 500, 10);
    private final BooleanSetting autoArmorSetting = new BooleanSetting("Auto Armor", true);
    private final BooleanSetting throwItemsSetting = new BooleanSetting("Throw Items", true);
    private final ModeSetting offhandItemSetting = new ModeSetting("Offhand Items", "None", "Golden Apple", "Projectile", "Fishing Rod", "Block").withDefault("None");
    private final ModeSetting bowPrioritySetting = new ModeSetting("Bow Priority", "Crossbow", "Power Bow", "Punch Bow").withDefault("Power Bow");
    private final BooleanSetting inventoryOnlySetting = new BooleanSetting("Inventory Only", false);
    private final BooleanSetting pauseOnKillAura = new BooleanSetting("Pause On KillAura", true);
    private final BooleanSetting fastThrowSetting = new BooleanSetting("Fast Throw", true);
    private final NumberSetting maxEggsSnowballsSetting = new NumberSetting("Max Eggs & Snowballs Size", 64, 16, 256, 16);
    public final NumberSetting maxBlockSizeSetting = new NumberSetting("Max Block Size", 256, 64, 512, 64);
    private final NumberSetting maxFoodSizeSetting = new NumberSetting("Max Food Size", 128, 32, 256, 32);
    private final NumberSetting maxRodSizeSetting = new NumberSetting("Max Rod Size", 1, 1, 16, 1);
    private final NumberSetting swordSlotSetting = new NumberSetting("Sword Slot", 1, 0, 9, 1);
    private final NumberSetting blockSlotSetting = new NumberSetting("Block Slot", 2, 0, 9, 1);
    private final NumberSetting axeSlotSetting = new NumberSetting("Axe Slot", 4, 0, 9, 1);
    private final NumberSetting pickaxeSlotSetting = new NumberSetting("Pickaxe Slot", 0, 0, 9, 1);
    private final NumberSetting bowSlotSetting = new NumberSetting("Bow Slot", 3, 0, 9, 1);
    private final NumberSetting waterBucketSlotSetting = new NumberSetting("Water Bucket Slot", 5, 0, 9, 1);
    private final NumberSetting pearlSlotSetting = new NumberSetting("Ender Pearl Slot", 7, 0, 9, 1);
    private final NumberSetting goldenAppleSlotSetting = new NumberSetting("Golden Apple Slot", 6, 0, 9, 1);
    private final NumberSetting eggsSnowballsSlotSetting = new NumberSetting("Eggs & Snowballs Slot", 0, 0, 9, 1);
    private final NumberSetting slimeBallSlotSetting = new NumberSetting("Slime Ball Slot", 0, 0, 9, 1);
    private final NumberSetting crystalSlotSetting = new NumberSetting("Crystal Slot", 0, 0, 9, 1);
    public final BooleanSetting functionalBlocksFix = new BooleanSetting("Functional Blocks Fix", true);

    private static final Timer actionTimer = new Timer();
    private static final int ACTION_JITTER_MS = 20;

    private boolean didInventoryAction = false;
    private boolean pendingOffhandPlace = false;
    private int sprintWaitTicks = 0;
    public static boolean isPerformingAction = false;
    private boolean skipNextTick = false;
    // multiaction 绕过:发 STOP_SPRINTING 后需冷却 sprintStopCooldown tick 才允许整理,
    // 保证 container click 与任何疾跑状态包不同 tick(Grim MultiActions D)
    private int sprintStopCooldown = 0;
    private boolean lastSprintState = false;

    public InventoryManager() {
        super("InventoryManager", Category.PLAYER, 66);
        INSTANCE = this;
    }

    @Override
    protected void onDisable() {
        isPerformingAction = false;
        this.skipNextTick = false;
        this.sprintStopCooldown = 0;
        this.lastSprintState = false;
        super.onDisable();
    }

    @EventTarget
    public void onSprint(SprintEvent event) {
        if (this.inventoryOnlySetting.getValue() || mc.player == null) {
            return;
        }
        boolean nowSprinting = mc.player.isSprinting();
        // 本 tick 玩家疾跑刚停止(无论 vanilla 松 W 还是别处 setSprinting(false),
        // STOP_SPRINTING 已发出),置冷却避免整理 click 与疾跑包同 tick
        if (this.lastSprintState && !nowSprinting) {
            this.sprintStopCooldown = Math.max(this.sprintStopCooldown, 2);
        }
        this.lastSprintState = nowSprinting;
        // 静默整理会话期间持续压疾跑,防止 Sprint 模块恢复疾跑造成 START/STOP 抖动
        if (isPerformingAction) {
            this.suppressSprintForInventory();
        }
    }

    /**
     * 统一压疾跑入口:疾跑中则发 STOP_SPRINTING 并置冷却(整理 click 必须与疾跑状态包
     * 至少间隔 sprintStopCooldown tick,否则 Grim MultiActions D 会报 multiaction)。
     * 供 Disabler.onSprint 等外部调用,保证所有疾跑压制路径都走同一个冷却逻辑。
     */
    public void suppressSprintForInventory() {
        if (mc.player == null) {
            return;
        }
        if (mc.player.isSprinting()) {
            mc.options.keySprint.setDown(false);
            mc.player.setSprinting(false);
            this.sprintStopCooldown = Math.max(this.sprintStopCooldown, 2);
        } else {
            mc.options.keySprint.setDown(false);
        }
    }

    /**
     * 静默整理会话是否在进行(InvOnly=false):只由"是否有整理需求"决定。
     * sprintStopCooldown 仅负责在 gate 挡整理 click(等 STOP 与服务端同步),
     * 不参与 isPerformingAction——否则玩家刚停疾跑(cooldown>0)即使空身、
     * 无整理需求也会被 onSprint 压掉疾跑键(站着不动也会被短暂压制)。
     */
    private boolean silentSessionActive() {
        if (this.inventoryOnlySetting.getValue()) {
            return false;
        }
        return this.hasPendingActions();
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        Packet<?> packet = event.getPacket();
        if (!event.isIncomingRaw() || mc.player == null || mc.getConnection() == null) {
            return;
        }
        if (packet instanceof ServerboundContainerClosePacket) {
            this.didInventoryAction = false;
            this.sprintWaitTicks = 0;
        }
    }

    private boolean validateSlotConfig() {
        List<Pair<Boolean, NumberSetting>> entries = new ArrayList<>();
        entries.add(Pair.of(this.swordSlotSetting.getValue().intValue() != 0, this.swordSlotSetting));
        entries.add(Pair.of(this.axeSlotSetting.getValue().intValue() != 0, this.axeSlotSetting));
        entries.add(Pair.of(this.pickaxeSlotSetting.getValue().intValue() != 0, this.pickaxeSlotSetting));
        entries.add(Pair.of(this.bowSlotSetting.getValue().intValue() != 0, this.bowSlotSetting));
        entries.add(Pair.of(this.waterBucketSlotSetting.getValue().intValue() != 0, this.waterBucketSlotSetting));
        entries.add(Pair.of(this.pearlSlotSetting.getValue().intValue() != 0, this.pearlSlotSetting));
        entries.add(Pair.of(this.slimeBallSlotSetting.getValue().intValue() != 0, this.slimeBallSlotSetting));
        entries.add(Pair.of(this.crystalSlotSetting.getValue().intValue() != 0, this.crystalSlotSetting));
        entries.add(Pair.of(this.eggsSnowballsSlotSetting.getValue().intValue() != 0, this.eggsSnowballsSlotSetting));
        if (!"Golden Apple".equals(this.offhandItemSetting.getValue())) {
            entries.add(Pair.of(this.goldenAppleSlotSetting.getValue().intValue() != 0, this.goldenAppleSlotSetting));
        }
        if (!"Block".equals(this.offhandItemSetting.getValue())) {
            entries.add(Pair.of(this.blockSlotSetting.getValue().intValue() != 0, this.blockSlotSetting));
        }
        HashSet<Integer> usedSlots = new HashSet<>();
        for (Pair<Boolean, NumberSetting> entry : entries) {
            if (!entry.getKey()) continue;
            int slot = entry.getValue().getValue().intValue() - 1;
            if (!usedSlots.add(slot)) return false;
        }
        return true;
    }

    @EventTarget
    public void onMotionManage(MotionEvent event) {
        if (!event.isPost() || mc.player == null || mc.getConnection() == null || mc.gameMode == null) {
            return;
        }
        // Sync functional blocks flag to ItemUtil
        ItemUtil.excludeFunctionalBlocks = this.functionalBlocksFix.getValue();
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

        // Scaffold 或 KillAura 正在执行动作时，不整理
        if (this.shouldPauseForAction()) {
            isPerformingAction = false;
            this.skipNextTick = true;
            this.pendingOffhandPlace = false;
            this.sprintWaitTicks = 0;
            return;
        }

        boolean externalContainerOpen = false;
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (mc.screen instanceof ContainerScreen containerScreen) {
            String title = containerScreen.getTitle().getString();
            String chest = Component.translatable("container.chest").getString();
            String chestDouble = Component.translatable("container.chestDouble").getString();
            if (title.equals(chest) || title.equals(chestDouble) || title.equals("Chest")) {
                externalContainerOpen = true;
            }
        }
        if (menu instanceof FurnaceMenu || menu instanceof BrewingStandMenu) {
            externalContainerOpen = true;
        }

        boolean blockedByMode = this.inventoryOnlySetting.getValue()
                && !(mc.screen instanceof InventoryScreen);

        if (externalContainerOpen
                || ChestStealer.isRateLimited()
                || Scaffold.INSTANCE.isEnabled()
                || blockedByMode) {
            this.pendingOffhandPlace = false;
            this.sprintWaitTicks = 0;
            isPerformingAction = false;
            this.skipNextTick = true;
            return;
        }

        if (mc.screen instanceof AbstractContainerScreen acs
                && acs.getMenu().containerId != mc.player.inventoryMenu.containerId) {
            return;
        }

        if (this.inventoryOnlySetting.getValue() && mc.screen instanceof InventoryScreen) {
            this.sprintWaitTicks++;
            if (this.sprintWaitTicks < this.sprintDelayTicksSetting.getValue().intValue()) {
                return;
            }
        }

        if (this.performInventoryAction()) {
            isPerformingAction = true;
        } else {
            // 冷却中或仍有待整理动作时保持 isPerformingAction=true,
            // 让 onSprint 持续压疾跑、Sprint 模块不恢复疾跑(避免 START/STOP 抖动)
            isPerformingAction = this.silentSessionActive();
            this.skipNextTick = true;
        }
    }

    private boolean shouldPauseForAction() {
        if (Scaffold.INSTANCE != null && Scaffold.INSTANCE.isEnabled()) return true;
        // NoSlow 正在实际处理(换手吃/挡、拉弓、释放收尾)时暂停整理,而不是开着就暂停:
        // 此时 NoSlow 会发 SWAP_ITEM_WITH_OFFHAND/RELEASE_USE_ITEM 并延迟入站包,
        // 整理 click 与之同 tick 会触发 Grim;且 NoSlow 工作期间保持疾跑,整理压疾跑会冲突
        if (NoSlow.isActive()) return true;
        // 仅当 KillAura 正在打人(有目标)时暂停整理,而不是开着 KillAura 就暂停;
        // 这样 InvOnly=false 静默整理时无需打开背包
        if (KillAura.INSTANCE != null && KillAura.INSTANCE.isEnabled()
                && this.pauseOnKillAura.getValue()
                && KillAura.target != null) {
            return true;
        }
        return false;
    }

    private boolean performInventoryAction() {
        // invOnly=false 静默整理时:疾跑中若确有整理需求则主动压疾跑去整理
        // (不需玩家手动松 W),否则保持疾跑自由;压疾跑后经 sprintStopCooldown 冷却
        // 才发 container click,保证 click 与疾跑状态包不同 tick(Grim MultiActions D)。
        // 整理任务(pendingOffhandPlace 等)在等待期间不丢失
        if (!this.inventoryOnlySetting.getValue()) {
            if (mc.player.isSprinting()) {
                if (this.hasPendingActions()) {
                    this.suppressSprintForInventory();
                }
                return false;
            }
            if (this.sprintStopCooldown > 0) {
                this.sprintStopCooldown--;
                return false;
            }
        }
        // --- auto armor: drop bad armor we're wearing ---
        if (this.autoArmorSetting.getValue()) {
            for (int i = 0; i < mc.player.getInventory().armor.size(); i++) {
                ItemStack equipped = mc.player.getInventory().armor.get(i);
                if (equipped.getItem() instanceof ArmorItem armor
                        && !equipped.isEmpty()
                        && actionTimer.hasPassed(this.jitteredActionDelay())
                        && ItemUtil.getBestArmorScore(armor.getEquipmentSlot()) > ItemUtil.getArmorScore(equipped)) {
                    this.silentClick(
                            mc.player.inventoryMenu.containerId,
                            4 + (4 - i), 1, ClickType.THROW);
                    this.didInventoryAction = true;
                    actionTimer.reset();
                    return true;
                }
            }
            // --- auto armor: equip best armor in inventory ---
            for (int i = 0; i < mc.player.getInventory().items.size(); i++) {
                ItemStack candidate = mc.player.getInventory().items.get(i);
                if (candidate.isEmpty() || !(candidate.getItem() instanceof ArmorItem armor)) continue;
                float candidateScore = ItemUtil.getArmorScore(candidate);
                boolean isBest = ItemUtil.getBestArmorScore(armor.getEquipmentSlot()) == candidateScore;
                boolean betterThanEquipped = ItemUtil.getEquippedArmorScore(armor.getEquipmentSlot()) < candidateScore;
                if (isBest && betterThanEquipped
                        && actionTimer.hasPassed(this.jitteredActionDelay())) {
                    int target = i < 9 ? i + 36 : i;
                    this.silentClick(
                            mc.player.inventoryMenu.containerId,
                            target, 0, ClickType.QUICK_MOVE);
                    this.didInventoryAction = true;
                    actionTimer.reset();
                    return true;
                }
            }
        }

        // --- finish a pending offhand swap from the previous tick ---
        if (this.pendingOffhandPlace
                && actionTimer.hasPassed(this.jitteredActionDelay())) {
            this.silentClick(mc.player.inventoryMenu.containerId,
                    45, 0, ClickType.PICKUP);
            this.didInventoryAction = true;
            this.pendingOffhandPlace = false;
            actionTimer.reset();
        }

        // --- offhand preference ---
        String offhandPref = this.offhandItemSetting.getValue();
        if ("Golden Apple".equals(offhandPref)) {
            ItemStack offhand = mc.player.getInventory().offhand.get(0);
            int slot = ItemUtil.getSlot(Items.GOLDEN_APPLE);
            if (slot != -1 && actionTimer.hasPassed(this.jitteredActionDelay())) {
                if (offhand.getItem() != Items.GOLDEN_APPLE) {
                    this.moveToOffhand(slot);
                    return true;
                }
                ItemStack invStack = mc.player.getInventory().items.get(slot);
                if (offhand.getCount() + invStack.getCount() <= 64) {
                    int target = slot < 9 ? slot + 36 : slot;
                    this.silentClick(mc.player.inventoryMenu.containerId,
                            target, 0, ClickType.PICKUP);
                    this.didInventoryAction = true;
                    this.pendingOffhandPlace = true;
                    actionTimer.reset();
                    return true;
                }
            }
        } else if ("Projectile".equals(offhandPref)) {
            ItemStack offhand = mc.player.getInventory().offhand.get(0);
            ItemStack bestProjectile = ItemUtil.getBestProjectile();
            if (bestProjectile != null) {
                int slot = ItemUtil.getSlot(bestProjectile);
                boolean shouldSwap;
                if (offhand.getItem() != Items.EGG && offhand.getItem() != Items.SNOWBALL) {
                    shouldSwap = true;
                } else {
                    shouldSwap = offhand.getCount() < bestProjectile.getCount();
                }
                if (shouldSwap && slot != -1
                        && actionTimer.hasPassed(this.jitteredActionDelay())) {
                    this.moveToOffhand(slot);
                    return true;
                }
            }
        } else if ("Fishing Rod".equals(offhandPref)) {
            ItemStack offhand = mc.player.getInventory().offhand.get(0);
            int slot = ItemUtil.getSlot(Items.FISHING_ROD);
            if (slot != -1
                    && actionTimer.hasPassed(this.jitteredActionDelay())
                    && offhand.getItem() != Items.FISHING_ROD) {
                this.moveToOffhand(slot);
                return true;
            }
        } else if ("Block".equals(offhandPref)) {
            ItemStack offhand = mc.player.getInventory().offhand.get(0);
            ItemStack bestBlock = ItemUtil.getBestBlock();
            if (bestBlock != null) {
                int slot = ItemUtil.getSlot(bestBlock);
                boolean shouldSwap;
                if (BlockUtil.isPlaceable(offhand)) {
                    shouldSwap = offhand.getCount() < bestBlock.getCount();
                } else {
                    shouldSwap = true;
                }
                if (shouldSwap && slot != -1
                        && actionTimer.hasPassed(this.jitteredActionDelay())) {
                    this.moveToOffhand(slot);
                    return true;
                }
            }
        }

        // --- hotbar slot assignments ---
        if (!"Golden Apple".equals(this.offhandItemSetting.getValue())
                && this.goldenAppleSlotSetting.getValue().intValue() != 0) {
            this.swapItemToSlot(this.goldenAppleSlotSetting.getValue().intValue() - 1, Items.GOLDEN_APPLE);
        }

        if (this.blockSlotSetting.getValue().intValue() != 0) {
            int slot = this.blockSlotSetting.getValue().intValue() - 1;
            ItemStack current = mc.player.getInventory().items.get(slot);
            ItemStack bestBlock = ItemUtil.getBestBlock();
            if (bestBlock != null
                    && (bestBlock.getCount() > current.getCount() || !BlockUtil.isPlaceable(current))
                    && !"Block".equals(this.offhandItemSetting.getValue())
                    && this.swapToSlot(slot, bestBlock)) {
                return true;
            }
        }

        if (ItemUtil.countBlocks() > this.maxBlockSizeSetting.getValue().intValue()) {
            if (this.throwItem(ItemUtil.getWorstBlock())) return true;
        }
        if (ItemUtil.countFood() > this.maxFoodSizeSetting.getValue().intValue()) {
            if (this.throwItem(ItemUtil.getBestFoodStack())) return true;
        }
        if (ItemUtil.countFishingRods() > this.maxRodSizeSetting.getValue().intValue()) {
            if (this.throwItem(ItemUtil.getFishingRodStack())) return true;
        }
        if (ItemUtil.countItem(Items.EGG) + ItemUtil.countItem(Items.SNOWBALL)
                > this.maxEggsSnowballsSetting.getValue().intValue()) {
            if (this.throwItem(ItemUtil.getWorstProjectile())) return true;
        }

        if (this.swordSlotSetting.getValue().intValue() != 0) {
            ItemStack bestSword = ItemUtil.getBestSword();
            int slot = this.swordSlotSetting.getValue().intValue() - 1;
            ItemStack current = mc.player.getInventory().items.get(slot);
            ItemStack bestSharpAxe = ItemUtil.getBestSharpAxe();
            if (ItemUtil.getAxeDamage(bestSharpAxe) > ItemUtil.getSwordDamage(bestSword)) {
                bestSword = bestSharpAxe;
            }
            if (bestSword != null) {
                float currentDamage = current.getItem() instanceof SwordItem
                        ? ItemUtil.getSwordDamage(current)
                        : ItemUtil.getAxeDamage(current);
                float candidateDamage = bestSword.getItem() instanceof SwordItem
                        ? ItemUtil.getSwordDamage(bestSword)
                        : ItemUtil.getAxeDamage(bestSword);
                if (candidateDamage > currentDamage && this.swapToSlot(slot, bestSword)) {
                    return true;
                }
            }
        }

        if (this.pickaxeSlotSetting.getValue().intValue() != 0) {
            int slot = this.pickaxeSlotSetting.getValue().intValue() - 1;
            ItemStack bestPickaxe = ItemUtil.getBestPickaxe();
            ItemStack current = mc.player.getInventory().items.get(slot);
            if (bestPickaxe != null
                    && bestPickaxe.getItem() instanceof PickaxeItem
                    && (ItemUtil.getDigSpeed(bestPickaxe) > ItemUtil.getDigSpeed(current)
                    || !(current.getItem() instanceof PickaxeItem))
                    && this.swapToSlot(slot, bestPickaxe)) {
                return true;
            }
        }

        if (this.bowSlotSetting.getValue().intValue() != 0) {
            int slot = this.bowSlotSetting.getValue().intValue() - 1;
            ItemStack current = mc.player.getInventory().items.get(slot);
            ItemStack bestBow;
            float bestScore;
            float currentScore;
            if ("Crossbow".equals(this.bowPrioritySetting.getValue())) {
                bestBow = ItemUtil.getBestCrossbow();
                bestScore = ItemUtil.getCrossbowScore(bestBow);
                currentScore = ItemUtil.getCrossbowScore(current);
            } else if ("Power Bow".equals(this.bowPrioritySetting.getValue())) {
                bestBow = ItemUtil.getBestBowAlt();
                bestScore = ItemUtil.getBowScoreAlt(bestBow);
                currentScore = ItemUtil.getBowScoreAlt(current);
            } else {
                bestBow = ItemUtil.getBestBow();
                bestScore = ItemUtil.getBowScore(bestBow);
                currentScore = ItemUtil.getBowScore(current);
            }
            if (bestBow == null) {
                bestBow = ItemUtil.getBestCrossbow();
                bestScore = ItemUtil.getCrossbowScore(bestBow);
                currentScore = ItemUtil.getCrossbowScore(current);
            }
            if (bestBow == null) {
                bestBow = ItemUtil.getBestBowAlt();
                bestScore = ItemUtil.getBowScoreAlt(bestBow);
                currentScore = ItemUtil.getBowScoreAlt(current);
            }
            if (bestBow == null) {
                bestBow = ItemUtil.getBestBow();
                bestScore = ItemUtil.getBowScore(bestBow);
                currentScore = ItemUtil.getBowScore(current);
            }
            if (bestBow != null && bestScore > currentScore && this.swapToSlot(slot, bestBow)) {
                return true;
            }
            if (ItemUtil.countItem(Items.ARROW) > 256) {
                if (this.throwItem(ItemUtil.getArrowStack())) return true;
            }
        }

        if (this.axeSlotSetting.getValue().intValue() != 0) {
            ItemStack bestAxe = ItemUtil.getBestAxe();
            if (this.swapToSlot(this.axeSlotSetting.getValue().intValue() - 1, bestAxe)) {
                return true;
            }
        }

        if (this.eggsSnowballsSlotSetting.getValue().intValue() != 0
                && this.swapToSlot(this.eggsSnowballsSlotSetting.getValue().intValue() - 1,
                ItemUtil.getBestProjectile())) {
            return true;
        }
        if (this.pearlSlotSetting.getValue().intValue() != 0
                && this.swapItemToSlot(this.pearlSlotSetting.getValue().intValue() - 1, Items.ENDER_PEARL)) {
            return true;
        }
        if (this.waterBucketSlotSetting.getValue().intValue() != 0
                && this.swapItemToSlot(this.waterBucketSlotSetting.getValue().intValue() - 1, Items.WATER_BUCKET)) {
            return true;
        }
        if (this.slimeBallSlotSetting.getValue().intValue() != 0
                && this.swapItemToSlot(this.slimeBallSlotSetting.getValue().intValue() - 1, Items.SLIME_BALL)) {
            return true;
        }
        if (this.crystalSlotSetting.getValue().intValue() != 0
                && this.swapItemToSlot(this.crystalSlotSetting.getValue().intValue() - 1, Items.END_CRYSTAL)) {
            return true;
        }

        // --- last resort: drop the first useless thing we run into ---
        List<Integer> order = IntStream.range(0, mc.player.getInventory().items.size())
                .boxed().collect(Collectors.toList());
        Collections.shuffle(order);
        for (Integer idx : order) {
            ItemStack stack = mc.player.getInventory().items.get(idx);
            if (!stack.isEmpty() && !this.isUsefulItem(stack)) {
                this.throwItem(stack);
                return true;
            }
        }
        return false;
    }

    /**
     * Predicts whether performInventoryAction() still has work to do (without
     * executing anything). Used by Disabler (Silent Inventory) to only suppress
     * sprinting while there are actual cleanup actions pending — once the
     * inventory is clean, sprinting is restored. Mirrors the branch conditions
     * of performInventoryAction() but ignores the action delay timers.
     */
    public boolean hasPendingActions() {
        if (mc.player == null || mc.level == null) return false;

        // Scaffold/KillAura 打人期间实际整理会被暂停(见 shouldPauseForAction),
        // 此时不应报告"有待整理动作",否则 Disabler 的 Silent Sprint 会误以为
        // 正在静默整理而把疾跑关掉,实际却什么都没整理。
        if (this.shouldPauseForAction()) return false;

        // hasPendingActions 纯表示"背包是否有整理需求",不依赖疾跑状态/冷却;
        // 由 performInventoryAction 的 gate 据此决定是否主动压疾跑去整理。
        // 若此处也按 isSprinting 提前 return false,玩家疾跑中 gate 将无法判断
        // 是否有需求,必须等玩家手动停疾跑才开始整理(松按 W 快时还会在 sprinting 下整理)


        // --- auto armor: drop bad armor we're wearing / equip better armor ---
        if (this.autoArmorSetting.getValue()) {
            for (int i = 0; i < mc.player.getInventory().armor.size(); i++) {
                ItemStack equipped = mc.player.getInventory().armor.get(i);
                if (equipped.getItem() instanceof ArmorItem armor && !equipped.isEmpty()
                        && ItemUtil.getBestArmorScore(armor.getEquipmentSlot()) > ItemUtil.getArmorScore(equipped)) {
                    return true;
                }
            }
            for (int i = 0; i < mc.player.getInventory().items.size(); i++) {
                ItemStack candidate = mc.player.getInventory().items.get(i);
                if (candidate.isEmpty() || !(candidate.getItem() instanceof ArmorItem armor)) continue;
                if (ItemUtil.getBestArmorScore(armor.getEquipmentSlot()) == ItemUtil.getArmorScore(candidate)
                        && ItemUtil.getEquippedArmorScore(armor.getEquipmentSlot()) < ItemUtil.getArmorScore(candidate)) {
                    return true;
                }
            }
        }

        // --- pending offhand placement from a previous action ---
        if (this.pendingOffhandPlace) return true;

        // --- offhand preferences ---
        String offhandPref = this.offhandItemSetting.getValue();
        if ("Golden Apple".equals(offhandPref)) {
            ItemStack offhand = mc.player.getInventory().offhand.get(0);
            int slot = ItemUtil.getSlot(Items.GOLDEN_APPLE);
            if (slot != -1 && (offhand.getItem() != Items.GOLDEN_APPLE
                    || offhand.getCount() + mc.player.getInventory().items.get(slot).getCount() <= 64)) {
                return true;
            }
        } else if ("Projectile".equals(offhandPref)) {
            ItemStack offhand = mc.player.getInventory().offhand.get(0);
            ItemStack bestProjectile = ItemUtil.getBestProjectile();
            if (bestProjectile != null && ItemUtil.getSlot(bestProjectile) != -1) {
                boolean shouldSwap;
                if (offhand.getItem() != Items.EGG && offhand.getItem() != Items.SNOWBALL) {
                    shouldSwap = true;
                } else {
                    shouldSwap = offhand.getCount() < bestProjectile.getCount();
                }
                if (shouldSwap) return true;
            }
        } else if ("Fishing Rod".equals(offhandPref)) {
            ItemStack offhand = mc.player.getInventory().offhand.get(0);
            if (ItemUtil.getSlot(Items.FISHING_ROD) != -1 && offhand.getItem() != Items.FISHING_ROD) {
                return true;
            }
        } else if ("Block".equals(offhandPref)) {
            ItemStack offhand = mc.player.getInventory().offhand.get(0);
            ItemStack bestBlock = ItemUtil.getBestBlock();
            if (bestBlock != null && ItemUtil.getSlot(bestBlock) != -1) {
                boolean shouldSwap;
                if (BlockUtil.isPlaceable(offhand)) {
                    shouldSwap = offhand.getCount() < bestBlock.getCount();
                } else {
                    shouldSwap = true;
                }
                if (shouldSwap) return true;
            }
        }

        // --- hotbar slot assignments ---
        if (!"Golden Apple".equals(offhandPref) && this.goldenAppleSlotSetting.getValue().intValue() != 0
                && this.swapItemToSlotCheck(this.goldenAppleSlotSetting.getValue().intValue() - 1, Items.GOLDEN_APPLE)) {
            return true;
        }

        if (this.blockSlotSetting.getValue().intValue() != 0) {
            int slot = this.blockSlotSetting.getValue().intValue() - 1;
            ItemStack current = mc.player.getInventory().items.get(slot);
            ItemStack bestBlock = ItemUtil.getBestBlock();
            if (bestBlock != null
                    && (bestBlock.getCount() > current.getCount() || !BlockUtil.isPlaceable(current))
                    && !"Block".equals(offhandPref)
                    && this.swapToSlotCheck(slot, bestBlock)) {
                return true;
            }
        }

        if (ItemUtil.countBlocks() > this.maxBlockSizeSetting.getValue().intValue()) return true;
        if (ItemUtil.countFood() > this.maxFoodSizeSetting.getValue().intValue()) return true;
        if (ItemUtil.countFishingRods() > this.maxRodSizeSetting.getValue().intValue()) return true;
        if (ItemUtil.countItem(Items.EGG) + ItemUtil.countItem(Items.SNOWBALL)
                > this.maxEggsSnowballsSetting.getValue().intValue()) return true;

        if (this.swordSlotSetting.getValue().intValue() != 0) {
            ItemStack bestSword = ItemUtil.getBestSword();
            int slot = this.swordSlotSetting.getValue().intValue() - 1;
            ItemStack current = mc.player.getInventory().items.get(slot);
            ItemStack bestSharpAxe = ItemUtil.getBestSharpAxe();
            if (ItemUtil.getAxeDamage(bestSharpAxe) > ItemUtil.getSwordDamage(bestSword)) {
                bestSword = bestSharpAxe;
            }
            if (bestSword != null) {
                float currentDamage = current.getItem() instanceof SwordItem
                        ? ItemUtil.getSwordDamage(current)
                        : ItemUtil.getAxeDamage(current);
                float candidateDamage = bestSword.getItem() instanceof SwordItem
                        ? ItemUtil.getSwordDamage(bestSword)
                        : ItemUtil.getAxeDamage(bestSword);
                if (candidateDamage > currentDamage && this.swapToSlotCheck(slot, bestSword)) return true;
            }
        }

        if (this.pickaxeSlotSetting.getValue().intValue() != 0) {
            int slot = this.pickaxeSlotSetting.getValue().intValue() - 1;
            ItemStack bestPickaxe = ItemUtil.getBestPickaxe();
            ItemStack current = mc.player.getInventory().items.get(slot);
            if (bestPickaxe != null
                    && bestPickaxe.getItem() instanceof PickaxeItem
                    && (ItemUtil.getDigSpeed(bestPickaxe) > ItemUtil.getDigSpeed(current)
                    || !(current.getItem() instanceof PickaxeItem))
                    && this.swapToSlotCheck(slot, bestPickaxe)) {
                return true;
            }
        }

        if (this.bowSlotSetting.getValue().intValue() != 0) {
            int slot = this.bowSlotSetting.getValue().intValue() - 1;
            ItemStack current = mc.player.getInventory().items.get(slot);
            ItemStack bestBow;
            float bestScore;
            float currentScore;
            if ("Crossbow".equals(this.bowPrioritySetting.getValue())) {
                bestBow = ItemUtil.getBestCrossbow();
                bestScore = ItemUtil.getCrossbowScore(bestBow);
                currentScore = ItemUtil.getCrossbowScore(current);
            } else if ("Power Bow".equals(this.bowPrioritySetting.getValue())) {
                bestBow = ItemUtil.getBestBowAlt();
                bestScore = ItemUtil.getBowScoreAlt(bestBow);
                currentScore = ItemUtil.getBowScoreAlt(current);
            } else {
                bestBow = ItemUtil.getBestBow();
                bestScore = ItemUtil.getBowScore(bestBow);
                currentScore = ItemUtil.getBowScore(current);
            }
            if (bestBow == null) {
                bestBow = ItemUtil.getBestCrossbow();
                bestScore = ItemUtil.getCrossbowScore(bestBow);
                currentScore = ItemUtil.getCrossbowScore(current);
            }
            if (bestBow == null) {
                bestBow = ItemUtil.getBestBowAlt();
                bestScore = ItemUtil.getBowScoreAlt(bestBow);
                currentScore = ItemUtil.getBowScoreAlt(current);
            }
            if (bestBow == null) {
                bestBow = ItemUtil.getBestBow();
                bestScore = ItemUtil.getBowScore(bestBow);
                currentScore = ItemUtil.getBowScore(current);
            }
            if (bestBow != null && bestScore > currentScore && this.swapToSlotCheck(slot, bestBow)) return true;
            if (ItemUtil.countItem(Items.ARROW) > 256) return true;
        }

        if (this.axeSlotSetting.getValue().intValue() != 0
                && this.swapToSlotCheck(this.axeSlotSetting.getValue().intValue() - 1, ItemUtil.getBestAxe())) {
            return true;
        }

        if (this.eggsSnowballsSlotSetting.getValue().intValue() != 0
                && this.swapToSlotCheck(this.eggsSnowballsSlotSetting.getValue().intValue() - 1,
                ItemUtil.getBestProjectile())) {
            return true;
        }
        if (this.pearlSlotSetting.getValue().intValue() != 0
                && this.swapItemToSlotCheck(this.pearlSlotSetting.getValue().intValue() - 1, Items.ENDER_PEARL)) {
            return true;
        }
        if (this.waterBucketSlotSetting.getValue().intValue() != 0
                && this.swapItemToSlotCheck(this.waterBucketSlotSetting.getValue().intValue() - 1, Items.WATER_BUCKET)) {
            return true;
        }
        if (this.slimeBallSlotSetting.getValue().intValue() != 0
                && this.swapItemToSlotCheck(this.slimeBallSlotSetting.getValue().intValue() - 1, Items.SLIME_BALL)) {
            return true;
        }
        if (this.crystalSlotSetting.getValue().intValue() != 0
                && this.swapItemToSlotCheck(this.crystalSlotSetting.getValue().intValue() - 1, Items.END_CRYSTAL)) {
            return true;
        }

        // --- last resort: drop the first useless thing ---
        for (int i = 0; i < mc.player.getInventory().items.size(); i++) {
            ItemStack stack = mc.player.getInventory().items.get(i);
            if (!stack.isEmpty() && !this.isUsefulItem(stack)) return true;
        }
        return false;
    }

    private boolean swapToSlotCheck(int targetSlot, ItemStack stack) {
        ItemStack current = mc.player.getInventory().items.get(targetSlot);
        if (!ItemUtil.isUsable(current) || stack == current) return false;
        return ItemUtil.getSlot(stack) != -1;
    }

    private boolean swapItemToSlotCheck(int targetSlot, Item item) {
        ItemStack current = mc.player.getInventory().items.get(targetSlot);
        if (!ItemUtil.isUsable(current)) return false;
        int source = ItemUtil.getSlot(item);
        if (source == -1) return false;
        ItemStack sourceStack = mc.player.getInventory().items.get(source);
        return current.getItem() != item
                || (current.getItem() == item && current.getCount() < sourceStack.getCount());
    }

    /**
     * Silent inventory click: when no GUI is open (Inventory Only = OFF),
     * click and immediately close the container so the server sees a legal
     * quick open-and-close. No sprint packets are sent here — sprint is
     * suppressed by Disabler (Silent Inventory) during the whole cleanup.
     */
    private void silentClick(int containerId, int slotId, int button, ClickType clickType) {
        if (mc.screen != null) {
            mc.gameMode.handleInventoryMouseClick(containerId, slotId, button, clickType, mc.player);
            return;
        }
        mc.gameMode.handleInventoryMouseClick(containerId, slotId, button, clickType, mc.player);
        PacketUtil.sendQueued(new ServerboundContainerClosePacket(containerId));
    }

    private void moveToOffhand(int slot) {
        if (mc.gameMode == null || mc.player == null) return;
        int source = slot < 9 ? slot + 36 : slot;
        this.silentClick(mc.player.inventoryMenu.containerId,
                source, 40, ClickType.SWAP);
        this.didInventoryAction = true;
        actionTimer.reset();
    }

    private boolean throwItem(ItemStack stack) {
        if (mc.gameMode == null || mc.player == null) return false;
        if (!this.throwItemsSetting.getValue() || !ItemUtil.isUsable(stack)) return false;
        if (!actionTimer.hasPassed(this.dropDelaySetting.getValue().intValue())
                && !this.fastThrowSetting.getValue()) {
            return false;
        }
        int slot = ItemUtil.getSlot(stack);
        if (slot == -1) return false;
        int source = slot < 9 ? slot + 36 : slot;
        this.silentClick(mc.player.inventoryMenu.containerId,
                source, 1, ClickType.THROW);
        this.didInventoryAction = true;
        actionTimer.reset();
        return true;
    }

    private boolean swapToSlot(int targetSlot, ItemStack stack) {
        if (mc.gameMode == null || mc.player == null) return false;
        ItemStack current = mc.player.getInventory().items.get(targetSlot);
        if (!ItemUtil.isUsable(current) || stack == current
                || !actionTimer.hasPassed(this.jitteredActionDelay())) {
            return false;
        }
        int source = ItemUtil.getSlot(stack);
        if (source == -1) return false;
        int from = source < 9 ? source + 36 : source;
        this.silentClick(mc.player.inventoryMenu.containerId,
                from, targetSlot, ClickType.SWAP);
        this.didInventoryAction = true;
        actionTimer.reset();
        return true;
    }

    private boolean swapItemToSlot(int targetSlot, Item item) {
        if (mc.gameMode == null || mc.player == null) return false;
        ItemStack current = mc.player.getInventory().items.get(targetSlot);
        if (!ItemUtil.isUsable(current)
                || !actionTimer.hasPassed(this.jitteredActionDelay())) {
            return false;
        }
        int source = ItemUtil.getSlot(item);
        if (source == -1) return false;
        ItemStack sourceStack = mc.player.getInventory().items.get(source);
        if (current.getItem() != item
                || (current.getItem() == item && current.getCount() < sourceStack.getCount())) {
            int from = source < 9 ? source + 36 : source;
            this.silentClick(mc.player.inventoryMenu.containerId,
                    from, targetSlot, ClickType.SWAP);
            this.didInventoryAction = true;
            actionTimer.reset();
            return true;
        }
        return false;
    }

    public boolean isInventoryOnly() {
        return this.inventoryOnlySetting.getValue();
    }

    public static int getMaxBlockSize() {
        return INSTANCE.maxBlockSizeSetting.getValue().intValue();
    }

    public static int getMaxEggsSnowballsSize() {
        return INSTANCE.maxEggsSnowballsSetting.getValue().intValue();
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

    /**
     * Organize interval with a symmetric +/-20ms jitter, so consecutive inventory clicks are not
     * emitted on a perfectly constant period.
     */
    private int jitteredActionDelay() {
        int base = this.actionDelaySetting.getValue().intValue();
        if (base <= 0) {
            return 0;
        }
        return Math.max(0, base + ThreadLocalRandom.current().nextInt(-ACTION_JITTER_MS, ACTION_JITTER_MS + 1));
    }

    public boolean isUsefulItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (ItemUtil.isWeaponItem(stack)) return true;
        if (stack.getDisplayName().getString().contains("点击使用")) return true;
        if (stack.getItem() == Items.COBWEB) return true;
        // Functional blocks fix: always keep TNT (not counted as regular block)
        if (this.functionalBlocksFix.getValue() && stack.getItem() == Items.TNT) return true;
        if (stack.getItem() instanceof ArmorItem armor) {
            float score = ItemUtil.getArmorScore(stack);
            if (ItemUtil.getEquippedArmorScore(armor.getEquipmentSlot()) >= score) return false;
            return score >= ItemUtil.getBestArmorScore(armor.getEquipmentSlot());
        }
        if (stack.getItem() instanceof SwordItem)   return ItemUtil.getBestSword() == stack;
        if (stack.getItem() instanceof PickaxeItem) return ItemUtil.getBestPickaxe() == stack;
        if (stack.getItem() instanceof AxeItem && !ItemUtil.isLegitAxe(stack)) {
            return ItemUtil.getBestAxe() == stack;
        }
        if (stack.getItem() instanceof ShovelItem)   return ItemUtil.getBestShovel() == stack;
        if (stack.getItem() instanceof CrossbowItem) return ItemUtil.getBestCrossbow() == stack;
        if (stack.getItem() instanceof BowItem && ItemUtil.isGoodBow(stack))    return ItemUtil.getBestBow() == stack;
        if (stack.getItem() instanceof BowItem && ItemUtil.isGoodBowAlt(stack)) return ItemUtil.getBestBowAlt() == stack;
        if (stack.getItem() instanceof BowItem && ItemUtil.countItem(Items.BOW) > 1) return false;
        if (stack.getItem() == Items.WATER_BUCKET && ItemUtil.countItem(Items.WATER_BUCKET) > getMaxWaterBuckets()) return false;
        if (stack.getItem() == Items.LAVA_BUCKET && ItemUtil.countItem(Items.LAVA_BUCKET)   > getMaxLavaBuckets())  return false;
        if (stack.getItem() instanceof FishingRodItem && ItemUtil.countItem(Items.FISHING_ROD) > 1) return false;
        if (stack.getItem() instanceof ItemNameBlockItem) return false;
        return ItemUtil.isUsableItem(stack);
    }
}
