package shit.lizz.patch;

import asm.patchify.annotation.Patch;
import asm.patchify.annotation.Transform;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import shit.lizz.ClientBase;
import shit.lizz.modules.impl.render.NameProtect;
import shit.lizz.utils.misc.ReflectionUtil;

@Patch(FriendlyByteBuf.class)
public class FriendlyByteBufPatch extends ClientBase {
    public static MutableComponent readUtfWithNameProtection(String json) {
        String filtered = NameProtect.replacePlayerName(json);
        return Component.Serializer.fromJson(filtered);
    }

    @Transform(method = "readComponent", desc = "()Lnet/minecraft/network/chat/Component;")
    public static void transformMethod(MethodNode methodNode) {
        InsnList instructions = methodNode.instructions;
        AbstractInsnNode targetCall = null;
        for (AbstractInsnNode insn : instructions) {
            if (!(insn instanceof MethodInsnNode methodInsn)) continue;
            if (!methodInsn.owner.equals("net/minecraft/network/chat/Component$Serializer")) continue;
            if (!methodInsn.name.equals(ReflectionUtil.getMappedMethodName(Component.Serializer.class, "fromJson", "(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;"))) continue;
            if (!methodInsn.desc.equals("(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;")) continue;
            targetCall = insn;
            break;
        }
        if (targetCall == null) return;
        MethodInsnNode replacement = new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                Type.getInternalName(FriendlyByteBufPatch.class),
                "readUtfWithNameProtection",
                "(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;",
                false);
        instructions.set(targetCall, replacement);
    }
}
