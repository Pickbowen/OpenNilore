package client.nilore.patch;

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
import client.nilore.ClientBase;
import client.nilore.NiloreClient;
import client.nilore.event.impl.PacketEvent;
import client.nilore.event.impl.PrePacketEvent;
import client.nilore.event.impl.ReceivePacketEvent;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import client.nilore.network.PingTracker;
import client.nilore.utils.misc.PacketUtil;

@Patch(Connection.class)
public class ConnectionPatch extends ClientBase {
    public static boolean onPacketReceive(Packet<?> packet) {
        if (mc == null || mc.level == null || mc.player == null || packet == null || !NiloreClient.isReady()) {
            return false;
        }
        // ping 读数的两个输入都在这一层: 入站 keep-alive 的到达时刻, 以及换服/换维度时的重置。
        if (packet instanceof ClientboundLoginPacket) {
            PingTracker.reset();
        }
        PingTracker.onIncomingPacket(packet);
        PrePacketEvent prePacket = new PrePacketEvent(packet);
        NiloreClient.getInstance().getEventBus().call(prePacket);
        if (prePacket.isCancelled()) {
            return true;
        }
        PacketEvent event = new PacketEvent(prePacket.getPacket(), false);
        NiloreClient.getInstance().getEventBus().call(event);
        if (event.isCancelled()) {
            return true;
        }
        ReceivePacketEvent receiveEvent = new ReceivePacketEvent((Packet) event.getPacket());
        NiloreClient.getInstance().getEventBus().call(receiveEvent);
        return receiveEvent.isCancelled();
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
        if (!event.isCancelled()) {
            // 只统计真正离开客户端的回包: 被取消的包会被别的模块稍后重发, 那时才计入持有时长,
            // 否则会把"排队中"的时间当成"已经发出去的延迟", 反而低估真实 RTT。
            PingTracker.onOutgoingPacket(packet);
        }
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
