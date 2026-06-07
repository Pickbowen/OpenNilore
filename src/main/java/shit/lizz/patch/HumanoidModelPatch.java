package shit.lizz.patch;

import asm.patchify.annotation.Patch;
import asm.patchify.annotation.Transform;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import shit.lizz.ClientBase;
import shit.lizz.LizzClient;
import shit.lizz.event.impl.CameraPitchEvent;

@Patch(HumanoidModel.class)
public class HumanoidModelPatch {
    public static CameraPitchEvent onPitchRender(LivingEntity entity, float pitch) {
        if (LizzClient.isReady() && entity == ClientBase.mc.player && ClientBase.mc.level != null) {
            return (CameraPitchEvent) LizzClient.instance.getEventBus().call(new CameraPitchEvent(pitch));
        }
        return new CameraPitchEvent(pitch);
    }

    @Transform(method = "setupAnim", desc = "(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V")
    public static void transformSetupAnim(MethodNode methodNode) {
        InsnList prelude = new InsnList();
        int newLocalIndex = 7;
        prelude.add(new VarInsnNode(Opcodes.ALOAD, 1));
        prelude.add(new VarInsnNode(Opcodes.FLOAD, 6));
        prelude.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                Type.getInternalName(HumanoidModelPatch.class),
                "onPitchRender",
                "(Lnet/minecraft/world/entity/LivingEntity;F)L" + CameraPitchEvent.class.getName().replace(".", "/") + ";",
                false));
        prelude.add(new VarInsnNode(Opcodes.ASTORE, newLocalIndex));

        for (int i = 0; i < methodNode.instructions.size(); i++) {
            AbstractInsnNode insn = methodNode.instructions.get(i);
            if (insn instanceof VarInsnNode varInsn && varInsn.var >= newLocalIndex) {
                varInsn.var += newLocalIndex;
            }
            if (insn instanceof VarInsnNode varInsn && varInsn.var == 6) {
                InsnList replacement = new InsnList();
                replacement.add(new VarInsnNode(Opcodes.ALOAD, newLocalIndex));
                replacement.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        CameraPitchEvent.class.getName().replace(".", "/"),
                        "getPitch",
                        "()F"));
                methodNode.instructions.insert(insn, replacement);
                methodNode.instructions.remove(insn);
            }
        }
        methodNode.instructions.insert(prelude);
    }
}
