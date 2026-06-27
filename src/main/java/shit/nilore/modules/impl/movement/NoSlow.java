package shit.nilore.modules.impl.movement;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.datafixers.util.Pair;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import client.liquidbounce.event.impl.MotionEvent;
import client.liquidbounce.event.impl.PacketEvent;
import client.liquidbounce.event.impl.SlowdownEvent;
import client.liquidbounce.event.impl.TickEvent;
import client.liquidbounce.modules.Category;
import client.liquidbounce.modules.Module;
import client.liquidbounce.settings.impl.BooleanSetting;
import client.liquidbounce.settings.impl.ModeSetting;
import client.liquidbounce.settings.impl.NumberSetting;
import client.liquidbounce.utils.animation.Timer;
import client.liquidbounce.utils.misc.PacketUtil;
import client.liquidbounce.event.EventTarget;

public class NoSlow extends Module {
    public static NoSlow INSTANCE;
    public static boolean releaseItemSent;

    private enum Step { NONE, ARMED, EATING }

    public final ModeSetting mode             = new ModeSetting("Mode", "Grim V3", "NoSlow").withDefault("Grim V3");
    public final BooleanSetting bowNoSlow      = new BooleanSetting("Bow", false, this::isGrimSlowMode);
    public final BooleanSetting keepSprinting  = new BooleanSetting("Keep Sprinting", true);
    public final BooleanSetting crossbowNoSlow = new BooleanSetting("Crossbow", false);
    public final BooleanSetting foodNoSlow     = new BooleanSetting("Food", true);
    public final BooleanSetting potionNoSlow   = new BooleanSetting("Potion", true);
    public final BooleanSetting shieldNoSlow   = new BooleanSetting("Shield NoSlow", true);
    public final NumberSetting useItemTicks    = new NumberSetting("Use Item Ticks", 1, 1, 20, 1,
            () -> this.isGrimSlowMode() && this.bowNoSlow.getValue());

    private final Timer timer = new Timer();
    private final Queue<Packet<ClientGamePacketListener>> inboundQueue = new ArrayDeque<>();
    private InteractionHand useHand     = InteractionHand.MAIN_HAND;
    private InteractionHand lastUseHand = InteractionHand.MAIN_HAND;
    private InteractionHand pendingUseHand;
    private boolean didSwapHand;
    private boolean shouldReleaseItem;
    private int swapInitSlot;
    private int releaseTicksRemaining;
    private int pendingUseCount;
    private boolean isBlinking;
    private int blinkTicks;
    private int blinkDuration;
    private Step step = Step.NONE;
    private boolean hasSwapped = false;
    private boolean swapInArmed = false;
    private int noUseTicks = 0;
    private final Queue<Packet<?>> cached = new ConcurrentLinkedQueue<>();

    public NoSlow() {
        super("NoSlow", Category.MOVEMENT);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        releaseItemSent = false;
        this.releaseTicksRemaining = 0;
        this.reset();
        this.stopBlink();
        super.onEnable();
    }

    @Override
    public void onDisable() {
        this.release();
        this.stopBlink();
        releaseItemSent = false;
        this.didSwapHand = false;
        this.shouldReleaseItem = false;
        this.pendingUseHand = null;
        this.releaseTicksRemaining = 0;
        this.restoreUseKeyState();
        super.onDisable();
    }

    @EventTarget
    public void onSlowdown(SlowdownEvent event) {
        if (mc.player == null || !mc.player.isUsingItem()) return;
        ItemStack stack = mc.player.getUseItem();
        if (stack.isEmpty()) return;
        if (this.isNoSlowMode()) {
            this.handleGrimSlowdown(event, stack);
            return;
        }
        if (!this.isGrimSlowMode()) return;
        // NoC0FNoSlow: cancel slowdown when in EATING step
        if (step == Step.EATING) {
            event.setSlowDown(false);
            if (this.keepSprinting.getValue() && mc.player != null) {
                mc.player.setSprinting(true);
            }
            return;
        }
        if (!(Boolean) this.bowNoSlow.getValue()) return;
        if (!this.canSwapHands()) return;
        UseAnim anim = stack.getUseAnimation();
        if (anim == UseAnim.BOW && this.crossbowNoSlow.getValue()
                || anim == UseAnim.CROSSBOW && this.foodNoSlow.getValue()
                || this.isEatOrDrink(stack)
                || anim == UseAnim.BLOCK) {
            event.setSlowDown(false);
        }
        if (this.keepSprinting.getValue()) {
            mc.player.setSprinting(true);
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (mc.player == null) {
            this.release();
            this.stopBlink();
            return;
        }
        if (this.isBlinking) {
            ++this.blinkTicks;
        }
        // NoC0FNoSlow: reset offhand state if switching away from GrimV3 mode
        if (!this.isGrimSlowMode() && this.step != Step.NONE) {
            this.release();
        }
        // NoC0FNoSlow: offhand state machine tick logic
        if (this.isGrimSlowMode()) {
            if (step != Step.NONE && step != Step.EATING) {
                mc.options.keyUse.setDown(false);
            }

            if (step == Step.NONE) {
                if (mc.player.isUsingItem()
                        && mc.options.keyUse.isDown()
                        && isUsable(mc.player.getUseItem().getUseAnimation())) {
                    if (isLookingAtInteractableBlock()) {
                        return;
                    }
                    InteractionHand hand = mc.player.getUsedItemHand();
                    if (hand == InteractionHand.OFF_HAND
                            || (hand == InteractionHand.MAIN_HAND && mc.player.getOffhandItem().isEmpty())) {
                        step = Step.ARMED;
                        mc.options.keyUse.setDown(false);

                        if (mc.player.containerMenu != mc.player.inventoryMenu) {
                            mc.getConnection().send(
                                    new ServerboundContainerClosePacket(mc.player.containerMenu.containerId));
                        }
                    }
                }
            } else if (step == Step.EATING) {
                if (mc.player.isUsingItem()) {
                    noUseTicks = 0;
                } else {
                    noUseTicks++;
                    if (noUseTicks >= 5) {
                        release();
                    }
                }
            } else {
                noUseTicks = 0;
            }
        }
        if (this.releaseTicksRemaining > 0) {
            this.releaseUseKey();
            --this.releaseTicksRemaining;
            if (this.releaseTicksRemaining == 0) {
                this.restoreUseKeyState();
            }
        }
        if (this.pendingUseHand != null) {
            this.startUseItem(this.pendingUseHand, this.pendingUseCount);
            this.pendingUseHand = null;
            this.pendingUseCount = 0;
        }
        if (this.isBlinking && this.blinkTicks >= this.blinkDuration) {
            this.finishBlink();
            return;
        }
        if (this.isGrimSlowMode() && this.bowNoSlow.getValue() && this.didSwapHand && !this.isBlinking) {
            if (this.useHand != this.lastUseHand) {
                this.sendSwapOffhand();
            }
            releaseItemSent = true;
            this.didSwapHand = false;
            this.shouldReleaseItem = false;
            this.timer.reset();
            this.releaseTicksRemaining = this.useItemTicks.getValue().intValue();
            this.releaseUseKey();
            PacketUtil.sendQueued(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN));
            return;
        }
        if (this.isGrimSlowMode() && this.bowNoSlow.getValue() && this.shouldReleaseItem
                && mc.player.isUsingItem() && this.canSwapHands()) {
            this.shouldReleaseItem = false;
            this.startUseItemDefault(mc.player.getUsedItemHand());
        }
    }

    @EventTarget
    public void onMotion(MotionEvent event) {
        if (event.isPre() && this.isBlinking && this.blinkTicks >= this.blinkDuration && !this.didSwapHand) {
            this.stopBlink();
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (mc.player == null) {
            return;
        }
        if (event.isIncoming() && this.shouldQueuePacket(event.getPacket())) {
            event.setCancelled(true);
            return;
        }
        if (this.isGrimSlowMode()) {
            Packet<?> p = event.getPacket();

            if (!event.isIncoming()) {
                // Send packet handling
                if ((step == Step.ARMED || step == Step.EATING) && p instanceof ServerboundPongPacket) {
                    event.setCancelled(true);
                    cached.offer(p);

                    if (step == Step.ARMED && !swapInArmed) {
                        swapInArmed = true;
                        hasSwapped = true;
                        mc.getConnection().send(new ServerboundPlayerActionPacket(
                                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
                    }
                }

                if (step == Step.EATING
                        && p instanceof ServerboundPlayerActionPacket action
                        && action.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM) {
                    release();
                }
            } else {
                if (step == Step.ARMED && swapInArmed && p instanceof ClientboundContainerSetSlotPacket) {
                    swapInArmed = false;
                    mc.options.keyUse.setDown(true);
                    step = Step.EATING;
                }

                if (step != Step.NONE && p instanceof ClientboundPlayerPositionPacket) {
                    release();
                }
            }
        }
        if (event.getPacket() instanceof ServerboundPlayerActionPacket actionPacket
                && actionPacket.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM) {
            this.blinkTicks = Math.max(this.blinkTicks, 1);
        }
        if (event.getPacket() instanceof ServerboundUseItemOnPacket useOnPacket
                && this.didSwapHand
                && useOnPacket.getHand() == this.useHand
                && mc.player.getInventory().selected == this.swapInitSlot) {
            InteractionHand other = useOnPacket.getHand() == InteractionHand.MAIN_HAND
                    ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            PacketUtil.sendQueued(new ServerboundUseItemOnPacket(other, useOnPacket.getHitResult(), useOnPacket.getSequence()));
        }
        if (event.getPacket() instanceof ServerboundUseItemPacket usePacket) {
            if (this.didSwapHand || this.releaseTicksRemaining > 0) {
                event.setCancelled(true);
            } else if (this.isGrimSlowMode() && this.bowNoSlow.getValue()) {
                if (!this.timer.hasPassed(150.0f) && this.releaseTicksRemaining <= 0) {
                    event.setCancelled(true);
                } else if (!this.canSwapHands()) {
                    this.shouldReleaseItem = true;
                } else {
                    ItemStack handStack = mc.player.getItemInHand(usePacket.getHand());
                    UseAnim anim = handStack.getUseAnimation();
                    if ((anim == UseAnim.BOW && this.crossbowNoSlow.getValue())
                            || (anim == UseAnim.CROSSBOW && !CrossbowItem.isCharged(handStack) && this.foodNoSlow.getValue())) {
                        this.shouldReleaseItem = false;
                        this.startBlink(1);
                    } else if (this.isEatOrDrink(handStack)) {
                        this.shouldReleaseItem = false;
                        event.setCancelled(true);
                        this.pendingUseHand = usePacket.getHand();
                        this.pendingUseCount = usePacket.getSequence();
                    }
                }
            }
        }
    }

    private void handleGrimSlowdown(SlowdownEvent event, ItemStack stack) {
        Item item = stack.getItem();
        boolean isBow = item instanceof BowItem;
        boolean isCrossbow = item instanceof CrossbowItem;
        boolean isEdible = stack.isEdible();
        boolean isPotion = item instanceof PotionItem;
        if (isBow && this.crossbowNoSlow.getValue()) {
            event.setSlowDown(mc.player.tickCount % 3 != 0);
        } else if (isCrossbow && this.foodNoSlow.getValue()) {
            event.setSlowDown(mc.player.tickCount % 3 != 0);
        } else if (isEdible && this.potionNoSlow.getValue()
                || isPotion && this.shieldNoSlow.getValue()) {
            event.setSlowDown(mc.player.getUseItemRemainingTicks() >= 1
                    || mc.player.tickCount % 3 != 0);
        }
        if (this.keepSprinting.getValue()) {
            mc.player.setSprinting(true);
        }
    }

    private void startUseItem(InteractionHand hand, int count) {
        if (mc.player == null) return;
        if (isLookingAtInteractableBlock()) {
            return;
        }
        this.didSwapHand = true;
        this.lastUseHand = hand;
        this.swapInitSlot = mc.player.getInventory().selected;
        this.useHand = hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        this.sendSwapOffhand();
        if (count > 0) {
            PacketUtil.sendQueued(new ServerboundUseItemPacket(this.useHand, count));
        } else {
            PacketUtil.sendPredictiveDirect(seq -> new ServerboundUseItemPacket(this.useHand, seq));
        }
        this.startBlink(2);
    }

    private void startUseItemDefault(InteractionHand hand) {
        this.startUseItem(hand, 0);
    }

    private void finishBlink() {
        this.shouldReleaseItem = false;
        if (!this.isBlinking || !this.didSwapHand || mc.player == null) return;
        if (this.useHand != this.lastUseHand) {
            this.sendSwapOffhand();
        }
        releaseItemSent = true;
        this.didSwapHand = false;
        this.timer.reset();
        this.releaseTicksRemaining = this.useItemTicks.getValue().intValue();
        this.releaseUseKey();
        PacketUtil.sendQueued(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN));
    }

    private boolean canSwapHands() {
        if (mc.player == null) return false;
        ItemStack mainHand = mc.player.getMainHandItem();
        ItemStack offHand = mc.player.getOffhandItem();
        if (mainHand.isEmpty() || offHand.isEmpty()) return true;
        if (mainHand.getItem() == Items.ENCHANTED_GOLDEN_APPLE && offHand.getItem() == Items.GOLDEN_APPLE) return false;
        if (offHand.getItem() == Items.ENCHANTED_GOLDEN_APPLE && mainHand.getItem() == Items.GOLDEN_APPLE) return false;
        return mainHand.getItem() != offHand.getItem();
    }

    public static boolean isBlocking(Minecraft minecraft) {
        return INSTANCE != null && INSTANCE.isBlockingInternal(minecraft);
    }

    private boolean isBlockingInternal(Minecraft minecraft) {
        if (!this.isEnabled() || !this.isGrimSlowMode() || this.bowNoSlow.getValue()) return false;
        if (minecraft == null || minecraft.player == null || minecraft.hitResult == null) return false;
        if (minecraft.hitResult.getType() != HitResult.Type.BLOCK) return false;
        for (InteractionHand hand : InteractionHand.values()) {
            if (isUsable(minecraft.player.getItemInHand(hand).getUseAnimation())) return true;
        }
        return false;
    }

    // ===== NoC0FNoSlow methods =====

    private void reset() {
        step = Step.NONE;
        hasSwapped = false;
        swapInArmed = false;
        noUseTicks = 0;
        cached.clear();
    }

    private void release() {
        if (step == Step.NONE && cached.isEmpty() && !hasSwapped) {
            return;
        }
        step = Step.NONE;
        noUseTicks = 0;
        swapInArmed = false;
        if (mc.player == null || mc.getConnection() == null) {
            cached.clear();
            return;
        }
        while (!cached.isEmpty()) {
            mc.getConnection().send(cached.poll());
        }
        if (hasSwapped) {
            mc.getConnection().send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
            hasSwapped = false;
        }
    }

    private boolean isUsable(UseAnim action) {
        return action == UseAnim.EAT || action == UseAnim.DRINK || action == UseAnim.SPEAR;
    }
    private boolean isLookingAtInteractableBlock() {
        if (mc.player == null || mc.level == null) return false;
        if (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) return false;

        Vec3 hitVec = mc.hitResult.getLocation();
        BlockPos pos = new BlockPos((int) hitVec.x, (int) hitVec.y, (int) hitVec.z);

        if (mc.level.isEmptyBlock(pos)) return false;
        return !mc.player.isCrouching() || !mc.player.getMainHandItem().isEmpty();
    }

    private boolean isEatOrDrink(ItemStack stack) {
        if (stack.isEmpty()) return false;
        UseAnim anim = stack.getUseAnimation();
        Item item = stack.getItem();
        return anim == UseAnim.EAT && this.potionNoSlow.getValue()
                || anim == UseAnim.DRINK && this.shieldNoSlow.getValue()
                || item instanceof PotionItem && this.shieldNoSlow.getValue();
    }

    private void sendSwapOffhand() {
        PacketUtil.sendQueued(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
    }

    private void startBlink(int duration) {
        if (!this.isBlinking) this.blinkTicks = 0;
        this.isBlinking = true;
        this.blinkDuration = duration;
    }

    private void stopBlink() {
        this.flushInboundQueue();
        this.isBlinking = false;
        this.blinkTicks = 0;
        this.blinkDuration = 0;
    }

    private boolean shouldQueuePacket(Packet<?> packet) {
        if (!this.isBlinking || packet == null || mc.level == null || mc.getConnection() == null) return false;
        if (packet instanceof ClientboundPlayerPositionPacket
                || packet instanceof ClientboundLoginPacket
                || packet instanceof ClientboundRespawnPacket) {
            this.stopBlink();
            return false;
        }
        if (packet instanceof ClientboundEntityEventPacket evt) {
            Entity entity = evt.getEntity(mc.level);
            if (entity != null && (entity != mc.player || evt.getEventId() != 2)) return false;
        }
        if (!this.isBlinkablePacket(packet)) return false;
        this.queueInboundPacket(packet);
        return true;
    }

    private void queueInboundPacket(Packet<?> packet) {
        @SuppressWarnings("unchecked")
        Packet<ClientGamePacketListener> typed = (Packet<ClientGamePacketListener>) packet;
        this.inboundQueue.add(typed);
    }

    private void flushInboundQueue() {
        if (mc == null || mc.getConnection() == null) {
            this.inboundQueue.clear();
            return;
        }
        while (!this.inboundQueue.isEmpty()) {
            Packet<ClientGamePacketListener> packet = this.inboundQueue.poll();
            try {
                packet.handle(mc.getConnection());
            } catch (Exception e) {
                this.inboundQueue.clear();
                logger.error("Failed to flush packet", e);
                return;
            }
        }
    }

    private boolean isBlinkablePacket(Packet<?> packet) {
        if (packet instanceof ClientboundKeepAlivePacket || packet instanceof ClientboundPingPacket) {
            return true;
        }
        if (packet instanceof ClientboundSetEntityMotionPacket motion) {
            return mc.player != null && motion.getId() == mc.player.getId();
        }
        if (packet instanceof ClientboundContainerSetSlotPacket slotPacket) {
            return slotPacket.getSlot() == 45 || slotPacket.getContainerId() == 0;
        }
        if (packet instanceof ClientboundSetEquipmentPacket equipmentPacket) {
            for (Pair<EquipmentSlot, ItemStack> slot : equipmentPacket.getSlots()) {
                if (slot.getFirst() == EquipmentSlot.OFFHAND) {
                    return true;
                }
            }
        }
        return false;
    }

    private void releaseUseKey() {
        mc.options.keyUse.setDown(false);
        while (mc.options.keyUse.consumeClick()) {
        }
    }

    private void restoreUseKeyState() {
        if (mc == null || mc.options == null || mc.getWindow() == null) return;
        InputConstants.Key key = InputConstants.getKey(mc.options.keyUse.saveString());
        long window = mc.getWindow().getWindow();
        boolean down = key.getType() == InputConstants.Type.MOUSE
                ? GLFW.glfwGetMouseButton(window, key.getValue()) == 1
                : InputConstants.isKeyDown(window, key.getValue());
        mc.options.keyUse.setDown(down);
    }

    private boolean isGrimSlowMode() {
        return this.mode.is("Grim V3");
    }

    private boolean isNoSlowMode() {
        return this.mode.is("NoSlow");
    }

    private Packet<?> createUseItemPacket(int sequence) {
        return new ServerboundUseItemPacket(this.useHand, sequence);
    }
}