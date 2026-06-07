package shit.lizz.event.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import lombok.Generated;
import net.minecraft.client.gui.GuiGraphics;
import shit.lizz.event.EventMarker;

import java.util.Objects;

public record Render2DEvent(PoseStack poseStack, GuiGraphics guiGraphics, float partialTick)
        implements EventMarker {
    @Override
    @Generated
    public PoseStack poseStack() {
        return this.poseStack;
    }

    @Override
    @Generated
    public GuiGraphics guiGraphics() {
        return this.guiGraphics;
    }

    @Override
    @Generated
    public float partialTick() {
        return this.partialTick;
    }

    @Generated
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof Render2DEvent otherEvent)) {
            return false;
        }
        if (!otherEvent.canEqual(this)) {
            return false;
        }
        if (Float.compare(this.partialTick(), otherEvent.partialTick()) != 0) {
            return false;
        }
        PoseStack thisPose = this.poseStack();
        PoseStack otherPose = otherEvent.poseStack();
        if (!Objects.equals(thisPose, otherPose)) {
            return false;
        }
        GuiGraphics thisGui = this.guiGraphics();
        GuiGraphics otherGui = otherEvent.guiGraphics();
        return !(!Objects.equals(thisGui, otherGui));
    }

    @Generated
    protected boolean canEqual(Object other) {
        return other instanceof Render2DEvent;
    }

    @Generated
    public int hashCode() {
        int prime = 59;
        int result = 1;
        result = result * 59 + Float.floatToIntBits(this.partialTick());
        PoseStack pose = this.poseStack();
        result = result * 59 + (pose == null ? 43 : pose.hashCode());
        GuiGraphics gui = this.guiGraphics();
        result = result * 59 + (gui == null ? 43 : gui.hashCode());
        return result;
    }

    @Generated
    public String toString() {
        float partial = this.partialTick();
        String guiStr = String.valueOf(this.guiGraphics());
        String poseStr = String.valueOf(this.poseStack());
        return "Render2DEvent(stack=" + poseStr + ", guiGraphics=" + guiStr + ", partialTicks=" + partial + ")";
    }

    @Generated
    public Render2DEvent {
    }
}