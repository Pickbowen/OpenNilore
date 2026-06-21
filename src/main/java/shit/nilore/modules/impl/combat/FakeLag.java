package shit.nilore.modules.impl.combat;

import java.util.ArrayList;
import java.util.List;
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
import shit.nilore.event.EventTarget;
import shit.nilore.event.impl.PacketEvent;
import shit.nilore.event.impl.ReceivePacketEvent;
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

    private final List<Packet<?>> packetQueue = new ArrayList<>();
    private long lastFlushTime = 0;
    private boolean isEnemyNearby = false;
    private final List<Vec3> positionTrail = new ArrayList<>();

    public FakeLag() {
        super("FakeLag", Category.COMBAT);
        INSTANCE = this;
    }

    @Override
    protected void onEnable() {
        packetQueue.clear();
        positionTrail.clear();
        lastFlushTime = 0;
        isEnemyNearby = false;
    }

    @Override
    protected void onDisable() {
        flushAll();
    }

    // --- Tick: 检测敌人、超时释放、记录轨迹 ---
    @EventTarget
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null) return;

        double r = range.getValue().doubleValue();
        isEnemyNearby = mc.level.players().stream()
                .filter(p -> p != mc.player)
                .filter(Player::isAlive)
                .anyMatch(p -> mc.player.distanceTo(p) <= r);

        if (!packetQueue.isEmpty()) {
            positionTrail.add(mc.player.position());
            while (positionTrail.size() > 200) positionTrail.remove(0);

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

    // --- 释放所有缓存包 ---
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void flushAll() {
        if (packetQueue.isEmpty()) {
            positionTrail.clear();
            return;
        }
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
        positionTrail.clear();
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

    public boolean isLagging() {
        return !packetQueue.isEmpty();
    }

    public List<Vec3> getPositionTrail() {
        return positionTrail;
    }
}
