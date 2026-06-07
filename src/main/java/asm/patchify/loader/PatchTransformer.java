package asm.patchify.loader;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Local;
import asm.patchify.annotation.ModifyLocals;
import asm.patchify.annotation.Overwrite;
import asm.patchify.annotation.Patch;
import asm.patchify.annotation.Slice;
import asm.patchify.annotation.Transform;
import asm.patchify.annotation.WrapInvoke;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import shit.lizz.asm.Bootstrap;
import shit.lizz.asm.ILocals;
import shit.lizz.asm.Invocation;
import shit.lizz.asm.InvocationImpl;
import shit.lizz.asm.Locals;
import shit.lizz.asm.MethodWrapper;
import shit.lizz.patch.CallbackInfo;

/**
 * Applies all {@link Patch}-annotated handlers from a patch class to a target {@link ClassNode}.
 * Ported from <a href="https://github.com/xiaojiang233/izmk-reborn">izmk-reborn</a>'s
 * {@code PatchLoader}.
 *
 * <p>Supports:</p>
 * <ul>
 *     <li>{@link Inject} with {@link At.Type#HEAD}/{@link At.Type#TAIL}/
 *         {@link At.Type#BEFORE_INVOKE}/{@link At.Type#AFTER_INVOKE} + {@link Slice}</li>
 *     <li>{@link Overwrite}</li>
 *     <li>{@link Transform} — direct {@link MethodNode} access for hand-written ASM</li>
 *     <li>{@link WrapInvoke} — wrap an {@code INVOKE} with an {@link Invocation} continuation</li>
 *     <li>{@link ModifyLocals} — read/write local slots via {@link ILocals}</li>
 *     <li>{@link Local} — pass a method-local slot directly to a handler parameter</li>
 * </ul>
 */
public final class PatchTransformer {
    private static final Logger LOGGER = LogManager.getLogger(PatchTransformer.class);
    private static final String CALLBACK_INFO = Type.getInternalName(CallbackInfo.class);
    private static final String CALLBACK_INFO_DESC = Type.getDescriptor(CallbackInfo.class);

    private PatchTransformer() {
    }

    public static void apply(Class<?> patchClass, ClassNode target) {
        Patch patchAnnotation = patchClass.getAnnotation(Patch.class);
        if (patchAnnotation == null) {
            throw new IllegalArgumentException(patchClass.getName() + " is not @Patch");
        }
        String patchTargetOwner = Type.getInternalName(patchAnnotation.value());

        Map<MethodKey, List<Method>> handlersByTarget = new HashMap<>();
        for (Method handler : patchClass.getDeclaredMethods()) {
            collectHandler(patchClass, patchTargetOwner, handler, handlersByTarget);
        }
        LOGGER.info("Loading patch {} -> {} ({} handler(s))",
                patchClass.getName(), target.name,
                handlersByTarget.values().stream().mapToInt(List::size).sum());

        Set<MethodKey> matched = new HashSet<>();
        for (MethodNode method : target.methods) {
            MethodKey key = new MethodKey(method.name, method.desc);
            List<Method> handlers = handlersByTarget.get(key);
            if (handlers == null) continue;
            matched.add(key);
            for (Method handler : handlers) {
                if (handler.isAnnotationPresent(Inject.class)) {
                    applyInject(method, handler);
                } else if (handler.isAnnotationPresent(Overwrite.class)) {
                    overwriteMethod(method, handler);
                    LOGGER.info("@Overwrite     {}/{}{} <- {}#{}",
                            target.name, method.name, method.desc,
                            patchClass.getName(), handler.getName());
                } else if (handler.isAnnotationPresent(Transform.class)) {
                    try {
                        handler.setAccessible(true);
                        handler.invoke(null, method);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to apply @Transform " + handler, e);
                    }
                    LOGGER.info("@Transform     {}/{}{} <- {}#{}",
                            target.name, method.name, method.desc,
                            patchClass.getName(), handler.getName());
                } else if (handler.isAnnotationPresent(WrapInvoke.class)) {
                    wrapInvoke(method, handler);
                } else if (handler.isAnnotationPresent(ModifyLocals.class)) {
                    modifyLocals(method, handler);
                }
            }
        }
        for (Map.Entry<MethodKey, List<Method>> entry : handlersByTarget.entrySet()) {
            if (matched.contains(entry.getKey())) continue;
            for (Method handler : entry.getValue()) {
                LOGGER.warn("Patch handler {}#{}{} targets {}#{}{} but no such method exists on {} — handler will not run.",
                        handler.getDeclaringClass().getName(), handler.getName(), Type.getMethodDescriptor(handler),
                        target.name, entry.getKey().name(), entry.getKey().desc(), target.name);
            }
        }
    }

    private static void collectHandler(Class<?> patchClass, String patchTargetOwner,
                                       Method handler,
                                       Map<MethodKey, List<Method>> handlersByTarget) {
        if (!(handler.isAnnotationPresent(Inject.class)
                || handler.isAnnotationPresent(Overwrite.class)
                || handler.isAnnotationPresent(Transform.class)
                || handler.isAnnotationPresent(WrapInvoke.class)
                || handler.isAnnotationPresent(ModifyLocals.class))) {
            return;
        }
        if (!Modifier.isStatic(handler.getModifiers())) {
            throw new IllegalArgumentException("Handler " + handler + " must be static");
        }

        String name;
        String desc;
        if (handler.isAnnotationPresent(Inject.class)) {
            Inject inject = handler.getAnnotation(Inject.class);
            name = inject.method();
            desc = inject.desc();
            validateInjectSignature(patchClass, handler, inject);
        } else if (handler.isAnnotationPresent(Overwrite.class)) {
            Overwrite overwrite = handler.getAnnotation(Overwrite.class);
            name = overwrite.method();
            desc = overwrite.desc();
        } else if (handler.isAnnotationPresent(Transform.class)) {
            Transform transform = handler.getAnnotation(Transform.class);
            name = transform.method();
            desc = transform.desc();
            if (handler.getParameterCount() != 1 || handler.getParameterTypes()[0] != MethodNode.class) {
                throw new IllegalArgumentException("@Transform " + handler + " must take a single MethodNode");
            }
        } else if (handler.isAnnotationPresent(WrapInvoke.class)) {
            WrapInvoke wrap = handler.getAnnotation(WrapInvoke.class);
            name = wrap.method();
            desc = wrap.desc();
            Class<?>[] params = handler.getParameterTypes();
            if (params.length == 0 || !Invocation.class.isAssignableFrom(params[params.length - 1])) {
                throw new IllegalArgumentException("@WrapInvoke " + handler + " must take an Invocation as last param");
            }
        } else {
            ModifyLocals modify = handler.getAnnotation(ModifyLocals.class);
            name = modify.method();
            desc = modify.desc();
            if (handler.getParameterCount() != 1 || !ILocals.class.isAssignableFrom(handler.getParameterTypes()[0])) {
                throw new IllegalArgumentException("@ModifyLocals " + handler + " must take a single ILocals");
            }
        }
        // Patch annotations carry the mojmap method name (the jar was compiled
        // against mojmap, and reobfJar does not rewrite string literals). In a
        // production Forge environment the live class only has SRG names, so
        // remap before matching against ClassNode.methods.
        name = Bootstrap.remapMethod(patchTargetOwner, name, desc);
        handlersByTarget.computeIfAbsent(new MethodKey(name, desc), k -> new ArrayList<>()).add(handler);
    }

    private static void validateInjectSignature(Class<?> patchClass, Method handler, Inject inject) {
        if (handler.getReturnType() != void.class) {
            throw new IllegalArgumentException("@Inject " + handler + " must return void");
        }
        Parameter[] params = handler.getParameters();
        if (params.length == 0 || params[params.length - 1].getType() != CallbackInfo.class) {
            throw new IllegalArgumentException("@Inject " + handler + " must take CallbackInfo as last param");
        }
        if (inject.at().value() == At.Type.HEAD) {
            for (Parameter param : params) {
                if (param.isAnnotationPresent(Local.class)) {
                    throw new IllegalArgumentException("@Inject " + handler + " HEAD cannot use @Local");
                }
            }
        }
    }

    // ============================================================================
    // @Inject
    // ============================================================================

    private static void applyInject(MethodNode method, Method handler) {
        At at = handler.getAnnotation(Inject.class).at();
        switch (at.value()) {
            case HEAD -> injectHead(method, handler);
            case TAIL -> injectTail(method, handler);
            case BEFORE_INVOKE, AFTER_INVOKE -> {
                String invokeName = at.method();
                String invokeDesc = at.desc();
                if (invokeName.isEmpty() || invokeDesc.isEmpty()) {
                    throw new IllegalArgumentException("@At " + handler + " missing method/desc");
                }
                injectAroundInvoke(method, handler, invokeName, invokeDesc, at.value() == At.Type.BEFORE_INVOKE);
            }
        }
    }

    private static void injectHead(MethodNode method, Method handler) {
        Type returnType = Type.getReturnType(method.desc);
        String handlerOwner = Type.getInternalName(handler.getDeclaringClass());
        String handlerName = handler.getName();
        String handlerDesc = Type.getMethodDescriptor(handler);

        InsnList insns = new InsnList();
        LabelNode notCancelled = new LabelNode();
        int callbackIndex = method.maxLocals;
        method.maxLocals += 1;

        // Forward target method's receiver + args to the handler.
        int slot = 0;
        if (!Modifier.isStatic(method.access)) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, slot++));
        }
        for (Type argType : Type.getArgumentTypes(method.desc)) {
            insns.add(new VarInsnNode(argType.getOpcode(Opcodes.ILOAD), slot));
            slot += argType.getSize();
        }
        // CallbackInfo.create(null)
        insns.add(new InsnNode(Opcodes.ACONST_NULL));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CALLBACK_INFO, "create",
                "(Ljava/lang/Object;)" + CALLBACK_INFO_DESC, false));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ASTORE, callbackIndex));
        // Invoke handler
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, handlerOwner, handlerName, handlerDesc, false));
        // Check CallbackInfo.cancelled
        insns.add(new VarInsnNode(Opcodes.ALOAD, callbackIndex));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new FieldInsnNode(Opcodes.GETFIELD, CALLBACK_INFO, "cancelled", "Z"));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, notCancelled));
        // cancelled -> return result
        if (returnType == Type.VOID_TYPE) {
            insns.add(new InsnNode(Opcodes.POP));
            insns.add(new InsnNode(Opcodes.RETURN));
        } else {
            insns.add(new FieldInsnNode(Opcodes.GETFIELD, CALLBACK_INFO, "result", "Ljava/lang/Object;"));
            insns.add(ASMHelpers.unboxFromObject(returnType));
            insns.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
        }
        insns.add(notCancelled);
        insns.add(new InsnNode(Opcodes.POP));
        method.instructions.insert(insns);
        LOGGER.info("@Inject(HEAD)  {}/{}{} <- {}#{}",
                targetClassName(handler), method.name, method.desc,
                handler.getDeclaringClass().getName(), handler.getName());
    }

    private static void injectTail(MethodNode method, Method handler) {
        Type returnType = Type.getReturnType(method.desc);
        int returnOp = returnType.getOpcode(Opcodes.IRETURN);
        Slice slice = handler.getAnnotation(Inject.class).slice();
        List<AbstractInsnNode> returnInsns = collectInjectionPoints(method.instructions, slice,
                insn -> insn.getOpcode() == returnOp);
        if (returnInsns.isEmpty()) {
            LOGGER.warn("@Inject(TAIL) {}#{}{} found no return instruction in target {}{} — patch handler will not run.",
                    handler.getDeclaringClass().getName(), handler.getName(), Type.getMethodDescriptor(handler),
                    method.name, method.desc);
            return;
        }

        String handlerOwner = Type.getInternalName(handler.getDeclaringClass());
        String handlerName = handler.getName();
        String handlerDesc = Type.getMethodDescriptor(handler);

        for (AbstractInsnNode returnInsn : returnInsns) {
            InsnList insns = new InsnList();
            int callbackIndex = method.maxLocals;
            method.maxLocals += 1;

            // Wrap return value as Object and stash into CallbackInfo.result.
            if (returnType == Type.VOID_TYPE) {
                insns.add(new InsnNode(Opcodes.ACONST_NULL));
            } else {
                insns.add(ASMHelpers.boxToObject(returnType));
            }
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CALLBACK_INFO, "create",
                    "(Ljava/lang/Object;)" + CALLBACK_INFO_DESC, false));

            // Forward (this, args..., @Local..., CallbackInfo) to handler. CallbackInfo is on top of stack now.
            int slot = 0;
            if (!Modifier.isStatic(method.access)) {
                insns.add(new VarInsnNode(Opcodes.ALOAD, slot++));
                insns.add(new InsnNode(Opcodes.SWAP));
            }
            for (Type argType : Type.getArgumentTypes(method.desc)) {
                insns.add(new VarInsnNode(argType.getOpcode(Opcodes.ILOAD), slot));
                slot += argType.getSize();
                swapForCallback(argType, insns);
            }
            for (Parameter param : handler.getParameters()) {
                Local local = param.getAnnotation(Local.class);
                if (local == null) continue;
                Type type = Type.getType(param.getType());
                insns.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), local.value()));
                swapForCallback(type, insns);
            }
            insns.add(new InsnNode(Opcodes.DUP));
            insns.add(new VarInsnNode(Opcodes.ASTORE, callbackIndex));
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, handlerOwner, handlerName, handlerDesc, false));

            // Restore the (possibly modified) return value from CallbackInfo.result.
            insns.add(new VarInsnNode(Opcodes.ALOAD, callbackIndex));
            insns.add(new FieldInsnNode(Opcodes.GETFIELD, CALLBACK_INFO, "result", "Ljava/lang/Object;"));
            if (returnType == Type.VOID_TYPE) {
                insns.add(new InsnNode(Opcodes.POP));
            } else {
                insns.add(ASMHelpers.unboxFromObject(returnType));
            }
            method.instructions.insertBefore(returnInsn, insns);
        }
        LOGGER.info("@Inject(TAIL)  {}/{}{} <- {}#{} ({} return site(s))",
                targetClassName(handler), method.name, method.desc,
                handler.getDeclaringClass().getName(), handler.getName(), returnInsns.size());
    }

    private static void injectAroundInvoke(MethodNode method, Method handler,
                                           String invokeName, String invokeDesc, boolean before) {
        Type returnType = Type.getReturnType(method.desc);
        String[] split = ASMHelpers.splitOwnerName(invokeName);
        String invokeOwner = split[0];
        String invokeMethod = Bootstrap.remapMethod(split[0], split[1], invokeDesc);

        Slice slice = handler.getAnnotation(Inject.class).slice();
        // PatchApplier.applyInvokeInject uses strict owner+name+desc matching.
        List<AbstractInsnNode> sites = collectInjectionPoints(method.instructions, slice,
                insn -> insn instanceof MethodInsnNode m
                        && m.owner.equals(invokeOwner)
                        && m.name.equals(invokeMethod)
                        && m.desc.equals(invokeDesc));
        if (sites.isEmpty()) {
            LOGGER.warn("@Inject({}_INVOKE) {}#{}{} found no call site of {}#{}{} — patch handler will not run.",
                    before ? "BEFORE" : "AFTER",
                    handler.getDeclaringClass().getName(), handler.getName(), Type.getMethodDescriptor(handler),
                    invokeOwner, invokeMethod, invokeDesc);
            return;
        }

        Set<Integer> initializedLocals = collectInitializedLocalsBefore(method, sites.get(0));
        int callbackIndex = method.maxLocals;
        method.maxLocals += 1;

        String handlerOwner = Type.getInternalName(handler.getDeclaringClass());
        String handlerName = handler.getName();
        String handlerDesc = Type.getMethodDescriptor(handler);

        for (AbstractInsnNode site : sites) {
            InsnList insns = new InsnList();
            LabelNode notCancelled = new LabelNode();

            int slot = 0;
            if (!Modifier.isStatic(method.access)) {
                insns.add(new VarInsnNode(Opcodes.ALOAD, slot++));
            }
            for (Type argType : Type.getArgumentTypes(method.desc)) {
                insns.add(new VarInsnNode(argType.getOpcode(Opcodes.ILOAD), slot));
                slot += argType.getSize();
            }
            for (Parameter param : handler.getParameters()) {
                Local local = param.getAnnotation(Local.class);
                if (local == null) continue;
                if (!initializedLocals.contains(local.value())) {
                    throw new IllegalArgumentException("@Local index " + local.value() + " not initialized before injection point in "
                            + handler.getDeclaringClass().getName() + "." + handler.getName());
                }
                Type type = Type.getType(param.getType());
                insns.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), local.value()));
            }
            insns.add(new InsnNode(Opcodes.ACONST_NULL));
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CALLBACK_INFO, "create",
                    "(Ljava/lang/Object;)" + CALLBACK_INFO_DESC, false));
            insns.add(new InsnNode(Opcodes.DUP));
            insns.add(new VarInsnNode(Opcodes.ASTORE, callbackIndex));
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, handlerOwner, handlerName, handlerDesc, false));

            insns.add(new VarInsnNode(Opcodes.ALOAD, callbackIndex));
            insns.add(new InsnNode(Opcodes.DUP));
            insns.add(new FieldInsnNode(Opcodes.GETFIELD, CALLBACK_INFO, "cancelled", "Z"));
            insns.add(new JumpInsnNode(Opcodes.IFEQ, notCancelled));
            insns.add(new FieldInsnNode(Opcodes.GETFIELD, CALLBACK_INFO, "result", "Ljava/lang/Object;"));
            if (returnType == Type.VOID_TYPE) {
                insns.add(new InsnNode(Opcodes.POP));
                insns.add(new InsnNode(Opcodes.RETURN));
            } else {
                insns.add(ASMHelpers.unboxFromObject(returnType));
                insns.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
            }
            insns.add(notCancelled);
            insns.add(new InsnNode(Opcodes.POP));

            if (before) {
                method.instructions.insertBefore(site, insns);
            } else {
                method.instructions.insert(site, insns);
            }
        }
        LOGGER.info("@Inject({}) {}/{}{} <- {}#{} around {}#{}{} ({} site(s))",
                before ? "BEFORE_INVOKE" : "AFTER_INVOKE ",
                targetClassName(handler), method.name, method.desc,
                handler.getDeclaringClass().getName(), handler.getName(),
                invokeOwner, invokeMethod, invokeDesc, sites.size());
    }

    // ============================================================================
    // @Overwrite
    // ============================================================================

    private static void overwriteMethod(MethodNode method, Method handler) {
        int expectedParams = Type.getArgumentTypes(method.desc).length
                + (Modifier.isStatic(method.access) ? 0 : 1);
        if (handler.getParameterCount() != expectedParams) {
            throw new IllegalArgumentException(
                    "@Overwrite handler " + handler + " has " + handler.getParameterCount()
                            + " params but target requires " + expectedParams
                            + " ((this if non-static), then method args). Remove any CallbackInfo param.");
        }
        // PatchApplier.applyOverwrite: prepend a static call to the handler followed by
        // RETURN. The original instructions stay as dead code (never reached). Slot
        // increment is plain ++ — wide types after a wide type are not handled because
        // PatchApplier never handled them.
        InsnList insns = new InsnList();
        int slot = 0;
        if (!Modifier.isStatic(method.access)) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, slot++));
        }
        for (Type argType : Type.getArgumentTypes(method.desc)) {
            insns.add(new VarInsnNode(argType.getOpcode(Opcodes.ILOAD), slot++));
        }
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                Type.getInternalName(handler.getDeclaringClass()),
                handler.getName(),
                Type.getMethodDescriptor(handler),
                false));
        Type returnType = Type.getReturnType(method.desc);
        if (returnType == Type.VOID_TYPE) {
            insns.add(new InsnNode(Opcodes.RETURN));
        } else {
            insns.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
        }
        method.instructions.insert(insns);
    }

    // ============================================================================
    // @WrapInvoke
    // ============================================================================

    private static void wrapInvoke(MethodNode method, Method handler) {
        WrapInvoke wrap = handler.getAnnotation(WrapInvoke.class);
        String target = wrap.target();
        String targetDesc = wrap.targetDesc();
        String[] split = ASMHelpers.splitOwnerName(target);
        String targetOwner = split[0];
        String targetMethod = Bootstrap.remapMethod(split[0], split[1], targetDesc);

        // Prefer a strict owner+name+desc match. Falling back to the historical
        // (owner || name) matcher means a wrap aimed at Mth.lerp(FFF)F also gets
        // its slice index counted against every Mth.rotLerp(FFF)F call site,
        // which silently shifts indices once an earlier wrap deletes its own
        // site instruction. Strict matching keeps each wrap's slice index
        // independent. The legacy loose matcher is still used as a fallback so
        // patches that target an inherited method on a subclass receiver
        // (Entity#getYRot resolved at LivingEntity#getYRot) keep working.
        List<AbstractInsnNode> sites = collectInjectionPoints(method.instructions, wrap.slice(),
                insn -> insn instanceof MethodInsnNode m
                        && m.owner.equals(targetOwner)
                        && m.name.equals(targetMethod)
                        && m.desc.equals(targetDesc));
        if (sites.isEmpty()) {
            sites = collectInjectionPoints(method.instructions, wrap.slice(),
                    insn -> insn instanceof MethodInsnNode m
                            && (m.owner.equals(targetOwner) || m.name.equals(targetMethod))
                            && m.desc.equals(targetDesc));
        }
        if (sites.isEmpty()) {
            LOGGER.warn("@WrapInvoke {}#{}{} found no call site of {}#{}{} — patch handler will not run.",
                    handler.getDeclaringClass().getName(), handler.getName(), Type.getMethodDescriptor(handler),
                    targetOwner, targetMethod, targetDesc);
            return;
        }
        Set<Integer> initializedLocals = collectInitializedLocalsBefore(method, sites.get(0));

        String handlerOwner = Type.getInternalName(handler.getDeclaringClass());
        String handlerName = handler.getName();
        String handlerDesc = Type.getMethodDescriptor(handler);

        // PatchApplier looks at sites.get(0) once and reuses staticCall for the loop;
        // only INVOKESTATIC / INVOKEVIRTUAL are accepted.
        int firstOp = sites.get(0).getOpcode();
        boolean staticCall;
        if (firstOp == Opcodes.INVOKESTATIC) {
            staticCall = true;
        } else if (firstOp == Opcodes.INVOKEVIRTUAL) {
            staticCall = false;
        } else {
            throw new IllegalArgumentException("@WrapInvoke unsupported opcode " + firstOp + " in " + handler);
        }

        for (AbstractInsnNode site : sites) {

            InsnList insns = new InsnList();
            int wrapperLocal = method.maxLocals;
            method.maxLocals += 1;

            // Build MethodWrapper instance and stash it in a local.
            insns.add(new LdcInsnNode(targetOwner));
            insns.add(new LdcInsnNode(targetMethod));
            insns.add(new LdcInsnNode(targetDesc));
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    Type.getInternalName(MethodWrapper.class), "getInstance",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)" + Type.getDescriptor(MethodWrapper.class),
                    false));
            insns.add(new VarInsnNode(Opcodes.ASTORE, wrapperLocal));

            // Stack: [..., receiver?, args...]
            // Pop args in reverse, box them, push them into MethodWrapper.
            Type[] argTypes = Type.getArgumentTypes(targetDesc);
            for (int i = argTypes.length - 1; i >= 0; i--) {
                insns.add(ASMHelpers.boxToObject(argTypes[i]));
                insns.add(new VarInsnNode(Opcodes.ALOAD, wrapperLocal));
                insns.add(new InsnNode(Opcodes.SWAP));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                        Type.getInternalName(MethodWrapper.class), "addParam",
                        "(Ljava/lang/Object;)" + Type.getDescriptor(MethodWrapper.class), false));
                insns.add(new InsnNode(Opcodes.POP));
            }

            // Build the InvocationImpl. For non-static calls the receiver is still on the stack.
            insns.add(new VarInsnNode(Opcodes.ALOAD, wrapperLocal));
            if (staticCall) {
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        Type.getInternalName(InvocationImpl.class), "create",
                        "(" + Type.getDescriptor(MethodWrapper.class) + ")" + Type.getDescriptor(InvocationImpl.class),
                        false));
            } else {
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        Type.getInternalName(InvocationImpl.class), "create",
                        "(Ljava/lang/Object;" + Type.getDescriptor(MethodWrapper.class) + ")" + Type.getDescriptor(InvocationImpl.class),
                        false));
            }
            // Stack now: [..., Invocation]

            // Forward (this, methodArgs..., @Local..., Invocation) to handler.
            int slot = 0;
            if (!Modifier.isStatic(method.access)) {
                insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                insns.add(new InsnNode(Opcodes.SWAP));
                slot++;
            }
            for (Type argType : Type.getArgumentTypes(method.desc)) {
                insns.add(new VarInsnNode(argType.getOpcode(Opcodes.ILOAD), slot));
                slot += argType.getSize();
                swapForCallback(argType, insns);
            }
            for (Parameter param : handler.getParameters()) {
                Local local = param.getAnnotation(Local.class);
                if (local == null) continue;
                if (!initializedLocals.contains(local.value())) {
                    throw new IllegalArgumentException("@Local index " + local.value() + " not initialized in " + handler);
                }
                Type type = Type.getType(param.getType());
                insns.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), local.value()));
                swapForCallback(type, insns);
            }
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, handlerOwner, handlerName, handlerDesc, false));

            method.instructions.insertBefore(site, insns);
            method.instructions.remove(site);
        }
        LOGGER.info("@WrapInvoke    {}/{}{} <- {}#{} wraps {}#{}{} ({} site(s))",
                targetClassName(handler), method.name, method.desc,
                handler.getDeclaringClass().getName(), handler.getName(),
                targetOwner, targetMethod, targetDesc, sites.size());
    }

    // ============================================================================
    // @ModifyLocals
    // ============================================================================

    private static void modifyLocals(MethodNode method, Method handler) {
        ModifyLocals modify = handler.getAnnotation(ModifyLocals.class);
        int[] indexes = modify.indexes();
        Class<?>[] typeClasses = modify.types();
        if (indexes.length != typeClasses.length) {
            throw new IllegalArgumentException("@ModifyLocals indexes/types length mismatch in " + handler);
        }
        Type[] types = new Type[typeClasses.length];
        for (int i = 0; i < typeClasses.length; i++) {
            types[i] = Type.getType(typeClasses[i]);
        }

        At at = modify.at();
        AbstractInsnNode insertBefore = method.instructions.getFirst();
        Set<Integer> initializedLocals = new HashSet<>();
        if (at.value() != At.Type.HEAD) {
            boolean foundAnchor = false;
            if (at.value() == At.Type.TAIL) {
                int retOp = Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN);
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn.getOpcode() == retOp) {
                        insertBefore = insn;
                        foundAnchor = true;
                        break;
                    }
                    if (insn instanceof VarInsnNode v && v.getOpcode() >= Opcodes.ISTORE && v.getOpcode() <= Opcodes.ASTORE) {
                        initializedLocals.add(v.var);
                    }
                }
            } else {
                String[] split = ASMHelpers.splitOwnerName(at.method());
                String invokeMethod = Bootstrap.remapMethod(split[0], split[1], at.desc());
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode m
                            && m.owner.equals(split[0])
                            && m.name.equals(invokeMethod)
                            && m.desc.equals(at.desc())) {
                        insertBefore = insn;
                        foundAnchor = true;
                        break;
                    }
                    if (insn instanceof VarInsnNode v && v.getOpcode() >= Opcodes.ISTORE && v.getOpcode() <= Opcodes.ASTORE) {
                        initializedLocals.add(v.var);
                    }
                }
            }
            if (!foundAnchor) {
                LOGGER.warn("@ModifyLocals {}#{}{} found no anchor {} {}{} in target {}{} — patch handler will not run.",
                        handler.getDeclaringClass().getName(), handler.getName(), Type.getMethodDescriptor(handler),
                        at.value(), at.method(), at.desc(),
                        method.name, method.desc);
                return;
            }
            for (int idx : indexes) {
                if (!initializedLocals.contains(idx) && at.value() != At.Type.HEAD) {
                    // Don't hard-fail — caller might intentionally read an uninitialised slot.
                    // Just log via exception only if BEFORE/AFTER were used.
                }
            }
        }

        InsnList insns = new InsnList();
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                Type.getInternalName(Locals.class), "create",
                "()" + Type.getDescriptor(Locals.class), false));
        for (int i = 0; i < indexes.length; i++) {
            int idx = indexes[i];
            Type type = types[i];
            insns.add(new LdcInsnNode(idx));
            insns.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), idx));
            insns.add(ASMHelpers.boxToObject(type));
            insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                    Type.getInternalName(ILocals.class), "set",
                    "(ILjava/lang/Object;)" + Type.getDescriptor(ILocals.class), true));
        }
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                Type.getInternalName(handler.getDeclaringClass()),
                handler.getName(), Type.getMethodDescriptor(handler), false));
        for (int i = 0; i < indexes.length; i++) {
            int idx = indexes[i];
            Type type = types[i];
            insns.add(new InsnNode(Opcodes.DUP));
            insns.add(new LdcInsnNode(idx));
            insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                    Type.getInternalName(ILocals.class), "get",
                    "(I)Ljava/lang/Object;", true));
            insns.add(ASMHelpers.unboxFromObject(type));
            insns.add(new VarInsnNode(type.getOpcode(Opcodes.ISTORE), idx));
        }
        insns.add(new InsnNode(Opcodes.POP));
        method.instructions.insertBefore(insertBefore, insns);
        LOGGER.info("@ModifyLocals  {}/{}{} <- {}#{} (locals: {})",
                targetClassName(handler), method.name, method.desc,
                handler.getDeclaringClass().getName(), handler.getName(),
                Arrays.toString(indexes));
    }

    // ============================================================================
    // Helpers
    // ============================================================================

    private static String targetClassName(Method handler) {
        Patch patch = handler.getDeclaringClass().getAnnotation(Patch.class);
        return patch == null ? "?" : Type.getInternalName(patch.value());
    }

    private static List<AbstractInsnNode> collectInjectionPoints(InsnList insns, Slice slice,
                                                                  Predicate<AbstractInsnNode> filter) {
        boolean defaultStart = slice.start().value() == At.Type.HEAD;
        boolean defaultEnd = slice.end().value() == At.Type.TAIL;
        boolean indexed = slice.startIndex() != -1 || slice.endIndex() != -1;
        List<AbstractInsnNode> matches = new ArrayList<>();

        if (defaultStart && defaultEnd && !indexed) {
            for (AbstractInsnNode insn : insns) {
                if (filter.test(insn)) matches.add(insn);
            }
            return matches;
        }
        if (indexed) {
            int count = 0;
            int endIndex = slice.endIndex() == -1 ? Integer.MAX_VALUE : slice.endIndex();
            int startIndex = slice.startIndex() == -1 ? 1 : slice.startIndex();
            for (AbstractInsnNode insn : insns) {
                if (!filter.test(insn)) continue;
                count++;
                if (count >= startIndex && count <= endIndex) {
                    matches.add(insn);
                } else if (count > endIndex) {
                    break;
                }
            }
            return matches;
        }
        // method-bounded slice
        String[] startSplit = slice.start().method().isEmpty()
                ? null : ASMHelpers.splitOwnerName(slice.start().method());
        String startDesc = slice.start().desc();
        String startName = startSplit == null ? null
                : Bootstrap.remapMethod(startSplit[0], startSplit[1], startDesc);
        String[] endSplit = slice.end().method().isEmpty()
                ? null : ASMHelpers.splitOwnerName(slice.end().method());
        String endDesc = slice.end().desc();
        String endName = endSplit == null ? null
                : Bootstrap.remapMethod(endSplit[0], endSplit[1], endDesc);
        boolean foundStart = defaultStart;
        for (AbstractInsnNode insn : insns) {
            if (!foundStart && startSplit != null && insn instanceof MethodInsnNode m
                    && m.owner.equals(startSplit[0]) && m.name.equals(startName) && m.desc.equals(startDesc)) {
                foundStart = true;
            } else if (!defaultEnd && endSplit != null && insn instanceof MethodInsnNode m
                    && m.owner.equals(endSplit[0]) && m.name.equals(endName) && m.desc.equals(endDesc)) {
                break;
            }
            // PatchApplier appends every insn in the slice, not only filter matches.
            // No production patch uses method-bounded slices today, so this faithfully
            // mirrors the original — but downstream code expecting filtered hits would
            // crash if a method-bounded slice were ever introduced.
            if (foundStart) {
                matches.add(insn);
            }
        }
        return matches;
    }

    private static Set<Integer> collectInitializedLocalsBefore(MethodNode method, AbstractInsnNode boundary) {
        Set<Integer> locals = new HashSet<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn == boundary) break;
            if (insn instanceof VarInsnNode v && v.getOpcode() >= Opcodes.ISTORE && v.getOpcode() <= Opcodes.ASTORE) {
                locals.add(v.var);
            }
        }
        return locals;
    }

    /**
     * Swap a value just pushed below the CallbackInfo / Invocation reference. Long/double take
     * two slots and need DUP2_X1+POP2 instead of SWAP. Mirrors izmk's helper.
     */
    private static void swapForCallback(Type type, InsnList insns) {
        if (type == Type.LONG_TYPE || type == Type.DOUBLE_TYPE) {
            insns.add(new InsnNode(Opcodes.DUP2_X1));
            insns.add(new InsnNode(Opcodes.POP2));
        } else {
            insns.add(new InsnNode(Opcodes.SWAP));
        }
    }

    private record MethodKey(String name, String desc) {
        @Override
        public boolean equals(Object o) {
            return o instanceof MethodKey k && k.name.equals(name) && k.desc.equals(desc);
        }
        @Override
        public int hashCode() {
            return name.hashCode() * 31 + desc.hashCode();
        }
    }

    /** Suppresses an unused-warning for {@link Arrays} import (kept for parity with izmk). */
    @SuppressWarnings("unused")
    private static void keepArraysImport() {
        Arrays.asList();
    }
}
