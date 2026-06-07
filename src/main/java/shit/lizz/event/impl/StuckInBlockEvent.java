package shit.lizz.event.impl;

import lombok.Getter;
import lombok.Setter;
import lombok.Generated;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import shit.lizz.event.Event;

import java.util.Objects;

public class StuckInBlockEvent
extends Event {
    @Getter @Setter
    private BlockState blockState;
    @Getter @Setter
    private Vec3 motion;

    @Generated
    public String toString() {
        return "StuckInBlockEvent(state=" + this.getBlockState() + ", stuckSpeedMultiplier=" + this.getMotion() + ")";
    }

    @Generated
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof StuckInBlockEvent otherEvent)) {
            return false;
        }
        if (!otherEvent.canEqual(this)) {
            return false;
        }
        BlockState thisState = this.getBlockState();
        BlockState otherState = otherEvent.getBlockState();
        if (!Objects.equals(thisState, otherState)) {
            return false;
        }
        Vec3 thisMotion = this.getMotion();
        Vec3 otherMotion = otherEvent.getMotion();
        return !(!Objects.equals(thisMotion, otherMotion));
    }

    @Generated
    protected boolean canEqual(Object other) {
        return other instanceof StuckInBlockEvent;
    }

    @Generated
    public int hashCode() {
        int prime = 59;
        int result = 1;
        BlockState state = this.getBlockState();
        result = result * 59 + (state == null ? 43 : state.hashCode());
        Vec3 motion = this.getMotion();
        result = result * 59 + (motion == null ? 43 : motion.hashCode());
        return result;
    }

    @Generated
    public StuckInBlockEvent(BlockState blockState, Vec3 motion) {
        this.blockState = blockState;
        this.motion = motion;
    }
}