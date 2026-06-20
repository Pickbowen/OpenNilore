package shit.nilore.event.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import lombok.Generated;
import net.minecraft.client.gui.GuiGraphics;
import shit.nilore.event.EventMarker;
import shit.nilore.render.DrawContext;

import java.util.Objects;

public record GlRenderEvent(GuiGraphics guiGraphics, PoseStack poseStack, DrawContext drawContext)
        implements EventMarker {
    @Override
    @Generated
    public GuiGraphics guiGraphics() {
        return this.guiGraphics;
    }

    @Override
    @Generated
    public PoseStack poseStack() {
        return this.poseStack;
    }

    @Override
    @Generated
    public DrawContext drawContext() {
        return this.drawContext;
    }

    @Generated
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof GlRenderEvent otherEvent)) {
            return false;
        }
        if (!otherEvent.canEqual(this)) {
            return false;
        }
        GuiGraphics thisGui = this.guiGraphics();
        GuiGraphics otherGui = otherEvent.guiGraphics();
        if (!Objects.equals(thisGui, otherGui)) {
            return false;
        }
        PoseStack thisPose = this.poseStack();
        PoseStack otherPose = otherEvent.poseStack();
        if (!Objects.equals(thisPose, otherPose)) {
            return false;
        }
        DrawContext thisCtx = this.drawContext();
        DrawContext otherCtx = otherEvent.drawContext();
        return !(!Objects.equals(thisCtx, otherCtx));
    }

    @Generated
    protected boolean canEqual(Object other) {
        return other instanceof GlRenderEvent;
    }

    @Generated
    public int hashCode() {
        int prime = 59;
        int result = 1;
        GuiGraphics gui = this.guiGraphics();
        result = result * 59 + (gui == null ? 43 : gui.hashCode());
        PoseStack pose = this.poseStack();
        result = result * 59 + (pose == null ? 43 : pose.hashCode());
        DrawContext ctx = this.drawContext();
        result = result * 59 + (ctx == null ? 43 : ctx.hashCode());
        return result;
    }

    @Generated
    public String toString() {
        String ctxStr = String.valueOf(this.drawContext());
        String poseStr = String.valueOf(this.poseStack());
        String guiStr = String.valueOf(this.guiGraphics());
        return "GlRenderEvent(guiGraphics=" + guiStr + ", stack=" + poseStr + ", context=" + ctxStr + ")";
    }

    @Generated
    public GlRenderEvent {
    }
}