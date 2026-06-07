package shit.lizz.utils.game;

import com.google.common.collect.Multimap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import lombok.Generated;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BookItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.PlayerHeadItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SkullBlock;
import shit.lizz.ClientBase;
import shit.lizz.utils.game.BlockUtil;

public final class ItemUtil
extends ClientBase {
    public static boolean hasServerItem() {
        return ItemUtil.getAllItems().stream().anyMatch(itemStack -> {
            if (!itemStack.isEmpty()) {
                String displayName = itemStack.getDisplayName().getString();
                return displayName.contains("长按点击") || displayName.contains("点击使用") || displayName.contains("离开游戏") || displayName.contains("选择一个队伍") || displayName.contains("再来一局");
            }
            return false;
        });
    }

    public static int getSlot(ItemStack itemStack) {
        if (itemStack == null || mc.player == null) {
            return -1;
        }
        for (int i = 0; i < mc.player.getInventory().items.size(); ++i) {
            if (mc.player.getInventory().items.get(i) != itemStack) continue;
            return i;
        }
        return -1;
    }

    public static int getSlot(Item item) {
        if (mc.player == null) {
            return -1;
        }
        for (int i = 0; i < mc.player.getInventory().items.size(); ++i) {
            ItemStack itemStack = mc.player.getInventory().items.get(i);
            if (itemStack.getItem() != item) continue;
            return i;
        }
        return -1;
    }

    public static boolean isUsableItem(ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return true;
        }
        Item item = itemStack.getItem();
        if (item instanceof BlockItem blockItem) {
            if (blockItem.getBlock() == Blocks.ENCHANTING_TABLE) {
                return false;
            }
            return blockItem.getBlock() != Blocks.COBWEB;
        }
        if (item instanceof BookItem) {
            return false;
        }
        if (item instanceof ExperienceBottleItem) {
            return false;
        }
        if (item instanceof FireworkRocketItem) {
            return false;
        }
        if (item == Items.WHEAT_SEEDS || item == Items.BEETROOT_SEEDS || item == Items.MELON_SEEDS || item == Items.PUMPKIN_SEEDS) {
            return false;
        }
        return item != Items.FLINT_AND_STEEL;
    }

    public static int getPunchLevel(ItemStack itemStack) {
        return EnchantmentHelper.getItemEnchantmentLevel(Enchantments.PUNCH_ARROWS, itemStack);
    }

    public static int getPowerLevel(ItemStack itemStack) {
        return EnchantmentHelper.getItemEnchantmentLevel(Enchantments.POWER_ARROWS, itemStack);
    }

    public static List<ItemStack> getAllItems() {
        ArrayList<ItemStack> items = new ArrayList<>(40);
        if (mc.player == null) {
            return items;
        }
        items.addAll(mc.player.getInventory().items);
        items.addAll(mc.player.getInventory().armor);
        return items;
    }

    public static float getBestArmorScore(EquipmentSlot equipmentSlot) {
        return ItemUtil.getAllItems().stream()
                .filter(stack -> !stack.isEmpty() && stack.getItem() instanceof ArmorItem armor && armor.getEquipmentSlot() == equipmentSlot)
                .map(ItemUtil::getArmorScore)
                .max(Float::compareTo)
                .orElse(0.0f);
    }

    public static float getEquippedArmorScore(EquipmentSlot equipmentSlot) {
        if (equipmentSlot == EquipmentSlot.HEAD) {
            return ItemUtil.getArmorScore(mc.player.getInventory().armor.get(3));
        }
        if (equipmentSlot == EquipmentSlot.CHEST) {
            return ItemUtil.getArmorScore(mc.player.getInventory().armor.get(2));
        }
        if (equipmentSlot == EquipmentSlot.LEGS) {
            return ItemUtil.getArmorScore(mc.player.getInventory().armor.get(1));
        }
        if (equipmentSlot == EquipmentSlot.FEET) {
            return ItemUtil.getArmorScore(mc.player.getInventory().armor.get(0));
        }
        return 0.0f;
    }

    public static float getBestSwordDamage() {
        return ItemUtil.getAllItems().stream()
                .filter(stack -> !stack.isEmpty() && stack.getItem() instanceof SwordItem)
                .map(ItemUtil::getSwordDamage)
                .max(Float::compareTo)
                .orElse(0.0f);
    }

    public static ItemStack getBestSword() {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && itemStack.getItem() instanceof SwordItem).max(Comparator.comparingInt(itemStack -> (int)(ItemUtil.getSwordDamage(itemStack) * 100.0f))).orElse(null);
    }

    public static float getBowScore(ItemStack itemStack) {
        if (itemStack == null) {
            return 0.0f;
        }
        if (itemStack.isEmpty()) {
            return 0.0f;
        }
        if (itemStack.getItem() instanceof BowItem) {
            float score = 10.0f;
            score += (float)EnchantmentHelper.getItemEnchantmentLevel(Enchantments.PUNCH_ARROWS, itemStack);
            score += (float)EnchantmentHelper.getItemEnchantmentLevel(Enchantments.INFINITY_ARROWS, itemStack);
            score += (float)EnchantmentHelper.getItemEnchantmentLevel(Enchantments.FLAMING_ARROWS, itemStack);
            return (score += (float)EnchantmentHelper.getItemEnchantmentLevel(Enchantments.POWER_ARROWS, itemStack) / 10.0f) + (float)itemStack.getDamageValue() / (float)itemStack.getMaxDamage();
        }
        return 0.0f;
    }

    public static float getBowScoreAlt(ItemStack itemStack) {
        if (itemStack == null) {
            return 0.0f;
        }
        if (itemStack.isEmpty()) {
            return 0.0f;
        }
        if (itemStack.getItem() instanceof BowItem) {
            float score = 10.0f;
            score += (float)EnchantmentHelper.getItemEnchantmentLevel(Enchantments.PUNCH_ARROWS, itemStack) / 10.0f;
            score += (float)EnchantmentHelper.getItemEnchantmentLevel(Enchantments.INFINITY_ARROWS, itemStack);
            score += (float)EnchantmentHelper.getItemEnchantmentLevel(Enchantments.FLAMING_ARROWS, itemStack);
            return (score += (float)EnchantmentHelper.getItemEnchantmentLevel(Enchantments.POWER_ARROWS, itemStack)) + (float)itemStack.getDamageValue() / (float)itemStack.getMaxDamage();
        }
        return 0.0f;
    }

    public static float getDigSpeed(ItemStack itemStack) {
        float speed = 0.0f;
        if (itemStack == null) {
            return 0.0f;
        }
        if (itemStack.isEmpty()) {
            return 0.0f;
        }
        if (ItemUtil.isWeaponItem(itemStack)) {
            return 0.0f;
        }
        if (ItemUtil.isLegitAxe(itemStack)) {
            return 0.0f;
        }
        if (itemStack.getItem() instanceof PickaxeItem) {
            speed += itemStack.getDestroySpeed(Blocks.STONE.defaultBlockState());
        } else if (itemStack.getItem() instanceof AxeItem) {
            speed += itemStack.getDestroySpeed(Blocks.OAK_LOG.defaultBlockState());
        } else if (itemStack.getItem() instanceof ShovelItem) {
            speed += itemStack.getDestroySpeed(Blocks.DIRT.defaultBlockState());
        } else {
            return 0.0f;
        }
        int efficiencyLevel = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_EFFICIENCY, itemStack);
        if (efficiencyLevel > 0) {
            speed += (float)efficiencyLevel * 0.0075f;
        }
        return speed;
    }

    public static float getAxeDamage(ItemStack itemStack) {
        int sharpnessLevel;
        float damage = 0.0f;
        if (itemStack == null) {
            return 0.0f;
        }
        if (itemStack.isEmpty()) {
            return 0.0f;
        }
        Item item = itemStack.getItem();
        if (item instanceof AxeItem axeItem) {
            if (ItemUtil.isLegitAxe(itemStack)) {
                if (axeItem == Items.WOODEN_AXE) {
                    damage += 4.0f;
                } else if (axeItem == Items.STONE_AXE) {
                    damage += 5.0f;
                } else if (axeItem == Items.IRON_AXE) {
                    damage += 6.0f;
                } else if (axeItem == Items.GOLDEN_AXE) {
                    damage += 4.0f;
                } else if (axeItem == Items.DIAMOND_AXE) {
                    damage += 7.0f;
                }
            }
        }
        if ((sharpnessLevel = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SHARPNESS, itemStack)) > 0) {
            float sharpnessBonus = Enchantments.SHARPNESS.getDamageBonus(sharpnessLevel, MobType.UNDEFINED);
            damage += sharpnessBonus;
        }
        return damage;
    }

    public static float getSwordDamage(ItemStack itemStack) {
        int sharpnessLevel;
        float damage = 0.0f;
        if (itemStack == null) {
            return 0.0f;
        }
        if (itemStack.isEmpty()) {
            return 0.0f;
        }
        Item item = itemStack.getItem();
        if (item instanceof SwordItem swordItem) {
            damage += swordItem.getDamage() + 1.0f;
        }
        if ((sharpnessLevel = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SHARPNESS, itemStack)) > 0) {
            float sharpnessBonus = Enchantments.SHARPNESS.getDamageBonus(sharpnessLevel, MobType.UNDEFINED);
            damage += sharpnessBonus;
        }
        return damage;
    }

    public static float getArmorScore(ItemStack itemStack) {
        int score = 0;
        if (itemStack == null || itemStack.isEmpty()) {
            return 0.0f;
        }
        Item item = itemStack.getItem();
        if (item instanceof ArmorItem armorItem) {
            net.minecraft.world.item.ArmorMaterial material = armorItem.getMaterial();
            if (material == ArmorMaterials.LEATHER) score += 100;
            else if (material == ArmorMaterials.CHAIN) score += 200;
            else if (material == ArmorMaterials.IRON) score += 400;
            else if (material == ArmorMaterials.GOLD) score += 300;
            else if (material == ArmorMaterials.DIAMOND) score += 500;
            else if (material == ArmorMaterials.NETHERITE) score += 600;
        }
        return score + EnchantmentHelper.getItemEnchantmentLevel(Enchantments.ALL_DAMAGE_PROTECTION, itemStack);
    }

    public static float getCrossbowScore(ItemStack itemStack) {
        int score = 0;
        if (itemStack == null) {
            return 0.0f;
        }
        if (itemStack.isEmpty()) {
            return 0.0f;
        }
        if (itemStack.getItem() instanceof CrossbowItem) {
            score += EnchantmentHelper.getItemEnchantmentLevel(Enchantments.QUICK_CHARGE, itemStack);
            score += EnchantmentHelper.getItemEnchantmentLevel(Enchantments.MULTISHOT, itemStack);
            score += EnchantmentHelper.getItemEnchantmentLevel(Enchantments.PIERCING, itemStack);
        }
        return score;
    }

    public static boolean isWeaponItem(ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return false;
        }
        if (itemStack.getItem() instanceof AxeItem && itemStack.getItem() == Items.GOLDEN_AXE && EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SHARPNESS, itemStack) > 100) {
            return true;
        }
        if (itemStack.getItem() == Items.SLIME_BALL && EnchantmentHelper.getItemEnchantmentLevel(Enchantments.KNOCKBACK, itemStack) > 1) {
            return true;
        }
        if (itemStack.getItem() == Items.TOTEM_OF_UNDYING) {
            return true;
        }
        return itemStack.getItem() == Items.END_CRYSTAL;
    }

    public static double getAttackDamage(ItemStack itemStack) {
        double damage = 0.0;
        Multimap<Attribute, AttributeModifier> modifiers = itemStack.getAttributeModifiers(EquipmentSlot.MAINHAND);
        for (Attribute attribute : modifiers.keySet()) {
            if (!attribute.getDescriptionId().equals("attribute.name.generic.attack_damage")) continue;
            Iterator<AttributeModifier> iterator = modifiers.get(attribute).iterator();
            if (!iterator.hasNext()) break;
            damage += iterator.next().getAmount();
            break;
        }
        if (itemStack.hasFoil()) {
            damage += EnchantmentHelper.getItemEnchantmentLevel(Enchantments.FIRE_ASPECT, itemStack);
            damage += (double)EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SHARPNESS, itemStack) * 1.25;
        }
        return damage;
    }

    public static boolean isSkullItem(ItemStack itemStack) {
        BlockItem blockItem;
        if (itemStack.isEmpty()) {
            return false;
        }
        Item item = itemStack.getItem();
        return item instanceof BlockItem && (blockItem = (BlockItem)item).getBlock() instanceof SkullBlock;
    }

    public static int countFishingRods() {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && itemStack.getItem() == Items.FISHING_ROD && ItemUtil.isUsable(itemStack)).mapToInt(ItemStack::getCount).sum();
    }

    public static int countFood() {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && itemStack.getItem().isEdible() && itemStack.getItem() != Items.GOLDEN_APPLE && itemStack.getItem() != Items.ENCHANTED_GOLDEN_APPLE && ItemUtil.isUsable(itemStack)).mapToInt(ItemStack::getCount).sum();
    }

    public static ItemStack getBestFoodStack() {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && itemStack.getItem().isEdible() && itemStack.getItem() != Items.GOLDEN_APPLE && itemStack.getItem() != Items.ENCHANTED_GOLDEN_APPLE && ItemUtil.isUsable(itemStack)).min(Comparator.comparingInt(ItemStack::getCount)).orElse(null);
    }

    public static boolean isLegitAxe(ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return false;
        }
        if (!(itemStack.getItem() instanceof AxeItem)) {
            return false;
        }
        int sharpnessLevel = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SHARPNESS, itemStack);
        return sharpnessLevel >= 8 && sharpnessLevel < 50;
    }

    public static boolean isOtherCheat(ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return false;
        }
        String displayName = itemStack.getDisplayName().getString();
        if (displayName.contains("一刀")) {
            return true;
        }
        if (itemStack.getTag() != null && itemStack.getTag().toString().contains("一刀")) {
            return true;
        }
        if (itemStack.getItem() == Items.GOLDEN_AXE) {
            return EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SHARPNESS, itemStack) > 100;
        }
        return false;
    }

    public static boolean isEnchantedGoldenApple(ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return false;
        }
        return itemStack.getItem() == Items.ENCHANTED_GOLDEN_APPLE;
    }

    public static boolean isEndCrystal(ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return false;
        }
        return itemStack.getItem() == Items.END_CRYSTAL;
    }

    public static boolean isKBSlimeBall(ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return false;
        }
        if (itemStack.getItem() != Items.SLIME_BALL) {
            return false;
        }
        return EnchantmentHelper.getItemEnchantmentLevel(Enchantments.KNOCKBACK, itemStack) > 1;
    }

    public static boolean isKBStick(ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return false;
        }
        if (itemStack.getItem() != Items.STICK) {
            return false;
        }
        return EnchantmentHelper.getItemEnchantmentLevel(Enchantments.KNOCKBACK, itemStack) > 1;
    }

    public static ItemStack getBestProjectile() {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && (itemStack.getItem() == Items.EGG || itemStack.getItem() == Items.SNOWBALL) && ItemUtil.isUsable(itemStack)).max(Comparator.comparingInt(ItemStack::getCount)).orElse(null);
    }

    public static ItemStack getFishingRodStack() {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && itemStack.getItem() instanceof FishingRodItem && ItemUtil.isUsable(itemStack)).findAny().orElse(null);
    }

    public static int countBlocks() {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && itemStack.getItem() instanceof BlockItem && BlockUtil.isPlaceable(itemStack) && ItemUtil.isUsable(itemStack)).mapToInt(ItemStack::getCount).sum();
    }

    public static ItemStack getWorstProjectile() {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && (itemStack.getItem() == Items.EGG || itemStack.getItem() == Items.SNOWBALL)).min(Comparator.comparingInt(ItemStack::getCount)).orElse(null);
    }

    public static ItemStack getArrowStack() {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && itemStack.getItem() instanceof ArrowItem && ItemUtil.isUsable(itemStack)).min(Comparator.comparingInt(ItemStack::getCount)).orElse(null);
    }

    public static ItemStack getWorstBlock() {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && itemStack.getItem() instanceof BlockItem && BlockUtil.isPlaceable(itemStack) && ItemUtil.isUsable(itemStack)).min(Comparator.comparingInt(ItemStack::getCount)).orElse(null);
    }

    public static ItemStack getBestBlock() {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && itemStack.getItem() instanceof BlockItem && BlockUtil.isPlaceable(itemStack) && ItemUtil.isUsable(itemStack)).max(Comparator.comparingInt(ItemStack::getCount)).orElse(null);
    }

    public static float getBestPickaxeScore() {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && itemStack.getItem() instanceof PickaxeItem && ItemUtil.isUsable(itemStack)).map(ItemUtil::getDigSpeed).max(Float::compareTo).orElse(0.0f);
    }

    public static ItemStack getBestPickaxe() {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && itemStack.getItem() instanceof PickaxeItem && ItemUtil.isUsable(itemStack)).max(Comparator.comparingInt(itemStack -> (int)(ItemUtil.getDigSpeed(itemStack) * 100.0f))).orElse(null);
    }

    public static float getBestAxeScore() {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && itemStack.getItem() instanceof AxeItem && !ItemUtil.isLegitAxe(itemStack) && ItemUtil.isUsable(itemStack)).map(ItemUtil::getDigSpeed).max(Float::compareTo).orElse(0.0f);
    }

    public static ItemStack getBestAxe() {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && itemStack.getItem() instanceof AxeItem && !ItemUtil.isLegitAxe(itemStack) && ItemUtil.isUsable(itemStack)).max(Comparator.comparingInt(itemStack -> (int)(ItemUtil.getDigSpeed(itemStack) * 100.0f))).orElse(null);
    }

    public static ItemStack getBestSharpAxe() {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && itemStack.getItem() instanceof AxeItem && ItemUtil.isLegitAxe(itemStack) && ItemUtil.isUsable(itemStack) && !ItemUtil.isOtherCheat(itemStack)).max(Comparator.comparingInt(itemStack -> (int)(ItemUtil.getAxeDamage(itemStack) * 100.0f))).orElse(null);
    }

    public static float getBestShovelScore() {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && itemStack.getItem() instanceof ShovelItem && ItemUtil.isUsable(itemStack)).map(ItemUtil::getDigSpeed).max(Float::compareTo).orElse(0.0f);
    }

    public static ItemStack getBestShovel() {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && itemStack.getItem() instanceof ShovelItem && ItemUtil.isUsable(itemStack)).max(Comparator.comparingInt(itemStack -> (int)(ItemUtil.getDigSpeed(itemStack) * 100.0f))).orElse(null);
    }

    public static float getBestCrossbowScore() {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && itemStack.getItem() instanceof CrossbowItem && ItemUtil.isUsable(itemStack)).map(ItemUtil::getCrossbowScore).max(Float::compareTo).orElse(0.0f);
    }

    public static ItemStack getBestCrossbow() {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && itemStack.getItem() instanceof CrossbowItem && ItemUtil.isUsable(itemStack)).max(Comparator.comparingInt(itemStack -> (int)(ItemUtil.getCrossbowScore(itemStack) * 100.0f))).orElse(null);
    }

    public static float getBestBowScore() {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && itemStack.getItem() instanceof BowItem && ItemUtil.isUsable(itemStack)).map(ItemUtil::getBowScore).max(Float::compareTo).orElse(0.0f);
    }

    public static ItemStack getBestBow() {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && itemStack.getItem() instanceof BowItem && ItemUtil.isUsable(itemStack)).max(Comparator.comparingInt(itemStack -> (int)(ItemUtil.getBowScore(itemStack) * 100.0f))).orElse(null);
    }

    public static float getBestBowScoreAlt() {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && itemStack.getItem() instanceof BowItem && ItemUtil.isUsable(itemStack)).map(ItemUtil::getBowScoreAlt).max(Float::compareTo).orElse(0.0f);
    }

    public static ItemStack getBestBowAlt() {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && itemStack.getItem() instanceof BowItem && ItemUtil.isUsable(itemStack)).max(Comparator.comparingInt(itemStack -> (int)(ItemUtil.getBowScoreAlt(itemStack) * 100.0f))).orElse(null);
    }

    public static boolean isGoodBow(ItemStack itemStack) {
        return ItemUtil.getBowScore(itemStack) > 10.0f && ItemUtil.isUsable(itemStack);
    }

    public static boolean isGoodBowAlt(ItemStack itemStack) {
        return ItemUtil.getBowScoreAlt(itemStack) > 10.0f && ItemUtil.isUsable(itemStack);
    }

    public static boolean hasItem(Item item) {
        return ItemUtil.getAllItems().stream().anyMatch(itemStack -> !itemStack.isEmpty() && itemStack.getItem() == item);
    }

    public static int countItem(Item item) {
        return ItemUtil.getAllItems().stream().filter(itemStack -> !itemStack.isEmpty() && itemStack.getItem() == item).mapToInt(ItemStack::getCount).sum();
    }

    public static boolean isUsable(ItemStack itemStack) {
        if (!itemStack.isEmpty()) {
            if (itemStack.getItem() instanceof PlayerHeadItem) {
                return false;
            }
            String displayName = itemStack.getDisplayName().getString();
            if (displayName.contains("Click")) {
                return false;
            }
            if (displayName.contains("Right")) {
                return false;
            }
            if (displayName.contains("点击")) {
                return false;
            }
            if (displayName.contains("Teleport")) {
                return false;
            }
            if (displayName.contains("使用")) {
                return false;
            }
            if (displayName.contains("传送")) {
                return false;
            }
            return !displayName.contains("再来");
        }
        return true;
    }

    public static int findItemInRange(int startSlot, int endSlot, Item item) {
        if (mc.player == null) {
            return -1;
        }
        for (int i = startSlot; i < endSlot; ++i) {
            ItemStack itemStack = mc.player.getInventory().getItem(i);
            if (itemStack.isEmpty() || itemStack.getItem() != item) continue;
            return i;
        }
        return -1;
    }

    @Generated
    private ItemUtil() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}