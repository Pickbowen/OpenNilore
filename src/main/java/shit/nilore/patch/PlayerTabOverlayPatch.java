package shit.nilore.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import asm.patchify.annotation.Transform;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import shit.nilore.ClientBase;
import shit.nilore.NiloreClient;
import shit.nilore.event.impl.ChatReceiveEvent;
import shit.nilore.hud.TabListInfo;
import shit.nilore.modules.impl.render.Watermark;
import shit.nilore.settings.impl.ModeSetting;
import shit.nilore.utils.misc.ReflectionUtil;

@Patch(PlayerTabOverlay.class)
public class PlayerTabOverlayPatch extends ClientBase {
    private static final ThreadLocal<Boolean> renderState = ThreadLocal.withInitial(() -> false);

    public static List<FormattedCharSequence> hookHeader(Font font, FormattedText text, int width) {
        Component component = (Component) text;
        ChatReceiveEvent event = new ChatReceiveEvent(ChatReceiveEvent.MessageType.SYSTEM, component);
        if (NiloreClient.isReady()) {
            NiloreClient.getInstance().getEventBus().call(event);
        }
        return font.split(event.getComponent(), width);
    }

    public static List<FormattedCharSequence> hookFooter(Font font, FormattedText text, int width) {
        Component component = (Component) text;
        ChatReceiveEvent event = new ChatReceiveEvent(ChatReceiveEvent.MessageType.CHAT, component);
        if (NiloreClient.isReady()) {
            NiloreClient.getInstance().getEventBus().call(event);
        }
        return font.split(event.getComponent(), width);
    }

    public static Component hookName(PlayerTabOverlay overlay, PlayerInfo info) {
        Component displayName = overlay.getNameForDisplay(info);
        ChatReceiveEvent event = new ChatReceiveEvent(ChatReceiveEvent.MessageType.NAME, displayName);
        if (NiloreClient.isReady()) {
            NiloreClient.getInstance().getEventBus().call(event);
        }
        return event.getComponent();
    }

    @Transform(
            method = "render",
            desc = "(Lnet/minecraft/client/gui/GuiGraphics;ILnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/world/scores/Objective;)V"
    )
    public static void transformRender(MethodNode methodNode) {
        InsnList instructions = methodNode.instructions;
        int splitCount = 0;
        for (AbstractInsnNode insn : instructions) {
            if (!(insn instanceof MethodInsnNode methodInsn)) continue;
            if (methodInsn.owner.equals("net/minecraft/client/gui/Font")
                    && methodInsn.name.equals(ReflectionUtil.getMappedFieldName(Font.class, "split"))
                    && methodInsn.desc.equals("(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;")) {
                splitCount++;
                String hookName = splitCount == 1 ? "hookHeader" : (splitCount == 2 ? "hookFooter" : null);
                if (hookName == null) continue;
                MethodInsnNode replacement = new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        Type.getInternalName(PlayerTabOverlayPatch.class),
                        hookName,
                        "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;",
                        false);
                instructions.set(methodInsn, replacement);
            }
            if (methodInsn.owner.equals("net/minecraft/client/gui/components/PlayerTabOverlay")
                    && methodInsn.name.equals("getNameForDisplay")
                    && methodInsn.desc.equals("(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;")) {
                MethodInsnNode replacement = new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        Type.getInternalName(PlayerTabOverlayPatch.class),
                        "hookName",
                        "(Lnet/minecraft/client/gui/components/PlayerTabOverlay;Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;",
                        false);
                instructions.set(methodInsn, replacement);
            }
        }
    }

    @Inject(
            method = "render",
            desc = "(Lnet/minecraft/client/gui/GuiGraphics;ILnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/world/scores/Objective;)V",
            at = @At(At.Type.HEAD)
    )
    public static void onRenderPre(PlayerTabOverlay overlay, GuiGraphics graphics, int width, Scoreboard scoreboard, Objective objective, CallbackInfo callbackInfo) {
        try {
            TabListInfo.header = (Component) ReflectionUtil.getStaticField(overlay, "header", "net/minecraft/client/gui/components/PlayerTabOverlay");
            TabListInfo.footer = (Component) ReflectionUtil.getStaticField(overlay, "footer", "net/minecraft/client/gui/components/PlayerTabOverlay");
        } catch (Exception ignored) {
        }
        renderState.set(false);
        if (!NiloreClient.isReady() || NiloreClient.getInstance().getModuleManager() == null) return;
        Watermark watermark = (Watermark) NiloreClient.getInstance().getModuleManager().getModule(Watermark.class);
        if (watermark == null || !watermark.isEnabled() || !mc.options.keyPlayerList.isDown()) return;
        try {
            Field styleField = Watermark.class.getDeclaredField("styleSetting");
            styleField.setAccessible(true);
            ModeSetting style = (ModeSetting) styleField.get(watermark);
            if ("DynamicIsland".equals(style.getValue())) {
                callbackInfo.cancel();
            } else {
                renderState.set(true);
                graphics.pose().pushPose();
                graphics.pose().translate(0.0f, 30.0f, 0.0f);
            }
        } catch (Exception ignored) {
        }
    }

    @Inject(
            method = "render",
            desc = "(Lnet/minecraft/client/gui/GuiGraphics;ILnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/world/scores/Objective;)V",
            at = @At(At.Type.TAIL)
    )
    public static void onRenderPost(PlayerTabOverlay overlay, GuiGraphics graphics, int width, Scoreboard scoreboard, Objective objective, CallbackInfo callbackInfo) {
        if (renderState.get()) {
            graphics.pose().popPose();
        }
    }
}
