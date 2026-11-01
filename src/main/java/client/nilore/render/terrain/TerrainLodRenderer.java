package client.nilore.render.terrain;

import com.mojang.blaze3d.platform.GlStateManager;
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
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrays;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TerrainLodRenderer {
    /**
     * 三档 LOD 的降采样边长，按距离由近到远依次变粗：2×2、4×4、8×8 个方块合并成一个格子。
     *
     * <p>降的不只是几何精度：格子变粗意味着一个方块贴图要铺满更大一块面积，每方块摊到的
     * 纹素（texels per block）随档位线性下降 —— 这才是 voxy 那种"纹理精度"退化。
     * {@link #draw} 里再叠一层：远档强制采样更粗的 mipmap（{@code GL_TEXTURE_MIN_LOD}），
     * 所以远处是"贴图变糊"，而不是"贴图变成纯色"。
     */
    public static final int[] TIER_CELLS = {2, 4, 8};
    private static final int TIER_COUNT = TIER_CELLS.length;
    private static final int BUILD_BUDGET = 3;
    private static final float[] FACE_SHADE = {0.55f, 1.0f, 0.68f, 0.82f, 0.72f, 0.9f};
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final RandomSource RANDOM = RandomSource.create();
    /** GL 默认的 {@code GL_TEXTURE_MIN_LOD}，绘制完必须还原。 */
    private static final float DEFAULT_MIN_LOD = -1000.0f;

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
        int tier = -1;
        int cell;
        boolean built;
        boolean hasMesh;

        LodSection(BlockPos origin) {
            this.origin = origin.immutable();
        }
    }

    /**
     * 一帧内的距离阈值，全部预先平方：热路径只做比较，不做开方。
     *
     * <p>档位边界在这里夹住，保证三档都不会塌成空区间：用户把后一档设得比前一档还近时，
     * 自动顺延成 起点 + 16。
     */
    public static final class Tiers {
        final double start;
        final double startSq;
        final double tier1Sq;
        final double tier2Sq;

        Tiers(double start, double tier1End, double tier2End) {
            this.start = Math.max(16.0, start);
            this.startSq = this.start * this.start;
            double t1 = Math.max(this.start + 16.0, tier1End);
            double t2 = Math.max(t1 + 16.0, tier2End);
            this.tier1Sq = t1 * t1;
            this.tier2Sq = t2 * t2;
        }

        /** @return -1 表示还在原版渲染范围内、LOD 不接管；否则返回 0/1/2 档位下标。 */
        int tierFor(double distanceSq) {
            if (distanceSq <= this.startSq) {
                return -1;
            }
            if (distanceSq <= this.tier1Sq) {
                return 0;
            }
            if (distanceSq <= this.tier2Sq) {
                return 1;
            }
            return TIER_COUNT - 1;
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

    /**
     * LOD 网格是否覆盖了这个 section（即原版这一层可以让位给 LOD）。
     *
     * <p>只吃 {@code start} 一个参数：这是 {@code renderChunkLayer} 的循环体里每帧每层每 section
     * 调一次的热路径，能少算就少算 —— 档位边界只在 {@code render} 里算一次。
     */
    public static boolean shouldReplace(BlockPos origin, double distanceSq, double start) {
        LodSection section = CACHE.get(SectionPos.asLong(origin.getX() >> 4, origin.getY() >> 4, origin.getZ() >> 4));
        return section != null && section.hasMesh && distanceSq > start * start;
    }

    public static void render(PoseStack poseStack, double start, double tier1End, double tier2End) {
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
        Tiers tiers = new Tiers(start, tier1End, tier2End);
        buildQueue(mc, camX, camZ, tiers);
        draw(poseStack, camX, camY, camZ, tiers);
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

    private static void buildQueue(Minecraft mc, double camX, double camZ, Tiers tiers) {
        int built = 0;
        for (int i = 0; i < candidateCount && built < BUILD_BUDGET; i++) {
            long key = candidates[i];
            int sx = SectionPos.x(key);
            int sy = SectionPos.y(key);
            int sz = SectionPos.z(key);
            double dx = sx * 16 + 8 - camX;
            double dz = sz * 16 + 8 - camZ;
            double distanceSq = dx * dx + dz * dz;
            // 档位只跟距离有关：近档 2、中档 4、远档 8 方块一格。档位变了就得重建 VBO。
            int tier = tiers.tierFor(distanceSq);
            if (tier < 0) {
                // 还在 LOD 起点以内：原版自己会画，不用建，也不用摘 —— 走远一点就得要。
                continue;
            }
            LodSection section = CACHE.get(key);
            if (section != null && section.built && section.tier == tier) {
                removeCandidate(i);
                i--;
                continue;
            }
            if (section == null) {
                section = new LodSection(new BlockPos(sx * 16, sy * 16, sz * 16));
                CACHE.put(key, section);
            }
            if (build(mc, section, TIER_CELLS[tier])) {
                section.cell = TIER_CELLS[tier];
                section.tier = tier;
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
        int cells = side * side * side;
        // colors：格子代表色（生物群系染色，0 表示空格子），只用来判断面可见性；
        // reps：该格的代表方块，用来取真实贴图；tops：该格地表高度（相对格底，1..cell，0 = 空）。
        int[] colors = new int[cells];
        BlockState[] reps = new BlockState[cells];
        int[] tops = new int[cells];
        Map<BlockState, TextureAtlasSprite[]> iconCache = new HashMap<>();
        for (int cy = 0; cy < side; cy++) {
            for (int cz = 0; cz < side; cz++) {
                for (int cx = 0; cx < side; cx++) {
                    sampleCell(mc.level, chunkSection, cx, cy, cz, cell, side, origin, colors, reps, tops);
                }
            }
        }
        float light = sectionLight(mc.level, origin);
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        // POSITION_TEX_COLOR：位置 + 图集 UV + 染色/明暗。贴图是方块图集的真实 sprite。
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        boolean any = false;
        for (int cy = 0; cy < side; cy++) {
            for (int cz = 0; cz < side; cz++) {
                for (int cx = 0; cx < side; cx++) {
                    int slot = (cy * side + cz) * side + cx;
                    int color = colors[slot];
                    if (color == 0) {
                        continue;
                    }
                    BlockState rep = reps[slot];
                    // 顶面画在采样到的真实地表高度上，而不是格子顶边界：否则地表被抬到格子上沿，
                    // cell=8 时整片地形会被抬高最多 8 格，看起来就是"地形整体上移"。
                    // 反过来，上面还有方块（顶面被剔除）时要把这段柱子补满到格子边界，不然会露缝。
                    float by = cy * cell;
                    boolean upVisible = faceVisible(mc.level, origin, colors, side, cell, cx, cy, cz, Direction.UP);
                    int fill = upVisible ? tops[slot] : cell;
                    float topY = by + fill;
                    float x0 = cx * cell;
                    float z0 = cz * cell;
                    for (Direction direction : DIRECTIONS) {
                        if (!faceVisible(mc.level, origin, colors, side, cell, cx, cy, cz, direction)) {
                            continue;
                        }
                        // 每个面取该方块这一面的贴图：草方块顶面是草、侧面是草侧，与原版一致。
                        TextureAtlasSprite sprite = spriteFor(rep, direction, iconCache);
                        if (sprite == null) {
                            continue;
                        }
                        int vertexColor = shade(color, FACE_SHADE[direction.ordinal()] * light);
                        emitFace(builder, direction, x0, by, z0, cell, fill, topY, sprite, vertexColor);
                        any = true;
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
                                   int cell, int side, BlockPos origin, int[] colors, BlockState[] reps,
                                   int[] tops) {
        int slot = (cy * side + cz) * side + cx;
        int solidY = -1;
        BlockState solid = null;
        int looseY = -1;
        BlockState loose = null;
        // 从格子顶部往下找：第一块实心方块就是这一格的地表，高度记下来给顶面用。
        search:
        for (int y = cell - 1; y >= 0; y--) {
            for (int z = 0; z < cell; z++) {
                for (int x = 0; x < cell; x++) {
                    BlockState state = chunkSection.getBlockState(cx * cell + x, cy * cell + y, cz * cell + z);
                    if (state.isAir()) {
                        continue;
                    }
                    if (state.canOcclude()) {
                        solidY = y;
                        solid = state;
                        break search;
                    }
                    // 花、草、水这类不遮挡的方块：先记下来当兜底，继续找有没有实心的。
                    if (loose == null) {
                        looseY = y;
                        loose = state;
                    }
                }
            }
        }
        if (solid != null) {
            storeCell(level, solid, origin, cx, cy, cz, cell, solidY, slot, colors, reps, tops);
        } else if (loose != null) {
            storeCell(level, loose, origin, cx, cy, cz, cell, looseY, slot, colors, reps, tops);
        } else {
            colors[slot] = 0;
            reps[slot] = null;
            tops[slot] = 0;
        }
    }

    private static void storeCell(ClientLevel level, BlockState state, BlockPos origin, int cx, int cy, int cz,
                                  int cell, int localY, int slot, int[] colors, BlockState[] reps, int[] tops) {
        // 染色按格子中心取：同一格内生物群系染色最多差一点，够用。
        CURSOR.set(origin.getX() + cx * cell + (cell >> 1),
                origin.getY() + cy * cell + localY,
                origin.getZ() + cz * cell + (cell >> 1));
        // 生物群系染色（草、树叶、水）：没有染色的方块返回 -1，用纯白保持贴图原色。
        int tint = Minecraft.getInstance().getBlockColors().getColor(state, level, CURSOR, 0);
        int rgb = tint == -1 ? 0xFFFFFF : (tint & 0xFFFFFF);
        colors[slot] = rgb == 0 ? 0x010101 : rgb;
        reps[slot] = state;
        // 地表高度：格底到该方块顶面的方块数（1..cell）。
        tops[slot] = localY + 1;
    }

    /**
     * 取方块某一面的真实贴图。
     *
     * <p>不用 {@code BlockModelShaper#getTexture}（那是 particle 图标，草方块会拿到泥土贴图），
     * 而是按面取模型 quad 的 sprite，和原版每个面用哪张图保持一致。
     */
    private static TextureAtlasSprite spriteFor(BlockState state, Direction direction,
                                                Map<BlockState, TextureAtlasSprite[]> cache) {
        TextureAtlasSprite[] cached = cache.get(state);
        if (cached == null) {
            cached = new TextureAtlasSprite[DIRECTIONS.length];
            BlockModelShaper shaper = Minecraft.getInstance().getModelManager().getBlockModelShaper();
            BakedModel model = shaper.getBlockModel(state);
            for (Direction side : DIRECTIONS) {
                List<BakedQuad> quads = model.getQuads(state, side, RANDOM);
                if (!quads.isEmpty()) {
                    cached[side.ordinal()] = quads.get(0).getSprite();
                }
            }
            List<BakedQuad> all = model.getQuads(state, null, RANDOM);
            TextureAtlasSprite fallback = all.isEmpty() ? model.getParticleIcon() : all.get(0).getSprite();
            for (int i = 0; i < cached.length; i++) {
                if (cached[i] == null) {
                    cached[i] = fallback;
                }
            }
            cache.put(state, cached);
        }
        return cached[direction.ordinal()];
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

    /**
     * 画一个格子的一面：按 1×1 方块切成小 quad，每个小 quad 贴一次完整 sprite。
     *
     * <p>这是"不放大"的关键：UV 不再是一张贴图铺满整个 cell（那样 cell=4 就把贴图放大 4 倍），
     * 而是每方块采样一次，远景贴图的世界尺度和原版一致，降的只有几何精度和 mip 精度。
     *
     * @param y0   格子底面 y
     * @param top  该格地表高度（格底往上数几个方块）
     * @param topY 顶面 y（= y0 + top）
     */
    private static void emitFace(BufferBuilder builder, Direction direction, float x0, float y0, float z0,
                                 int cell, int top, float topY, TextureAtlasSprite sprite, int color) {
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        float x1 = x0 + cell;
        float z1 = z0 + cell;
        switch (direction) {
            case UP -> {
                for (int i = 0; i < cell; i++) {
                    for (int j = 0; j < cell; j++) {
                        float xa = x0 + i;
                        float xb = xa + 1;
                        float za = z0 + j;
                        float zb = za + 1;
                        v(builder, xa, topY, za, u0, v0, color);
                        v(builder, xa, topY, zb, u0, v1, color);
                        v(builder, xb, topY, zb, u1, v1, color);
                        v(builder, xb, topY, za, u1, v0, color);
                    }
                }
            }
            case DOWN -> {
                for (int i = 0; i < cell; i++) {
                    for (int j = 0; j < cell; j++) {
                        float xa = x0 + i;
                        float xb = xa + 1;
                        float za = z0 + j;
                        float zb = za + 1;
                        v(builder, xa, y0, za, u0, v0, color);
                        v(builder, xb, y0, za, u1, v0, color);
                        v(builder, xb, y0, zb, u1, v1, color);
                        v(builder, xa, y0, zb, u0, v1, color);
                    }
                }
            }
            case NORTH -> {
                for (int i = 0; i < cell; i++) {
                    float xa = x0 + i;
                    float xb = xa + 1;
                    for (int k = 0; k < top; k++) {
                        float ya = y0 + k;
                        float yb = ya + 1;
                        v(builder, xa, ya, z0, u0, v1, color);
                        v(builder, xa, yb, z0, u0, v0, color);
                        v(builder, xb, yb, z0, u1, v0, color);
                        v(builder, xb, ya, z0, u1, v1, color);
                    }
                }
            }
            case SOUTH -> {
                for (int i = 0; i < cell; i++) {
                    float xa = x0 + i;
                    float xb = xa + 1;
                    for (int k = 0; k < top; k++) {
                        float ya = y0 + k;
                        float yb = ya + 1;
                        v(builder, xa, ya, z1, u0, v1, color);
                        v(builder, xb, ya, z1, u1, v1, color);
                        v(builder, xb, yb, z1, u1, v0, color);
                        v(builder, xa, yb, z1, u0, v0, color);
                    }
                }
            }
            case WEST -> {
                for (int j = 0; j < cell; j++) {
                    float za = z0 + j;
                    float zb = za + 1;
                    for (int k = 0; k < top; k++) {
                        float ya = y0 + k;
                        float yb = ya + 1;
                        v(builder, x0, ya, za, u0, v1, color);
                        v(builder, x0, ya, zb, u1, v1, color);
                        v(builder, x0, yb, zb, u1, v0, color);
                        v(builder, x0, yb, za, u0, v0, color);
                    }
                }
            }
            case EAST -> {
                for (int j = 0; j < cell; j++) {
                    float za = z0 + j;
                    float zb = za + 1;
                    for (int k = 0; k < top; k++) {
                        float ya = y0 + k;
                        float yb = ya + 1;
                        v(builder, x1, ya, za, u1, v1, color);
                        v(builder, x1, yb, za, u1, v0, color);
                        v(builder, x1, yb, zb, u0, v0, color);
                        v(builder, x1, ya, zb, u0, v1, color);
                    }
                }
            }
            default -> {
            }
        }
    }

    private static void v(BufferBuilder builder, float x, float y, float z, float u, float w, int color) {
        builder.vertex(x, y, z).uv(u, w).color(color).endVertex();
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

    /**
     * 按档位绘制：每档画之前把方块图集的 {@code GL_TEXTURE_MIN_LOD} 顶高一档，强制远处只能
     * 采样更粗的 mipmap —— 这就是"纹理精度逐档递减"，贴图仍在，只是糊了。
     *
     * <p>{@code MIN_LOD} 是纹理对象上的参数，必须还原（默认 -1000），否则会污染之后所有原版渲染。
     * 玩家在视频设置里关掉 mipmap 时（可用级别 0）不加限制，否则会采到不存在的层级变黑。
     */
    private static void draw(PoseStack poseStack, double camX, double camY, double camZ, Tiers tiers) {
        if (CACHE.isEmpty()) {
            return;
        }
        ShaderInstance shader = GameRenderer.getPositionTexColorShader();
        AbstractTexture atlas = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        if (shader == null || atlas == null) {
            return;
        }
        int atlasId = atlas.getId();
        int mipLevels = Minecraft.getInstance().options.mipmapLevels().get();
        Matrix4f base = new Matrix4f(poseStack.last().pose());
        Matrix4f projection = RenderSystem.getProjectionMatrix();
        Matrix4f modelView = new Matrix4f();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
        try {
            for (int tier = 0; tier < TIER_COUNT; tier++) {
                applyMinLod(atlasId, mipLevels > 0 ? Math.min(tier, mipLevels) : DEFAULT_MIN_LOD);
                for (LodSection section : CACHE.values()) {
                    if (!section.hasMesh || section.tier != tier) {
                        continue;
                    }
                    double dx = section.origin.getX() + 8 - camX;
                    double dz = section.origin.getZ() + 8 - camZ;
                    if (tiers.tierFor(dx * dx + dz * dz) < 0) {
                        continue;
                    }
                    modelView.set(base).translate((float) (section.origin.getX() - camX),
                            (float) (section.origin.getY() - camY),
                            (float) (section.origin.getZ() - camZ));
                    section.buffer.bind();
                    section.buffer.drawWithShader(modelView, projection, shader);
                    VertexBuffer.unbind();
                }
            }
        } finally {
            applyMinLod(atlasId, DEFAULT_MIN_LOD);
        }
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
    }

    private static void applyMinLod(int atlasId, float minLod) {
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(atlasId);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL13.GL_TEXTURE_MIN_LOD, minLod);
        GlStateManager._bindTexture(0);
    }
}
