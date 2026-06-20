package shit.nilore.utils.game;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import shit.nilore.utils.game.ItemUtil;
import shit.nilore.utils.misc.ChatUtil;

public class ItemAlertTracker {
    private static final Map<UUID, Integer> trackedItems;
    private static final Map<UUID, Set<Item>> alertedItems;
    private static final ConcurrentHashMap<Object, Set<ItemStack>> entityItems;
    private static final String ALERT_FORMAT;

    public static boolean isNewItem(ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return false;
        }
        return ItemUtil.isOtherCheat(itemStack) || ItemUtil.isEnchantedGoldenApple(itemStack) || ItemUtil.isEndCrystal(itemStack) || ItemUtil.isKBSlimeBall(itemStack) || ItemUtil.isKBStick(itemStack) || ItemUtil.getPunchLevel(itemStack) > 2 && itemStack.getItem() instanceof BowItem || ItemUtil.getPowerLevel(itemStack) > 3 && itemStack.getItem() instanceof BowItem;
    }

    public static void trackPlayerItem(Player player, ItemStack itemStack) {
        if (!ItemUtil.isOtherCheat(itemStack)) {
            return;
        }
        int damageValue = itemStack.getDamageValue();
        UUID playerId = player.getUUID();
        Integer prevDamage = trackedItems.get(playerId);
        if (prevDamage != null && damageValue > prevDamage) {
            ChatUtil.print(String.format(ALERT_FORMAT, new Object[]{player.getName().getString()}));
            alertedItems.computeIfAbsent(playerId, uUID -> new HashSet<>()).add(itemStack.getItem());
        }
        trackedItems.put(playerId, damageValue);
    }

    public static void trackEntityItem(Object entityKey, ItemStack itemStack) {
        if (!ItemAlertTracker.isNewItem(itemStack)) {
            return;
        }
        Set<ItemStack> stacks = entityItems.computeIfAbsent(entityKey, key -> new CopyOnWriteArraySet<>());
        if (stacks.stream().noneMatch(existing -> ItemStack.matches(existing, itemStack))) {
            stacks.add(itemStack);
        }
    }

    public static Set<ItemStack> getEntityItems(Object entityKey) {
        return entityItems.getOrDefault(entityKey, Collections.emptySet());
    }

    public static boolean hasItem(UUID playerId, Item item) {
        Set<Item> items = alertedItems.getOrDefault(playerId, Collections.emptySet());
        return items.contains(item);
    }

    public static void clear() {
        trackedItems.clear();
        alertedItems.clear();
        entityItems.clear();
    }

    public static void removeEntity(Object entityKey) {
        entityItems.remove(entityKey);
    }

    public static void updateItems(Set<?> activeEntities) {
        entityItems.keySet().removeIf(entityKey -> !activeEntities.contains(entityKey));
    }

    public static Integer getItemCount(UUID playerId) {
        return trackedItems.get(playerId);
    }

    public static void setItemCount(UUID playerId, int count) {
        trackedItems.put(playerId, count);
    }

    static {
        ALERT_FORMAT = "§c[ALERT] §f%s used a God Axe!";
        trackedItems = new ConcurrentHashMap<>();
        alertedItems = new ConcurrentHashMap<>();
        entityItems = new ConcurrentHashMap<>();
    }
}
