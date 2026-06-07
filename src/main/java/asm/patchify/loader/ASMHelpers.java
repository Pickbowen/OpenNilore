package asm.patchify.loader;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Boxing / unboxing helpers used by the patchify-style transformer. Ported from
 * <a href="https://github.com/xiaojiang233/izmk-reborn">izmk-reborn</a>'s {@code ASMUtil}.
 */
public final class ASMHelpers {
    private ASMHelpers() {
    }

    /** Pops an {@code Object} off the stack and replaces it with the unboxed primitive of {@code type}. */
    public static InsnList unboxFromObject(Type type) {
        InsnList list = new InsnList();
        switch (type.getSort()) {
            case Type.INT -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
            }
            case Type.BOOLEAN -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
            }
            case Type.CHAR -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Character"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false));
            }
            case Type.BYTE -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Byte"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false));
            }
            case Type.SHORT -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Short"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false));
            }
            case Type.LONG -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Long"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false));
            }
            case Type.FLOAT -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Float"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false));
            }
            case Type.DOUBLE -> {
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Double"));
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false));
            }
            default -> list.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName()));
        }
        return list;
    }

    /** Wraps the value currently on the stack in its boxed form (no-op for reference types). */
    public static InsnList boxToObject(Type type) {
        InsnList list = new InsnList();
        switch (type.getSort()) {
            case Type.INT -> list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
            case Type.BOOLEAN -> list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
            case Type.CHAR -> list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false));
            case Type.BYTE -> list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false));
            case Type.SHORT -> list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false));
            case Type.LONG -> list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
            case Type.FLOAT -> list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
            case Type.DOUBLE -> list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false));
            default -> list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Object"));
        }
        return list;
    }

    /** Splits {@code "owner/of/Class/methodName"} into {@code ("owner/of/Class", "methodName")}. */
    public static String[] splitOwnerName(String ownerSlashName) {
        int slash = ownerSlashName.lastIndexOf('/');
        if (slash < 0) {
            return new String[] {"", ownerSlashName};
        }
        return new String[] {ownerSlashName.substring(0, slash), ownerSlashName.substring(slash + 1)};
    }
}
