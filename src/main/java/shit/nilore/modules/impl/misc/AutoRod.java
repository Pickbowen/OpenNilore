package shit.nilore.modules.impl.misc;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;
import shit.nilore.event.impl.TickEvent;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.modules.impl.player.MidPearl;
import shit.nilore.settings.impl.ModeSetting;
import shit.nilore.settings.impl.NumberSetting;
import shit.nilore.utils.game.PlayerUtil;
import shit.nilore.event.EventTarget;

public class AutoRod
extends Module {
    public static AutoRod INSTANCE;
    private final ModeSetting mouseButton = new ModeSetting("Button", "Middle", "Mouse 4", "Mouse 5").withDefault("Mouse 4");
    private final NumberSetting delay = new NumberSetting("Delay", 2, 0, 20, 1);
    private boolean slotSwitched = false;
    private boolean isActive = false;
    private int previousSlot = -1;
    private int tickDelay = 0;

    public AutoRod() {
        super("AutoRod", Category.MISC);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.resetState();
    }

    @Override
    public void onDisable() {
        this.restoreSlot();
        this.resetState();
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        if (mc.player == null || mc.level == null) {
            this.resetState();
            return;
        }
        if (this.tickDelay > 0) {
            --this.tickDelay;
            if (this.tickDelay == 0) {
                this.restoreSlot();
                this.slotSwitched = false;
            }
            return;
        }
        boolean mouseDown = this.isMouseButtonDown();
        if (!mouseDown) {
            this.isActive = false;
            return;
        }
        if (this.isActive || this.isMidPearlBlocking()) {
            return;
        }
        this.isActive = true;
        int slot = this.findUsableItemSlot();
        if (slot == -1) {
            return;
        }
        this.previousSlot = mc.player.getInventory().selected;
        boolean isFishingRod = mc.player.getInventory().getItem(slot).getItem() == Items.FISHING_ROD;
        this.slotSwitched = true;
        this.selectHotbarSlot(slot);
        this.useItem();
        if (isFishingRod) {
            this.tickDelay = this.delay.getValue().intValue();
        }
        if (!isFishingRod || this.tickDelay <= 0) {
            this.restoreSlot();
            this.slotSwitched = false;
        }
    }

    public boolean isCurrentMouseButton(int button) {
        return this.getMouseButtonCode() == button;
    }

    public boolean isActiveOrPending() {
        return this.slotSwitched || this.tickDelay > 0;
    }

    public boolean shouldInterceptButton(int button) {
        return this.isCurrentMouseButton(button) && (this.isActiveOrPending() || this.findUsableItemSlot() != -1);
    }

    private boolean isMidPearlBlocking() {
        MidPearl midPearl = MidPearl.INSTANCE;
        return midPearl != null && midPearl.isEnabled() && midPearl.isInterceptButton(this.getMouseButtonCode());
    }

    private boolean isMouseButtonDown() {
        return GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), this.getMouseButtonCode()) == 1;
    }

    private int getMouseButtonCode() {
        return switch ((String) this.mouseButton.getValue()) {
            case "Mouse 5" -> 4;
            case "Middle" -> 2;
            default -> 3;
        };
    }

    private int findUsableItemSlot() {
        int rodSlot = this.findItemInHotbar(Items.FISHING_ROD);
        return rodSlot != -1 ? rodSlot : this.findThrowableSlot();
    }

    private int findItemInHotbar(Item item) {
        if (mc.player == null) {
            return -1;
        }
        int hotbarSize = Math.min(9, mc.player.getInventory().items.size());
        for (int i = 0; i < hotbarSize; ++i) {
            if (mc.player.getInventory().getItem(i).getItem() != item) continue;
            return i;
        }
        return -1;
    }

    private int findThrowableSlot() {
        if (mc.player == null) {
            return -1;
        }
        int hotbarSize = Math.min(9, mc.player.getInventory().items.size());
        for (int i = 0; i < hotbarSize; ++i) {
            if (!this.isThrowable(mc.player.getInventory().getItem(i).getItem())) continue;
            return i;
        }
        return -1;
    }

    private void selectHotbarSlot(int slot) {
        mc.player.getInventory().selected = slot;
        PlayerUtil.sendCarriedItem();
    }

    private void useItem() {
        if (mc.gameMode == null || !this.isUsableItem(mc.player.getMainHandItem().getItem())) {
            return;
        }
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private boolean isUsableItem(Item item) {
        return item == Items.FISHING_ROD || this.isThrowable(item);
    }

    private boolean isThrowable(Item item) {
        return item == Items.EGG || item == Items.SNOWBALL;
    }

    private void restoreSlot() {
        if (mc.player != null && this.previousSlot != -1) {
            mc.player.getInventory().selected = this.previousSlot;
            PlayerUtil.sendCarriedItem();
            this.previousSlot = -1;
        }
    }

    private void resetState() {
        this.slotSwitched = false;
        this.isActive = false;
        this.previousSlot = -1;
        this.tickDelay = 0;
    }
}