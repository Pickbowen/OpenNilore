package client.nilore.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import asm.patchify.annotation.Transform;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import org.lwjgl.glfw.GLFW;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import client.nilore.ClientBase;
import client.nilore.NiloreClient;
import client.nilore.asm.Bootstrap;
import client.nilore.hud.HudElement;
import client.nilore.manager.CommandManager;
import client.nilore.modules.Module;
import client.nilore.modules.impl.render.BetterChat;
import client.nilore.utils.misc.ReflectionUtil;

@Patch(ChatScreen.class)
public class ChatScreenPatch {
    @Inject(method = "render", desc = "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V")
    public static void onRender(ChatScreen screen, GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo callbackInfo) {
        try {
            for (HudElement element : NiloreClient.getInstance().getHudManager().getHudElements().stream().filter(Module::isEnabled).toList()) {
                if (!element.isDragging()) continue;
                element.mouseDragged(mouseX, mouseY);
                boolean leftDown = GLFW.glfwGetMouseButton(ClientBase.mc.getWindow().getWindow(), 0) == 1;
                if (!leftDown) {
                    element.setDragging(false);
                }
            }
        } catch (Exception exception) {
            ClientBase.logger.error(exception);
            ClientBase.logger.error(exception.getMessage());
        }
    }

    /**
     * Masks the vanilla input-bar fill while BetterChat draws its own unified background.
     *
     * <p>Zeroing {@code textBackgroundOpacity} instead is not an option: that option's update
     * callback calls {@code ChatComponent.rescaleChat()}, which resets the chat scroll position and
     * rebuilds every wrapped line. Doing that once per frame made scrolling snap straight back and
     * cost a full rebuild per frame. Rewriting the colour argument leaves the call site's shape
     * exactly as it was, so nothing else in the render pass is disturbed.
     */
    @Transform(method = "render", desc = "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V")
    public static void transformInputBackground(MethodNode methodNode) {
        String owner = "net/minecraft/client/gui/GuiGraphics";
        String fillName = Bootstrap.remapMethod(owner, "fill", "(IIIII)V");
        for (AbstractInsnNode insn : methodNode.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEVIRTUAL) {
                continue;
            }
            if (!call.owner.equals(owner) || !call.desc.equals("(IIIII)V")) {
                continue;
            }
            if (!call.name.equals(fillName) && !call.name.equals("fill")) {
                continue;
            }
            methodNode.instructions.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    Type.getInternalName(ChatScreenPatch.class), "maskInputBackgroundColor", "(I)I", false));
        }
    }

    public static int maskInputBackgroundColor(int color) {
        BetterChat betterChat = BetterChat.INSTANCE;
        if (betterChat == null || !betterChat.replacesChatText() || !betterChat.drawsBackgroundThisFrame()) {
            return color;
        }
        return 0;
    }


    @Inject(method = "render", desc = "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At(At.Type.TAIL))
    public static void onRenderTail(ChatScreen screen, GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo callbackInfo) {
        ChatScreenPatch.renderCommandSuggestions(graphics);
    }

    /** Mirrors the vanilla suggestion popup for client "." commands, which the server never sees. */
    private static void renderCommandSuggestions(GuiGraphics graphics) {
        if (!NiloreClient.isReady()) {
            return;
        }
        try {
            EditBox input = (EditBox) ReflectionUtil.getStaticField(
                    ClientBase.mc.screen, "input", "net/minecraft/client/gui/screens/ChatScreen");
            if (input == null) {
                return;
            }
            String value = input.getValue();
            if (!value.startsWith(CommandManager.PREFIX)) {
                return;
            }
            List<String> candidates = NiloreClient.getInstance().getCommandManager().getCandidates(value);
            if (candidates.isEmpty()) {
                return;
            }
            var font = ClientBase.mc.font;
            int rows = Math.min(candidates.size(), 10);
            int width = 0;
            for (int i = 0; i < rows; ++i) {
                width = Math.max(width, font.width(candidates.get(i)));
            }
            width += 4;
            int guiHeight = ClientBase.mc.getWindow().getGuiScaledHeight();
            int baseY = guiHeight - 16 - 12 * rows;
            int baseX = 4;
            // Same metrics and colours as vanilla's suggestion list: 12px rows, 0xD0000000 fill,
            // the entry Tab would insert highlighted in yellow.
            graphics.fill(baseX, baseY, baseX + width, baseY + 12 * rows, 0xD0000000);
            for (int i = 0; i < rows; ++i) {
                graphics.drawString(font, candidates.get(i), baseX + 1, baseY + 2 + 12 * i,
                        i == 0 ? 0xFFFFFF00 : 0xFFAAAAAA, false);
            }
        } catch (Throwable throwable) {
            ClientBase.logger.error(throwable.getMessage(), throwable);
        }
    }

    @Inject(method = "keyPressed", desc = "(III)Z", at = @At(At.Type.HEAD))
    public static void onKeyPressed(ChatScreen screen, int keyCode, int scanCode, int modifiers, CallbackInfo callbackInfo) {
        if (!NiloreClient.isReady() || keyCode != GLFW.GLFW_KEY_TAB) {
            return;
        }
        try {
            EditBox input = (EditBox) ReflectionUtil.getStaticField(screen, "input", "net/minecraft/client/gui/screens/ChatScreen");
            if (input == null || !input.getValue().startsWith(CommandManager.PREFIX)) {
                return;
            }
            String completed = NiloreClient.getInstance().getCommandManager().tabComplete(input.getValue());
            if (completed == null) {
                return;
            }
            input.setValue(completed);
            input.setCursorPosition(completed.length());
            callbackInfo.cancel();
            callbackInfo.result = Boolean.TRUE;
        } catch (Throwable throwable) {
            ClientBase.logger.error(throwable.getMessage(), throwable);
        }
    }

    @Inject(method = "mouseClicked", desc = "(DDI)Z")
    public static void onMouseClicked(double mouseX, double mouseY, int button, CallbackInfo callbackInfo) {
        try {
            for (HudElement element : NiloreClient.getInstance().getHudManager().getHudElements().stream().filter(Module::isEnabled).toList()) {
                if (element.mousePressed((int) mouseX, (int) mouseY, button)) {
                    break;
                }
            }
        } catch (Exception exception) {
            ClientBase.logger.error(exception);
            ClientBase.logger.error(exception.getMessage());
        }
    }
}
