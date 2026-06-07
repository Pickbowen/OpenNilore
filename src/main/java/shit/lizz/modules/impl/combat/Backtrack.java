package shit.lizz.modules.impl.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import shit.lizz.event.impl.DisconnectEvent;
import shit.lizz.event.impl.EntityRemoveEvent;
import shit.lizz.event.impl.ReceivePacketEvent;
import shit.lizz.event.impl.RenderEvent;
import shit.lizz.event.impl.TickEvent;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.modules.impl.combat.antikb.AntiKBMode;
import shit.lizz.settings.impl.BooleanSetting;
import shit.lizz.settings.impl.NumberSetting;
import shit.lizz.utils.game.RotationUtil;
import shit.lizz.utils.render.RenderUtil;
import shit.lizz.event.EventTarget;

public class Backtrack extends Module {
    public static Backtrack INSTANCE;

    public static final class PacketEntry {
        public final Packet<?> packet;
        public final long timestamp;

        public PacketEntry(Packet<?> packet) {
            this.packet = packet;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public static final class PositionTracker {
        public final Player player;
        public Vec3 currentPos;
        public Vec3 lastPos;

        public PositionTracker(Player player, Vec3 pos) {
            this.player = player;
            this.currentPos = pos;
            this.lastPos = pos;
        }

        public Vec3 decodeRelativePos(short xa, short ya, short za) {
            double dx = xa / 4096.0;
            double dy = ya / 4096.0;
            double dz = za / 4096.0;
            return this.currentPos.add(dx, dy, dz);
        }

        public void updatePos(Vec3 pos) {
            this.lastPos = this.currentPos;
            this.currentPos = pos;
        }

        public void applyPos() {
            this.lastPos = this.currentPos;
        }

        public Vec3 getInterpolatedPos(float partial) {
            return new Vec3(
                    this.lastPos.x + (this.currentPos.x - this.lastPos.x) * partial,
                    this.lastPos.y + (this.currentPos.y - this.lastPos.y) * partial,
                    this.lastPos.z + (this.currentPos.z - this.lastPos.z) * partial);
        }
    }

    private final NumberSetting minRange = new NumberSetting("Min Range", 3.0, 1.0, 6.0, 0.1);
    private final NumberSetting maxRange = new NumberSetting("Max Range", 6.0, 1.0, 6.0, 0.1);
    private final NumberSetting delay = new NumberSetting("Delay", 200.0, 0.0, 1000.0, 10.0);
    private final NumberSetting chance = new NumberSetting("Chance", 100.0, 5.0, 100.0, 1.0);
    private final BooleanSetting resetOnVelocity = new BooleanSetting("Reset On Velocity", true);
    private final BooleanSetting render = new BooleanSetting("Render", true);
    private final ConcurrentLinkedQueue<PacketEntry> packetQueue = new ConcurrentLinkedQueue<>();
    private volatile PositionTracker positionTracker;
    private volatile boolean isBacktrackingActive;

    public Backtrack() {
        super("Backtrack", Category.COMBAT);
        INSTANCE = this;
    }

    public boolean isBlinking() {
        return this.isBacktrackingActive;
    }

    @Override
    public void onEnable() {
        this.positionTracker = null;
        this.isBacktrackingActive = false;
        this.packetQueue.clear();
    }

    @Override
    public void onDisable() {
        this.positionTracker = null;
        this.releasePackets();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null) {
            this.positionTracker = null;
            this.releasePackets();
            return;
        }
        if (this.positionTracker == null) {
            this.releasePackets();
            return;
        }
        if (!this.positionTracker.player.isAlive() || this.positionTracker.player.isRemoved()) {
            this.positionTracker = null;
            this.releasePackets();
            return;
        }
        if (this.resetOnVelocity.getValue() && this.isAntiKBActive()) {
            this.positionTracker = null;
            this.releasePackets();
            return;
        }
        this.positionTracker.applyPos();
        if (this.isBacktrackingActive) {
            this.checkBacktrackRange(this.positionTracker);
            this.processQueue();
        }
    }

    @EventTarget
    public void onEntityRemove(EntityRemoveEvent event) {
        if (event.dead()) return;
        if (event.entity() instanceof Player player) {
            if (Math.random() <= this.chance.getValue().doubleValue() / 100.0) {
                if (this.resetOnVelocity.getValue() && this.isAntiKBActive()) return;
                if (this.positionTracker == null) {
                    this.releasePackets();
                    this.positionTracker = new PositionTracker(player, player.position());
                }
            } else {
                this.positionTracker = null;
            }
        }
    }

    @EventTarget(value = 1)
    public void onReceivePacket(ReceivePacketEvent event) {
        PositionTracker tracker = this.positionTracker;
        if (tracker == null) return;
        if (mc.player == null || mc.level == null) return;
        if (event.isCancelled()) return;
        Packet<ClientGamePacketListener> packet = event.getPacket();
        if (packet instanceof ClientboundMoveEntityPacket move && move.hasPosition()) {
            Entity entity = move.getEntity(mc.level);
            if (entity != null && entity.getId() == tracker.player.getId()) {
                Vec3 pos = tracker.decodeRelativePos(move.getXa(), move.getYa(), move.getZa());
                tracker.currentPos = pos;
                this.checkBacktrackRange(tracker);
            }
        } else if (packet instanceof ClientboundTeleportEntityPacket tp) {
            if (tp.getId() == tracker.player.getId()) {
                Vec3 pos = new Vec3(tp.getX(), tp.getY(), tp.getZ());
                tracker.updatePos(pos);
                tracker.currentPos = pos;
                this.checkBacktrackRange(tracker);
            }
        } else if (packet instanceof ClientboundRemoveEntitiesPacket remove) {
            if (remove.getEntityIds().contains(tracker.player.getId())) {
                this.positionTracker = null;
                this.releasePackets();
                return;
            }
        } else if (packet instanceof ClientboundPlayerPositionPacket) {
            this.positionTracker = null;
            this.releasePackets();
            return;
        }
        if (this.isBacktrackingActive) {
            if (this.resetOnVelocity.getValue()
                    && packet instanceof ClientboundSetEntityMotionPacket motion) {
                if (mc.player != null && motion.getId() == mc.player.getId()) {
                    this.positionTracker = null;
                    this.releasePackets();
                    return;
                }
            }
            this.packetQueue.add(new PacketEntry(packet));
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onRender(RenderEvent renderEvent) {
        if (!(Boolean) this.render.getValue()) return;
        if (!this.isBacktrackingActive) return;
        PositionTracker tracker = this.positionTracker;
        if (tracker == null) return;
        if (mc.player == null || mc.gameRenderer == null) return;
        PoseStack poseStack = renderEvent.poseStack();
        Vec3 pos = tracker.getInterpolatedPos(renderEvent.partialTick());
        double halfWidth = tracker.player.getBbWidth() / 2.0;
        double height = tracker.player.getBbHeight();
        poseStack.pushPose();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        poseStack.translate(pos.x - camera.x, pos.y - camera.y, pos.z - camera.z);
        AABB box = new AABB(-halfWidth, 0.0, -halfWidth, halfWidth, height, halfWidth);
        Color fill = new Color(255, 255, 255, 64);
        RenderUtil.drawFilledColoredBox(box, poseStack, fill, fill);
        Color outline = new Color(255, 255, 255, 204);
        RenderUtil.drawColoredBox(box, poseStack, outline, outline);
        poseStack.popPose();
    }

    @EventTarget
    public void onDisconnect(DisconnectEvent event) {
        this.positionTracker = null;
        this.releasePackets();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void handlePacket(Packet packet, PacketListener listener) {
        packet.handle(listener);
    }

    private void processQueue() {
        long now = System.currentTimeMillis();
        long delayMs = this.delay.getValue().longValue();
        while (!this.packetQueue.isEmpty()) {
            PacketEntry entry = this.packetQueue.peek();
            if (now - entry.timestamp < delayMs) break;
            this.packetQueue.poll();
            try {
                if (mc.getConnection() == null) continue;
                handlePacket(entry.packet, mc.getConnection());
            } catch (Exception ignored) {
            }
        }
        if (this.packetQueue.isEmpty()) {
            this.positionTracker = null;
            this.isBacktrackingActive = false;
        }
    }

    public boolean isActive() {
        return this.isBacktrackingActive && !this.packetQueue.isEmpty();
    }

    public boolean isBacktracking() {
        return this.isBacktrackingActive;
    }

    private boolean isAntiKBActive() {
        if (AntiKB.INSTANCE != null && AntiKB.INSTANCE.isEnabled()) {
            Optional<AntiKBMode> opt = AntiKBMode.findMode(AntiKB.mode.getValue());
            return opt.isPresent() && opt.get().isActive();
        }
        return false;
    }

    private void checkBacktrackRange(PositionTracker tracker) {
        if (tracker == null || mc.player == null) return;
        Vec3 eye = mc.player.getEyePosition();
        double halfWidth = tracker.player.getBbWidth() / 2.0;
        AABB box = new AABB(
                tracker.currentPos.x - halfWidth, tracker.currentPos.y, tracker.currentPos.z - halfWidth,
                tracker.currentPos.x + halfWidth, tracker.currentPos.y + tracker.player.getBbHeight(), tracker.currentPos.z + halfWidth);
        Vec3 closest = RotationUtil.closestPoint(eye, box);
        Vec3 realClosest = RotationUtil.closestPoint(eye, tracker.player.getBoundingBox());
        double distance = eye.distanceTo(closest);
        double realDistance = eye.distanceTo(realClosest);
        if (realDistance <= 3.0
                && distance >= this.minRange.getValue().doubleValue()
                && distance < this.maxRange.getValue().doubleValue()) {
            this.isBacktrackingActive = true;
        } else {
            this.positionTracker = null;
            this.releasePackets();
        }
    }

    private void releasePackets() {
        if (this.isBacktrackingActive) {
            this.isBacktrackingActive = false;
            while (!this.packetQueue.isEmpty()) {
                PacketEntry entry = this.packetQueue.poll();
                try {
                    if (mc.getConnection() == null) continue;
                    handlePacket(entry.packet, mc.getConnection());
                } catch (Exception ignored) {
                }
            }
        }
        this.packetQueue.clear();
    }
}
