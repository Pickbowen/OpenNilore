package client.nilore.render.terrain;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.joml.Matrix4f;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrays;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.util.Arrays;

public final class TerrainLodRenderer {
    private static final int NEAR_CELL = 2;
    private static final int FAR_CELL = 4;
    private static final double FAR_CELL_FACTOR = 1.5;
    private static final int BUILD_BUDGET = 3;
    private static final float[] FACE_SHADE = {0.55f, 1.0f, 0.68f, 0.82f, 0.72f, 0.9f};
    private static final Direction[] DIRECTIONS = Direction.values();

    /**
     * section 键 -> LOD 网格。
     *
     * <p>用 fastutil 的原语表而不是 {@code HashMap<Long, …>}：{@code shouldReplace} 是
     * {@code renderChunkLayer} 的循环体里调的，每帧每层每个可见 section 一次，
     * 渲染距离 12 时是每帧上万次 {@code Long} 装箱 —— 纯 GC 压力，和"泛不泛型"无关。
     */
    private static final Long2ObjectOpenHashMap<LodSection> CACHE = new Long2ObjectOpenHashMap<>();
    /**
     * 还没建好的 section，按到相机的水平距离升序。
     *
     * <p>用 {@code long[] + count} 而不是 {@code List<Long>}：旧实现每帧从下标 0 开始整表扫一遍，
     * 对每个元素算距离再 {@code computeIfAbsent} 装箱查表；已经建好的那些也得陪着走完。
     * 现在建好的会被 {@link #removeCandidate(int)} 当场摘掉，帧成本随剩余量收敛到 0
     * （候选表只在玩家跨 section 时重建）。
     */
    private static long[] candidates = new long[0];
    private static int candidateCount;
    private static final LongOpenHashSet LIVE = new LongOpenHashSet();
    private static final BlockPos.MutableBlockPos CURSOR = new BlockPos.MutableBlockPos();
    private static long candidateAnchor = Long.MIN_VALUE;
    private static ClientLevel candidateLevel;

    private TerrainLodRenderer() {
    }

    private static final class LodSection {
        final BlockPos origin;
        final VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        int cell;
        boolean built;
        boolean hasMesh;

        LodSection(BlockPos origin) {
            this.origin = origin.immutable();
        }
    }

    public static void reset() {
        for (LodSection section : CACHE.values()) {
            section.buffer.close();
        }
        CACHE.clear();
        candidates = new long[0];
        candidateCount = 0;
        LIVE.clear();
        candidateAnchor = Long.MIN_VALUE;
        candidateLevel = null;
    }

    public static boolean shouldReplace(BlockPos origin, double distanceSq, double start) {
        LodSection section = CACHE.get(SectionPos.asLong(origin.getX() >> 4, origin.getY() >> 4, origin.getZ() >> 4));
        return section != null && section.hasMesh && distanceSq > start * start;
    }

    public static void render(PoseStack poseStack, double start) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            reset();
            return;
        }
        if (mc.level != candidateLevel) {
            reset();
            candidateLevel = mc.level;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        double camX = camera.getPosition().x;
        double camY = camera.getPosition().y;
        double camZ = camera.getPosition().z;
        BlockPos playerPos = mc.player.blockPosition();
        long anchor = SectionPos.asLong(playerPos.getX() >> 4, playerPos.getY() >> 4, playerPos.getZ() >> 4);
        if (anchor != candidateAnchor) {
            candidateAnchor = anchor;
            refreshCandidates(mc, camX, camZ);
        }
        buildQueue(mc, camX, camY, camZ, start);
        draw(poseStack, camX, camY, camZ, start);
    }

    private static void refreshCandidates(Minecraft mc, double camX, double camZ) {
        LIVE.clear();
        int radius = mc.options.renderDistance().get();
        ChunkPos center = new ChunkPos(mc.player.blockPosition());
        long[] keys = candidates;
        int count = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                LevelChunk chunk = mc.level.getChunkSource()
                        .getChunk(center.x + dx, center.z + dz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                LevelChunkSection[] sections = chunk.getSections();
                ChunkPos pos = chunk.getPos();
                for (int i = 0; i < sections.length; i++) {
                    if (sections[i].hasOnlyAir()) {
                        continue;
                    }
                    long key = SectionPos.asLong(pos.x, chunk.getSectionYFromSectionIndex(i), pos.z);
                    if (count == keys.length) {
                        keys = grow(keys, count + 1);
                    }
                    keys[count++] = key;
                    LIVE.add(key);
                }
            }
        }
        // 只排一次（玩家每跨一个 section 才走到这里），但排序键是原语：旧实现用
        // Comparator.comparingDouble 包 Long，几千个元素排序时每次比较都拆一次箱。
        LongArrays.quickSort(keys, 0, count, (a, b) -> Double.compare(distanceSq(a, camX, camZ), distanceSq(b, camX, camZ)));
        candidates = keys;
        candidateCount = count;
        // 已经不在渲染距离内的 section 直接把 VBO 还掉。
        ObjectIterator<Long2ObjectMap.Entry<LodSection>> iterator = CACHE.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            Long2ObjectMap.Entry<LodSection> entry = iterator.next();
            if (!LIVE.contains(entry.getLongKey())) {
                entry.getValue().buffer.close();
                iterator.remove();
            }
        }
    }

    private static long[] grow(long[] array, int minimum) {
        int next = Math.max(minimum, array.length == 0 ? 256 : array.length * 2);
        return Arrays.copyOf(array, next);
    }

    private static double distanceSq(long key, double camX, double camZ) {
        double dx = SectionPos.x(key) * 16 + 8 - camX;
        double dz = SectionPos.z(key) * 16 + 8 - camZ;
        return dx * dx + dz * dz;
    }

    /** 建好（或确认无需重建）的 section 从候选表里摘掉：swap-remove，O(1)。 */
    private static void removeCandidate(int index) {
        int last = --candidateCount;
        candidates[index] = candidates[last];
    }

    private static void buildQueue(Minecraft mc, double camX, double camY, double camZ, double start) {
        int built = 0;
        double startSq = start * start;
        for (int i = 0; i < candidateCount && built < BUILD_BUDGET; i++) {
            long key = candidates[i];
            int sx = SectionPos.x(key);
            int sy = SectionPos.y(key);
            int sz = SectionPos.z(key);
            double dx = sx * 16 + 8 - camX;
            double dz = sz * 16 + 8 - camZ;
            double distanceSq = dx * dx + dz * dz;
            if (distanceSq <= startSq) {
                // 还在 LOD 起点以内：原版自己会画，不用建，也不用摘 —— 走远一点就得要。
                continue;
            }
            int cell = distanceSq <= startSq * FAR_CELL_FACTOR * FAR_CELL_FACTOR ? NEAR_CELL : FAR_CELL;
            LodSection section = CACHE.get(key);
            if (section != null && section.built && section.cell == cell) {
                removeCandidate(i);
                i--;
                continue;
            }
            if (section == null) {
                section = new LodSection(new BlockPos(sx * 16, sy * 16, sz * 16));
                CACHE.put(key, section);
            }
            if (build(mc, section, cell)) {
                section.cell = cell;
                section.built = true;
                removeCandidate(i);
                i--;
            }
            built++;
        }
    }

    private static boolean build(Minecraft mc, LodSection section, int cell) {
        section.hasMesh = false;
        BlockPos origin = section.origin;
        LevelChunk chunk = mc.level.getChunkSource()
                .getChunk(origin.getX() >> 4, origin.getZ() >> 4, ChunkStatus.FULL, false);
        if (chunk == null) {
            return false;
        }
        int index = chunk.getSectionIndex(origin.getY());
        if (index < 0 || index >= chunk.getSections().length) {
            return false;
        }
        LevelChunkSection chunkSection = chunk.getSection(index);
        int side = 16 / cell;
        int[] colors = new int[side * side * side];
        for (int cy = 0; cy < side; cy++) {
            for (int cz = 0; cz < side; cz++) {
                for (int cx = 0; cx < side; cx++) {
                    sampleCell(mc.level, chunkSection, cx, cy, cz, cell, origin, colors);
                }
            }
        }
        float light = sectionLight(mc.level, origin);
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        boolean any = false;
        for (int cy = 0; cy < side; cy++) {
            for (int cz = 0; cz < side; cz++) {
                for (int cx = 0; cx < side; cx++) {
                    int slot = (cy * side + cz) * side + cx;
                    int color = colors[slot];
                    if (color == 0) {
                        continue;
                    }
                    float x0 = cx * cell;
                    float y0 = cy * cell;
                    float z0 = cz * cell;
                    float x1 = x0 + cell;
                    float y1 = y0 + cell;
                    float z1 = z0 + cell;
                    for (Direction direction : DIRECTIONS) {
                        if (!faceVisible(mc.level, origin, colors, side, cell, cx, cy, cz, direction)) {
                            continue;
                        }
                        any |= emit(builder, x0, y0, z0, x1, y1, z1, direction, color, light);
                    }
                }
            }
        }
        BufferBuilder.RenderedBuffer rendered = builder.end();
        if (!any) {
            rendered.release();
            return true;
        }
        section.buffer.bind();
        section.buffer.upload(rendered);
        VertexBuffer.unbind();
        section.hasMesh = true;
        return true;
    }

    private static void sampleCell(ClientLevel level, LevelChunkSection chunkSection, int cx, int cy, int cz,
                                   int cell, BlockPos origin, int[] colors) {
        int slot = (cy * (16 / cell) + cz) * (16 / cell) + cx;
        int fallback = 0;
        for (int y = cell - 1; y >= 0; y--) {
            for (int z = 0; z < cell; z++) {
                for (int x = 0; x < cell; x++) {
                    BlockState state = chunkSection.getBlockState(cx * cell + x, cy * cell + y, cz * cell + z);
                    if (state.isAir()) {
                        continue;
                    }
                    int rgb = state.getMapColor(level, CURSOR.set(origin.getX() + cx * cell + x,
                            origin.getY() + cy * cell + y, origin.getZ() + cz * cell + z)).col & 0xFFFFFF;
                    if (rgb == 0) {
                        rgb = 0x8B8B8B;
                    }
                    if (state.canOcclude()) {
                        colors[slot] = rgb;
                        return;
                    }
                    if (fallback == 0) {
                        fallback = rgb;
                    }
                }
            }
        }
        if (fallback != 0) {
            colors[slot] = fallback;
        }
    }

    private static boolean faceVisible(ClientLevel level, BlockPos origin, int[] colors, int side,
                                       int cell, int cx, int cy, int cz, Direction direction) {
        int nx = cx + direction.getStepX();
        int ny = cy + direction.getStepY();
        int nz = cz + direction.getStepZ();
        if (nx >= 0 && nx < side && ny >= 0 && ny < side && nz >= 0 && nz < side) {
            return colors[(ny * side + nz) * side + nx] == 0;
        }
        BlockState outside = level.getBlockState(CURSOR.set(
                origin.getX() + nx * cell, origin.getY() + ny * cell, origin.getZ() + nz * cell));
        return outside.isAir() || (!outside.canOcclude() && outside.getFluidState().isEmpty());
    }

    private static float sectionLight(ClientLevel level, BlockPos origin) {
        int top = Mth.clamp(origin.getY() + 15, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        int bottom = Mth.clamp(origin.getY() + 1, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        CURSOR.set(origin.getX() + 8, top, origin.getZ() + 8);
        int sky = level.getBrightness(LightLayer.SKY, CURSOR);
        CURSOR.set(origin.getX() + 8, bottom, origin.getZ() + 8);
        int block = level.getBrightness(LightLayer.BLOCK, CURSOR);
        return 0.45f + 0.55f * (Math.max(sky, block) / 15.0f);
    }

    private static boolean emit(BufferBuilder builder, float x0, float y0, float z0, float x1, float y1, float z1,
                                Direction direction, int rgb, float light) {
        int color = shade(rgb, FACE_SHADE[direction.ordinal()] * light);
        switch (direction) {
            case UP -> {
                builder.vertex(x0, y1, z0).color(color).endVertex();
                builder.vertex(x0, y1, z1).color(color).endVertex();
                builder.vertex(x1, y1, z1).color(color).endVertex();
                builder.vertex(x1, y1, z0).color(color).endVertex();
            }
            case DOWN -> {
                builder.vertex(x0, y0, z0).color(color).endVertex();
                builder.vertex(x1, y0, z0).color(color).endVertex();
                builder.vertex(x1, y0, z1).color(color).endVertex();
                builder.vertex(x0, y0, z1).color(color).endVertex();
            }
            case NORTH -> {
                builder.vertex(x0, y0, z0).color(color).endVertex();
                builder.vertex(x0, y1, z0).color(color).endVertex();
                builder.vertex(x1, y1, z0).color(color).endVertex();
                builder.vertex(x1, y0, z0).color(color).endVertex();
            }
            case SOUTH -> {
                builder.vertex(x0, y0, z1).color(color).endVertex();
                builder.vertex(x1, y0, z1).color(color).endVertex();
                builder.vertex(x1, y1, z1).color(color).endVertex();
                builder.vertex(x0, y1, z1).color(color).endVertex();
            }
            case WEST -> {
                builder.vertex(x0, y0, z0).color(color).endVertex();
                builder.vertex(x0, y0, z1).color(color).endVertex();
                builder.vertex(x0, y1, z1).color(color).endVertex();
                builder.vertex(x0, y1, z0).color(color).endVertex();
            }
            case EAST -> {
                builder.vertex(x1, y0, z0).color(color).endVertex();
                builder.vertex(x1, y1, z0).color(color).endVertex();
                builder.vertex(x1, y1, z1).color(color).endVertex();
                builder.vertex(x1, y0, z1).color(color).endVertex();
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static int shade(int rgb, float factor) {
        int r = clamp((int) (((rgb >> 16) & 0xFF) * factor));
        int g = clamp((int) (((rgb >> 8) & 0xFF) * factor));
        int b = clamp((int) ((rgb & 0xFF) * factor));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }

    private static void draw(PoseStack poseStack, double camX, double camY, double camZ, double start) {
        if (CACHE.isEmpty()) {
            return;
        }
        Matrix4f base = new Matrix4f(poseStack.last().pose());
        Matrix4f projection = RenderSystem.getProjectionMatrix();
        ShaderInstance shader = GameRenderer.getPositionColorShader();
        Matrix4f modelView = new Matrix4f();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        for (LodSection section : CACHE.values()) {
            if (!section.hasMesh) {
                continue;
            }
            double dx = section.origin.getX() + 8 - camX;
            double dz = section.origin.getZ() + 8 - camZ;
            if (dx * dx + dz * dz <= start * start) {
                continue;
            }
            modelView.set(base).translate((float) (section.origin.getX() - camX),
                    (float) (section.origin.getY() - camY),
                    (float) (section.origin.getZ() - camZ));
            section.buffer.bind();
            section.buffer.drawWithShader(modelView, projection, shader);
            VertexBuffer.unbind();
        }
        RenderSystem.enableCull();
    }
}
