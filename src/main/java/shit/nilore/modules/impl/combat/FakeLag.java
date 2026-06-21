package shit.nilore.modules.impl.combat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import shit.nilore.event.EventTarget;
import shit.nilore.event.impl.PacketEvent;
import shit.nilore.event.impl.ReceivePacketEvent;
import shit.nilore.event.impl.RenderEvent;
import shit.nilore.event.impl.TickEvent;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.settings.impl.BooleanSetting;
import shit.nilore.settings.impl.ModeSetting;
import shit.nilore.settings.impl.NumberSetting;
import shit.nilore.utils.misc.PacketUtil;

/**
 * FakeLag — 缓存出站位置数据包，模拟高延迟
 *
 * 基于 LiquidBounce NextGen ModuleFakeLag 逻辑移植
 * 核心机制: 拦截并延迟发送 ServerboundMovePlayerPacket，释放时瞬间发送所有缓存包
 *
 * Dynamic 模式: 仅在敌人进入范围时缓存
 * Constant 模式: 始终缓存
 *
 * 自动释放: 攻击、交互、受伤、击退、服务端传送
 * 渲染: 缓存期间显示位置轨迹线
 */
public class FakeLag extends Module {

    public static FakeLag INSTANCE;

    private final NumberSetting range = new NumberSetting("Range", 5, 1, 10, 0.5);
    private final NumberSetting delay = new NumberSetting("Delay", 400, 50, 1000, 10);
    private final NumberSetting recoilTime = new NumberSetting("Recoil Time", 250, 0, 1000, 10);
    private final ModeSetting mode = new ModeSetting("Mode", "Dynamic", "Constant").withDefault("Dynamic");
    private final BooleanSetting flushOnAttack = new BooleanSetting("Flush On Attack", true);
    private final BooleanSetting flushOnInteract = new BooleanSetting("Flush On Interact", true);
    private final BooleanSetting flushOnDamage = new BooleanSetting("Flush On Damage", true);
    private final BooleanSetting render = new BooleanSetting("Render", true);

    private final List<Packet<?>> packetQueue = new ArrayList<>();
    private long lastFlushTime = 0;
    private boolean isEnemyNearby = false;

    public FakeLag() {
        super("FakeLag", Category.COMBAT);
        INSTANCE = this;
    }

    @Override
    protected void onEnable() {
        packetQueue.clear();
        lastFlushTime = 0;
        isEnemyNearby = false;
    }

    @Override
    protected void onDisable() {
        flushAll();
    }

    // --- Tick: 检测敌人、超时释放 ---
    @EventTarget
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null) return;

        double r = range.getValue().doubleValue();
        isEnemyNearby = mc.level.players().stream()
                .filter(p -> p != mc.player)
                .filter(Player::isAlive)
                .anyMatch(p -> mc.player.distanceTo(p) <= r);

        if (!packetQueue.isEmpty()) {
            long elapsed = System.currentTimeMillis() - lastFlushTime;
            if (elapsed >= delay.getValue().longValue()) {
                flushAll();
            }
        }
    }

    // --- 拦截入站包: 检测击退/传送/受伤 → 触发释放 ---
    @EventTarget
    public void onReceivePacket(ReceivePacketEvent event) {
        if (mc.player == null || packetQueue.isEmpty()) return;

        Packet<ClientGamePacketListener> packet = event.getPacket();

        if (packet instanceof ClientboundPlayerPositionPacket) {
            flushAll();
            return;
        }

        if (flushOnDamage.getValue()) {
            if (packet instanceof ClientboundSetEntityMotionPacket motion) {
                if (motion.getId() == mc.player.getId()
                        && (motion.getXa() != 0 || motion.getYa() != 0 || motion.getZa() != 0)) {
                    flushAll();
                    return;
                }
            }
            if (packet instanceof ClientboundSetHealthPacket health) {
                if (health.getHealth() < mc.player.getHealth()) {
                    flushAll();
                    return;
                }
            }
        }
    }

    // --- 拦截出站包: 缓存移动包，攻击/交互时释放 ---
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (event.isIncoming()) return;

        Packet<?> packet = event.getPacket();

        // 非移动包: 检查是否需要因攻击/交互而释放
        if (!(packet instanceof ServerboundMovePlayerPacket)) {
            if (flushOnAttack.getValue() && isAttackPacket(packet)) {
                flushAll();
                return;
            }
            if (flushOnInteract.getValue() && isInteractPacket(packet)) {
                flushAll();
                return;
            }
            return;
        }

        // Dynamic 模式: 没敌人就不缓存
        if ("Dynamic".equals(mode.getValue()) && !isEnemyNearby) {
            if (!packetQueue.isEmpty()) flushAll();
            return;
        }

        // 回冲冷却
        if (System.currentTimeMillis() - lastFlushTime < recoilTime.getValue().longValue()) {
            return;
        }

        // 缓存移动包
        event.setCancelled(true);
        packetQueue.add(packet);
    }

    // --- 渲染: 轨迹线 ---
    @EventTarget
    public void onRender(RenderEvent event) {
        if (!render.getValue()) return;
        if (packetQueue.isEmpty() || mc.player == null || mc.gameRenderer == null) return;

        List<Vec3> positions = getQueuedPositions();
        if (positions.size() < 2) return;

        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        PoseStack poseStack = event.poseStack();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
        RenderSystem.lineWidth(2.5f);
        RenderSystem.disableCull();

        Matrix4f matrix = poseStack.last().pose();
        org.joml.Matrix3f normalMat = poseStack.last().normal();
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);

        float r = 0.0f, g = 0.9f, b = 1.0f, a = 0.8f;
        double offset = 0.5;

        for (int i = 0; i < positions.size() - 1; i++) {
            Vec3 start = positions.get(i);
            Vec3 end = positions.get(i + 1);

            // Coalesce collinear segments
            int next = i + 1;
            while (next + 1 < positions.size()) {
                Vec3 nextEnd = positions.get(next + 1);
                double dx1 = end.x - start.x, dy1 = end.y - start.y, dz1 = end.z - start.z;
                double dx2 = nextEnd.x - end.x, dy2 = nextEnd.y - end.y, dz2 = nextEnd.z - end.z;
                if (Math.abs(dx1 - dx2) < 0.01 && Math.abs(dy1 - dy2) < 0.01 && Math.abs(dz1 - dz2) < 0.01) {
                    end = nextEnd;
                    next++;
                } else {
                    break;
                }
            }
            i = next - 1;

            float x1 = (float) start.x, y1 = (float) (start.y + offset), z1 = (float) start.z;
            float x2 = (float) end.x, y2 = (float) (end.y + offset), z2 = (float) end.z;

            float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float nx = len > 0 ? dx / len : 0f;
            float ny = len > 0 ? dy / len : 0f;
            float nz = len > 0 ? dz / len : 0f;

            builder.vertex(matrix, x1, y1, z1).color(r, g, b, a).normal(normalMat, nx, ny, nz).endVertex();
            builder.vertex(matrix, x2, y2, z2).color(r, g, b, a).normal(normalMat, nx, ny, nz).endVertex();
        }

        Tesselator.getInstance().end();
        RenderSystem.enableCull();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();

        poseStack.popPose();
    }

    // --- 释放所有缓存包 ---
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void flushAll() {
        if (packetQueue.isEmpty()) return;
        for (Packet<?> packet : packetQueue) {
            try {
                if (mc.getConnection() != null) {
                    Packet raw = packet;
                    PacketUtil.queuedPackets.add(raw);
                    mc.getConnection().send(raw);
                }
            } catch (Exception ignored) {
            }
        }
        packetQueue.clear();
        lastFlushTime = System.currentTimeMillis();
    }

    private boolean isAttackPacket(Packet<?> packet) {
        return packet instanceof ServerboundInteractPacket
                || packet instanceof ServerboundSwingPacket;
    }

    private boolean isInteractPacket(Packet<?> packet) {
        return packet instanceof ServerboundUseItemOnPacket
                || packet instanceof ServerboundPlayerActionPacket;
    }

    /**
     * 从缓存的移动包中提取位置
     */
    private List<Vec3> getQueuedPositions() {
        List<Vec3> positions = new ArrayList<>();
        for (Packet<?> packet : packetQueue) {
            if (packet instanceof ServerboundMovePlayerPacket.PosRot p) {
                positions.add(new Vec3(p.getX(0), p.getY(0), p.getZ(0)));
            } else if (packet instanceof ServerboundMovePlayerPacket.Pos p) {
                positions.add(new Vec3(p.getX(0), p.getY(0), p.getZ(0)));
            }
        }
        // 追加玩家当前位置作为终点
        if (!positions.isEmpty() && mc.player != null) {
            positions.add(mc.player.position());
        }
        return positions;
    }

    public boolean isLagging() {
        return !packetQueue.isEmpty();
    }
}
