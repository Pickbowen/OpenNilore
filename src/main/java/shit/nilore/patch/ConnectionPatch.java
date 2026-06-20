package shit.nilore.patch;

import asm.patchify.annotation.Patch;
import asm.patchify.annotation.Transform;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import shit.nilore.ClientBase;
import shit.nilore.NiloreClient;
import shit.nilore.event.impl.PacketEvent;
import shit.nilore.event.impl.PrePacketEvent;
import shit.nilore.utils.misc.PacketUtil;

@Patch(Connection.class)
public class ConnectionPatch extends ClientBase {
    public static boolean onPacketReceive(Packet<?> packet) {
        if (mc == null || mc.level == null || mc.player == null || packet == null || !NiloreClient.isReady()) {
            return false;
        }
        PrePacketEvent prePacket = new PrePacketEvent(packet);
        NiloreClient.getInstance().getEventBus().call(prePacket);
        if (prePacket.isCancelled()) {
            return true;
        }
        PacketEvent event = new PacketEvent(prePacket.getPacket(), false);
        NiloreClient.getInstance().getEventBus().call(event);
        return event.isCancelled();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean onPacketSend(Packet<?> packet) {
        if (mc == null || mc.level == null || mc.player == null || packet == null || !NiloreClient.isReady()) {
            return false;
        }
        if (PacketUtil.shouldBypass((Packet) packet)) {
            return false;
        }
        PacketEvent event = new PacketEvent(packet, true);
        NiloreClient.getInstance().getEventBus().call(event);
        return event.isCancelled();
    }

    @Transform(method = "channelRead0", desc = "(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V")
    public static void transformReceive(MethodNode methodNode) {
        InsnList header = new InsnList();
        header.add(new VarInsnNode(Opcodes.ALOAD, 2));
        header.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                Type.getInternalName(ConnectionPatch.class),
                "onPacketReceive",
                "(Lnet/minecraft/network/protocol/Packet;)Z",
                false));
        LabelNode label = new LabelNode();
        header.add(new JumpInsnNode(Opcodes.IFEQ, label));
        header.add(new InsnNode(Opcodes.RETURN));
        header.add(label);
        methodNode.instructions.insert(header);
    }

    @Transform(method = "sendPacket", desc = "(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V")
    public static void transformSend(MethodNode methodNode) {
        InsnList header = new InsnList();
        header.add(new VarInsnNode(Opcodes.ALOAD, 1));
        header.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                Type.getInternalName(ConnectionPatch.class),
                "onPacketSend",
                "(Lnet/minecraft/network/protocol/Packet;)Z",
                false));
        LabelNode label = new LabelNode();
        header.add(new JumpInsnNode(Opcodes.IFEQ, label));
        header.add(new InsnNode(Opcodes.RETURN));
        header.add(label);
        methodNode.instructions.insert(header);
    }
}
