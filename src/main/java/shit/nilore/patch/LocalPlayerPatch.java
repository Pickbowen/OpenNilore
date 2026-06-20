package shit.nilore.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import asm.patchify.annotation.Transform;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import shit.nilore.NiloreClient;
import shit.nilore.event.impl.GameTickEvent;
import shit.nilore.event.impl.MotionEvent;
import shit.nilore.event.impl.SlowdownEvent;
import shit.nilore.event.impl.SprintEvent;
import shit.nilore.utils.misc.ReflectionUtil;

@Patch(LocalPlayer.class)
public class LocalPlayerPatch {
    private static MotionEvent currentMotionEvent;

    public static MotionEvent onMotion(double x, double y, double z, float yaw, float pitch, boolean onGround, boolean isPost) {
        if (currentMotionEvent == null) {
            currentMotionEvent = new MotionEvent(isPost, x, y, z, yaw, pitch, onGround);
        } else if (currentMotionEvent.isPost() && isPost) {
            currentMotionEvent.setPre(true);
        }
        MotionEvent event = currentMotionEvent;
        if (NiloreClient.isReady()) {
            NiloreClient.getInstance().getEventBus().call(currentMotionEvent);
            if (isPost) {
                currentMotionEvent = null;
            }
        }
        return event;
    }

    public static SlowdownEvent onSlowDown(boolean slow) {
        if (NiloreClient.isReady()) {
            return (SlowdownEvent) NiloreClient.instance.getEventBus().call(new SlowdownEvent(slow));
        }
        return new SlowdownEvent(slow);
    }

    @Transform(method = "aiStep", desc = "()V")
    public static void transformAiStep(MethodNode methodNode) {
        AbstractInsnNode targetCall = null;
        for (AbstractInsnNode insn : methodNode.instructions) {
            if (insn instanceof MethodInsnNode methodInsn
                    && methodInsn.name.equals(ReflectionUtil.getMappedMethodName(LocalPlayer.class, "isUsingItem", "()Z"))
                    && methodInsn.desc.equals("()Z")
                    && methodInsn.getNext() instanceof JumpInsnNode) {
                targetCall = insn;
                break;
            }
        }
        int slowdownLocalIndex = methodNode.maxLocals++;
        InsnList prelude = new InsnList();
        prelude.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prelude.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/player/LocalPlayer",
                ReflectionUtil.getMappedMethodName(LocalPlayer.class, "isUsingItem", "()Z"),
                "()Z",
                false));
        prelude.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                Type.getInternalName(LocalPlayerPatch.class),
                "onSlowDown",
                "(Z)L" + SlowdownEvent.class.getName().replace(".", "/") + ";",
                false));
        prelude.add(new VarInsnNode(Opcodes.ASTORE, slowdownLocalIndex));
        methodNode.instructions.insert(prelude);
        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ALOAD, slowdownLocalIndex));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                SlowdownEvent.class.getName().replace(".", "/"),
                "isSlowDown",
                "()Z",
                false));
        methodNode.instructions.insertBefore(targetCall, replacement);
        methodNode.instructions.remove(targetCall);
    }

    @Inject(
            method = "tick",
            desc = "()V",
            at = @At(value = At.Type.BEFORE_INVOKE, method = "net/minecraft/client/player/AbstractClientPlayer/tick", desc = "()V")
    )
    public static void onTick(LocalPlayer player, CallbackInfo callbackInfo) throws Throwable {
        if (NiloreClient.isReady()) {
            NiloreClient.getInstance().getEventBus().call(new SprintEvent());
        }
    }

    @Inject(method = "aiStep", desc = "()V")
    public static void onAiStep(LocalPlayer player, CallbackInfo callbackInfo) throws Throwable {
        if (NiloreClient.isReady()) {
            NiloreClient.getInstance().getEventBus().call(new GameTickEvent());
        }
    }

    @Transform(method = "sendPosition", desc = "()V")
    public static void transformSendPosition(MethodNode methodNode) throws Throwable {
        InsnList constructEvent = new InsnList();
        constructEvent.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructEvent.add(invokeGetter(Entity.class, "getX", "()D"));
        constructEvent.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructEvent.add(invokeGetter(Entity.class, "getY", "()D"));
        constructEvent.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructEvent.add(invokeGetter(Entity.class, "getZ", "()D"));
        constructEvent.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructEvent.add(invokeGetter(Entity.class, "getYRot", "()F"));
        constructEvent.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructEvent.add(invokeGetter(Entity.class, "getXRot", "()F"));
        constructEvent.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructEvent.add(invokeGetter(Entity.class, "onGround", "()Z"));
        constructEvent.add(new InsnNode(Opcodes.ICONST_0));
        constructEvent.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                Type.getInternalName(LocalPlayerPatch.class),
                "onMotion",
                "(DDDFFZZ)L" + MotionEvent.class.getName().replace(".", "/") + ";"));
        constructEvent.add(new VarInsnNode(Opcodes.ASTORE, 1));

        InsnList finalizeEvent = new InsnList();
        finalizeEvent.add(new VarInsnNode(Opcodes.ALOAD, 0));
        finalizeEvent.add(invokeGetter(Entity.class, "getX", "()D"));
        finalizeEvent.add(new VarInsnNode(Opcodes.ALOAD, 0));
        finalizeEvent.add(invokeGetter(Entity.class, "getY", "()D"));
        finalizeEvent.add(new VarInsnNode(Opcodes.ALOAD, 0));
        finalizeEvent.add(invokeGetter(Entity.class, "getZ", "()D"));
        finalizeEvent.add(new VarInsnNode(Opcodes.ALOAD, 0));
        finalizeEvent.add(invokeGetter(Entity.class, "getYRot", "()F"));
        finalizeEvent.add(new VarInsnNode(Opcodes.ALOAD, 0));
        finalizeEvent.add(invokeGetter(Entity.class, "getXRot", "()F"));
        finalizeEvent.add(new VarInsnNode(Opcodes.ALOAD, 0));
        finalizeEvent.add(invokeGetter(Entity.class, "onGround", "()Z"));
        finalizeEvent.add(new InsnNode(Opcodes.ICONST_1));
        finalizeEvent.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                Type.getInternalName(LocalPlayerPatch.class),
                "onMotion",
                "(DDDFFZZ)L" + MotionEvent.class.getName().replace(".", "/") + ";"));
        finalizeEvent.add(new InsnNode(Opcodes.POP));

        int newLocalsShift = 1;
        for (int i = 0; i < methodNode.instructions.size(); i++) {
            AbstractInsnNode insn = methodNode.instructions.get(i);
            if (insn instanceof VarInsnNode varInsn && varInsn.var >= newLocalsShift) {
                varInsn.var += newLocalsShift;
            }
            if (insn instanceof MethodInsnNode methodInsn) {
                String name = methodInsn.name;
                if (name.equals(ReflectionUtil.getMappedMethodName(Entity.class, "onGround", "()Z"))) {
                    redirectToEventField(methodNode, insn, i, "onGround", "Z");
                } else if (name.equals(ReflectionUtil.getMappedMethodName(Entity.class, "getYRot", "()F"))) {
                    redirectToEventField(methodNode, insn, i, "yaw", "F");
                } else if (name.equals(ReflectionUtil.getMappedMethodName(Entity.class, "getXRot", "()F"))) {
                    redirectToEventField(methodNode, insn, i, "pitch", "F");
                } else if (name.equals(ReflectionUtil.getMappedMethodName(Entity.class, "getX", "()D"))) {
                    redirectToEventField(methodNode, insn, i, "x", "D");
                } else if (name.equals(ReflectionUtil.getMappedMethodName(Entity.class, "getY", "()D"))) {
                    redirectToEventField(methodNode, insn, i, "y", "D");
                } else if (name.equals(ReflectionUtil.getMappedMethodName(Entity.class, "getZ", "()D"))) {
                    redirectToEventField(methodNode, insn, i, "z", "D");
                }
            }
        }
        methodNode.instructions.insert(constructEvent);
        methodNode.instructions.insertBefore(methodNode.instructions.getLast().getPrevious(), finalizeEvent);
    }

    private static MethodInsnNode invokeGetter(Class<?> owner, String mojangName, String desc) {
        return new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/entity/Entity",
                ReflectionUtil.getMappedMethodName(owner, mojangName, desc),
                desc);
    }

    private static void redirectToEventField(MethodNode methodNode, AbstractInsnNode callInsn, int index, String field, String desc) {
        AbstractInsnNode preceding = methodNode.instructions.get(index - 1);
        if (!(preceding instanceof VarInsnNode)) {
            return;
        }
        methodNode.instructions.insert(preceding, new VarInsnNode(Opcodes.ALOAD, 1));
        methodNode.instructions.remove(preceding);
        methodNode.instructions.insert(callInsn, new FieldInsnNode(
                Opcodes.GETFIELD,
                MotionEvent.class.getName().replace(".", "/"),
                field,
                desc));
        methodNode.instructions.remove(callInsn);
    }
}
