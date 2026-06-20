package shit.nilore.modules.impl.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import shit.nilore.event.impl.MotionEvent;
import shit.nilore.event.impl.PacketEvent;
import shit.nilore.event.impl.RenderEvent;
import shit.nilore.event.impl.WorldChangeEvent;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.utils.game.BlockUtil;
import shit.nilore.utils.game.ChunkUtil;
import shit.nilore.utils.render.RenderUtil;
import shit.nilore.event.EventTarget;

public class ChestESP
extends Module {
    private static final float[] chestColor;
    private static final float[] openedChestColor;
    private final List<BlockPos> openedChestPositions = new CopyOnWriteArrayList<>();
    private final List<AABB> renderBoundingBoxes = new CopyOnWriteArrayList<>();
    private static final String MODULE_NAME;

    public ChestESP() {
        super(MODULE_NAME, Category.RENDER);
    }

    @EventTarget
    public void onWorldChange(WorldChangeEvent worldChangeEvent) {
        this.openedChestPositions.clear();
    }

    @EventTarget
    public void onPacket(PacketEvent packetEvent) {
        ClientboundBlockEventPacket clientboundBlockEventPacket;
        Packet<?> packet;
        if (packetEvent.isIncoming() && (packet = packetEvent.getPacket()) instanceof ClientboundBlockEventPacket && ((clientboundBlockEventPacket = (ClientboundBlockEventPacket)packet).getBlock() == Blocks.CHEST || clientboundBlockEventPacket.getBlock() == Blocks.TRAPPED_CHEST) && clientboundBlockEventPacket.getB0() == 1 && clientboundBlockEventPacket.getB1() == 1) {
            this.openedChestPositions.add(clientboundBlockEventPacket.getPos());
        }
    }

    @EventTarget
    public void onMotion(MotionEvent motionEvent) {
        if (motionEvent.isPost()) {
            ArrayList<BlockEntity> arrayList = ChunkUtil.getLoadedBlockEntities().collect(Collectors.toCollection(ArrayList::new));
            this.renderBoundingBoxes.clear();
            for (BlockEntity blockEntity : arrayList) {
                ChestBlockEntity chestBlockEntity;
                AABB aABB;
                if (!(blockEntity instanceof ChestBlockEntity) || (aABB = this.getChestAabb(chestBlockEntity = (ChestBlockEntity)blockEntity)) == null) continue;
                this.renderBoundingBoxes.add(aABB);
            }
        }
    }

    private AABB getChestAabb(ChestBlockEntity chestBlockEntity) {
        BlockPos blockPos;
        BlockState blockState = chestBlockEntity.getBlockState();
        if (!blockState.hasProperty((Property)ChestBlock.TYPE)) {
            return null;
        }
        ChestType chestType = (ChestType)blockState.getValue((Property)ChestBlock.TYPE);
        if (chestType == ChestType.LEFT) {
            return null;
        }
        BlockPos blockPos2 = chestBlockEntity.getBlockPos();
        AABB aABB = BlockUtil.getBoundingBox(blockPos2);
        if (chestType != ChestType.SINGLE && BlockUtil.canBeClicked(blockPos = blockPos2.relative(ChestBlock.getConnectedDirection(blockState)))) {
            AABB aABB2 = BlockUtil.getBoundingBox(blockPos);
            aABB = aABB.minmax(aABB2);
        }
        return aABB;
    }

    @EventTarget
    public void onRender(RenderEvent renderEvent) {
        PoseStack poseStack = renderEvent.poseStack();
        poseStack.pushPose();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionShader);
        Tesselator tesselator = RenderSystem.renderThreadTesselator();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        for (AABB aABB : this.renderBoundingBoxes) {
            BlockPos blockPos = BlockPos.containing(aABB.minX, aABB.minY, aABB.minZ);
            float[] fArray = this.openedChestPositions.contains(blockPos) ? openedChestColor : chestColor;
            RenderSystem.setShaderColor(fArray[0], fArray[1], fArray[2], 0.25f);
            RenderUtil.drawBoxVerts(bufferBuilder, poseStack.last().pose(), aABB);
        }
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        poseStack.popPose();
    }

    static {
        MODULE_NAME = "ChestESP";
        chestColor = new float[]{0.0f, 1.0f, 0.0f};
        openedChestColor = new float[]{1.0f, 0.0f, 0.0f};
    }
}