package client.nilore.modules.impl.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.RenderEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.utils.render.RenderUtil;

/**
 * ItemTags —— 地上掉落物的箱子 + 数量标签, 移植自 NeoRaven 的 ItemESPModule。
 * <p>
 * 只处理铁/金/钻石/绿宝石四类掉落物(各自可关), 同一坐标上的同类掉落物合并计数。
 * 箱子和数字都关掉深度测试, 穿墙可见; 数字用 billboard 贴在箱子上方并随距离放大,
 * 让远近的标签在屏幕上的大小基本一致。
 */
public class ItemTags extends Module {
    public static ItemTags INSTANCE;

    private static final int IRON_BOX = 0xFFFFFF;
    private static final int IRON_TEXT = 0xFFFFFFFF;
    private static final int GOLD_BOX = 0xFAF049;
    private static final int GOLD_TEXT = 0xFFFFFF68;
    private static final int DIAMOND_BOX = 0x61E2FF;
    private static final int DIAMOND_TEXT = 0xFF8AFFFF;
    private static final int EMERALD_BOX = 0x17D262;
    private static final int EMERALD_TEXT = 0xFF20FF8C;
    /** 箱子半宽(方块), 随距离放大一点再封顶。 */
    private static final float BOX_MIN_HALF = 0.2f;
    private static final float BOX_MAX_HALF = 0.4f;
    /** 数字的 billboard 缩放: 15 格约等于原版铭牌大小, 之后随距离放大保持屏幕尺寸。 */
    private static final float TAG_MIN_SCALE = 0.025f;
    private static final float TAG_MAX_SCALE = 0.075f;

    private final BooleanSetting iron = new BooleanSetting("Iron", true);
    private final BooleanSetting gold = new BooleanSetting("Gold", true);
    private final BooleanSetting diamond = new BooleanSetting("Diamond", true);
    private final BooleanSetting emerald = new BooleanSetting("Emerald", true);

    public ItemTags() {
        super("ItemTags", Category.RENDER);
        INSTANCE = this;
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (mc.player == null || mc.level == null || mc.gameRenderer == null) {
            return;
        }
        float partialTick = event.partialTick();
        Map<Item, List<ItemEntity>> byItem = new HashMap<>();
        Map<Double, Integer> counts = new HashMap<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ItemEntity itemEntity) || entity.tickCount < 3) {
                continue;
            }
            ItemStack stack = itemEntity.getItem();
            if (stack.isEmpty() || !this.shouldRender(stack.getItem())) {
                continue;
            }
            double key = this.hash(stack.getItem(), entity.getX(), entity.getY(), entity.getZ());
            Integer count = counts.get(key);
            if (count == null) {
                counts.put(key, stack.getCount());
                byItem.computeIfAbsent(stack.getItem(), ignored -> new ArrayList<>()).add(itemEntity);
            } else {
                counts.put(key, count + stack.getCount());
            }
        }
        if (byItem.isEmpty()) {
            return;
        }

        PoseStack poseStack = event.poseStack();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        try {
            for (Map.Entry<Item, List<ItemEntity>> entry : byItem.entrySet()) {
                int[] colors = this.colors(entry.getKey());
                for (ItemEntity itemEntity : entry.getValue()) {
                    double x = Mth.lerp(partialTick, itemEntity.xOld, itemEntity.getX());
                    double y = Mth.lerp(partialTick, itemEntity.yOld, itemEntity.getY());
                    double z = Mth.lerp(partialTick, itemEntity.zOld, itemEntity.getZ());
                    double dist = Math.sqrt(mc.player.distanceToSqr(x, y, z));
                    // 计数的 key 必须和收集时一样用未插值坐标, 否则 hash 对不上就取不到数。
                    Integer count = counts.get(this.hash(entry.getKey(),
                            itemEntity.getX(), itemEntity.getY(), itemEntity.getZ()));
                    this.renderTag(colors[0], colors[1], count == null ? 0 : count, x, y, z, dist, poseStack);
                }
            }
            // drawInBatch 进的是共享缓冲, 不当场冲掉就会拖到 GUI 阶段才画, 那时 modelview
            // 已经换成了 2D 的, 字就和箱子不在一个坐标系里了。
            mc.renderBuffers().bufferSource().endBatch();
        } finally {
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            poseStack.popPose();
        }
    }

    private void renderTag(int boxColor, int textColor, int count, double x, double y, double z,
                           double dist, PoseStack poseStack) {
        float half = Mth.clamp((float) (0.01 * dist), BOX_MIN_HALF, BOX_MAX_HALF);
        AABB box = new AABB(x - half, y, z - half, x + half, y + half * 2.0f, z + half);
        RenderSystem.setShaderColor((boxColor >> 16 & 0xFF) / 255.0f, (boxColor >> 8 & 0xFF) / 255.0f,
                (boxColor & 0xFF) / 255.0f, 0.56f);
        RenderUtil.drawSolidBox(box, poseStack);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        String label = String.valueOf(count);
        poseStack.pushPose();
        poseStack.translate(x, y + 0.3 + half * 2.0f, z);
        poseStack.mulPose(mc.gameRenderer.getMainCamera().rotation());
        float scale = Mth.clamp((float) (0.00167 * dist), TAG_MIN_SCALE, TAG_MAX_SCALE);
        poseStack.scale(-scale, -scale, scale);
        int width = mc.font.width(label);
        mc.font.drawInBatch(label, -width / 2.0f, -mc.font.lineHeight / 2.0f, textColor, true,
                poseStack.last().pose(), mc.renderBuffers().bufferSource(), Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
        poseStack.popPose();
    }

    private boolean shouldRender(Item item) {
        return item == Items.IRON_INGOT && this.iron.getValue()
                || item == Items.GOLD_INGOT && this.gold.getValue()
                || item == Items.DIAMOND && this.diamond.getValue()
                || item == Items.EMERALD && this.emerald.getValue();
    }

    private int[] colors(Item item) {
        if (item == Items.GOLD_INGOT) {
            return new int[]{GOLD_BOX, GOLD_TEXT};
        }
        if (item == Items.DIAMOND) {
            return new int[]{DIAMOND_BOX, DIAMOND_TEXT};
        }
        if (item == Items.EMERALD) {
            return new int[]{EMERALD_BOX, EMERALD_TEXT};
        }
        return new int[]{IRON_BOX, IRON_TEXT};
    }

    /** 同一位置的同类掉落物归成一组: y 取整, 再按物品类型加偏移免得不同类混算。 */
    private double hash(Item item, double x, double y, double z) {
        double h = 0.0174 * Double.hashCode(x)
                + 0.023 * Double.hashCode(y < 1.0 ? 1.0 : Math.floor(y))
                + 0.039 * Double.hashCode(z);
        if (item == Items.IRON_INGOT) {
            h += 0.155;
        } else if (item == Items.GOLD_INGOT) {
            h += 0.255;
        } else if (item == Items.DIAMOND) {
            h += 0.355;
        } else {
            h += 0.455;
        }
        return h;
    }
}
