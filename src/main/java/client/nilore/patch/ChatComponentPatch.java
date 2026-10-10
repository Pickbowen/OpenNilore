package client.nilore.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import client.nilore.ClientBase;
import client.nilore.modules.impl.render.BetterChat;

@Patch(ChatComponent.class)
public class ChatComponentPatch {
    @Inject(method = "render", desc = "(Lnet/minecraft/client/gui/GuiGraphics;III)V", at = @At(At.Type.HEAD))
    public static void onRender(ChatComponent chat, GuiGraphics graphics, int tickCount, int mouseX, int mouseY, CallbackInfo callbackInfo) {
        BetterChat betterChat = BetterChat.INSTANCE;
        if (betterChat == null || !betterChat.replacesChatText()) {
            return;
        }
        try {
            callbackInfo.cancel();
            betterChat.renderChat(graphics, chat, tickCount);
        } catch (Throwable throwable) {
            ClientBase.logger.error(throwable.getMessage(), throwable);
        }
    }

    /**
     * Keeps click hit-testing on the shifted lines. The custom font path draws every line a fixed
     * distance lower, so the vanilla screen-to-chat mapping has to subtract that offset again or
     * links are only clickable one row above where they appear.
     */
    @Inject(method = "screenToChatY", desc = "(D)D", at = @At(At.Type.HEAD))
    public static void onScreenToChatY(ChatComponent chat, double mouseY, CallbackInfo callbackInfo) {
        BetterChat betterChat = BetterChat.INSTANCE;
        if (betterChat == null || !betterChat.replacesChatText() || betterChat.usesMinecraftFont()) {
            return;
        }
        if (ClientBase.mc.options == null || ClientBase.mc.getWindow() == null) {
            return;
        }
        double scale = chat.getScale();
        int lineHeight = (int) (9.0D * (ClientBase.mc.options.chatLineSpacing().get() + 1.0D));
        if (scale <= 0.0D || lineHeight <= 0) {
            return;
        }
        int guiHeight = ClientBase.mc.getWindow().getGuiScaledHeight();
        double adjusted = mouseY - BetterChat.LINE_DROP * scale - betterChat.textYOffset();
        callbackInfo.cancel();
        callbackInfo.result = ((double) guiHeight - adjusted - 40.0D) / (scale * (double) lineHeight);
    }
}
