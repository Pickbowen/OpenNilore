package shit.nilore.event.impl;

import lombok.Getter;
import lombok.Setter;
import lombok.Generated;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import shit.nilore.event.EventMarker;

import java.util.Objects;

public class UpdateHeldItemEvent
implements EventMarker {
    @Getter
    private final InteractionHand hand;
    @Getter @Setter
    private ItemStack itemStack;

    @Generated
    public UpdateHeldItemEvent(InteractionHand hand, ItemStack itemStack) {
        this.hand = hand;
        this.itemStack = itemStack;
    }

    @Generated
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof UpdateHeldItemEvent otherEvent)) {
            return false;
        }
        if (!otherEvent.canEqual(this)) {
            return false;
        }
        InteractionHand thisHand = this.getHand();
        InteractionHand otherHand = otherEvent.getHand();
        if (!Objects.equals(thisHand, otherHand)) {
            return false;
        }
        ItemStack thisStack = this.getItemStack();
        ItemStack otherStack = otherEvent.getItemStack();
        return !(!Objects.equals(thisStack, otherStack));
    }

    @Generated
    protected boolean canEqual(Object other) {
        return other instanceof UpdateHeldItemEvent;
    }

    @Generated
    public int hashCode() {
        int prime = 59;
        int result = 1;
        InteractionHand hand = this.getHand();
        result = result * 59 + (hand == null ? 43 : hand.hashCode());
        ItemStack stack = this.getItemStack();
        result = result * 59 + (stack == null ? 43 : stack.hashCode());
        return result;
    }

    @Generated
    public String toString() {
        return "UpdateHeldItemEvent(hand=" + this.getHand() + ", item=" + this.getItemStack() + ")";
    }
}