package client.nilore.modules.impl.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import client.nilore.event.impl.DisconnectEvent;
import client.nilore.event.impl.EntityRemoveEvent;
import client.nilore.event.impl.ReceivePacketEvent;
import client.nilore.event.impl.RenderEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.combat.antikb.AntiKBMode;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.render.RenderUtil;
import client.nilore.event.EventTarget;

/**
 * Backtrack — delays position packets for a tracked entity so the client
 * sees them at an older (further) position while their real position updates
 * silently.
 *
 * Trigger: when any player performs a melee attack (EntityRemoveEvent with
 * dead=false), the attacked entity becomes the backtrack target.  The module
 * then intercepts position-update packets for that entity, stores the newer
 * position in a map, and delays the packet.  Once the delay expires the
 * packet is released and the client "catches up".
 */
public class Backtrack extends Module {
    public static Backtrack INSTANCE;

    /* ======================== Settings ======================== */

    private final NumberSetting maxTime = new NumberSetting("Max MS", 1000.0, 50.0, 3000.0, 50.0);
    private final NumberSetting delay = new NumberSetting("Delay", 200.0, 1.0, 1000.0, 10.0);
    private final NumberSetting maxRange = new NumberSetting("Max Range", 5.0, 2.0, 12.0, 0.1);
    private final NumberSetting startRange = new NumberSetting("Start Range", 2.8, 0.1, 6.0, 0.1);
    private final BooleanSetting render = new BooleanSetting("Render", true);

    /* ======================== State ======================== */

    private final ConcurrentLinkedQueue<PacketEntry> packetQueue = new ConcurrentLinkedQueue<>();
    private final Map<Entity, Vec3> positionMap = new HashMap<>();
    private Entity target;
    private long trackingStartTime;
    private volatile boolean isBacktrackingActive;

    /* ======================== Construction ======================== */

    public Backtrack() {
        super("Backtrack", Category.COMBAT);
        INSTANCE = this;
    }

    /* ======================== Public API ======================== */

    public boolean isBlinking() {
        return this.isBacktrackingActive;
    }

    public boolean isActive() {
        return this.isBacktrackingActive && !this.packetQueue.isEmpty();
    }

    public boolean isBacktracking() {
        return this.isBacktrackingActive;
    }

    /* ======================== Lifecycle ======================== */

    @Override
    public void onEnable() {
        this.cleanup();
    }

    @Override
    public void onDisable() {
        this.releasePackets();
        this.positionMap.clear();
        this.target = null;
        this.trackingStartTime = 0L;
    }

    /* ======================== Event Handlers ======================== */

    /**
     * Trigger: a player attacked an entity (pre-attack phase).
     * The attacked entity becomes our backtrack target.
     *
     * EntityRemoveEvent(false, entity) = pre-attack
     * EntityRemoveEvent(true, entity)  = post-attack
     */
    @EventTarget
    public void onPreAttack(EntityRemoveEvent event) {
        if (event.dead()) return;
        if (this.isAntiKBActive()) return;
        if (event.entity() instanceof Player player) {
            this.target = player;
            this.trackingStartTime = System.currentTimeMillis();
            // Do NOT clear positionMap here — the need version preserves it
            // so that any already-stored positions are kept for subsequent checks.
        }
    }

    @EventTarget
    public void onReceivePacket(ReceivePacketEvent event) {
        if (event.isCancelled()) return;
        if (mc.player == null || mc.level == null) return;

        // No valid target — bail
        if (this.target == null || !this.target.isAlive() || this.target.isRemoved()) {
            this.cleanup();
            return;
        }

        // Anti-KB active — don't interfere
        if (this.isAntiKBActive()) {
            this.forceRelease();
            return;
        }

        Packet<ClientGamePacketListener> packet = event.getPacket();

        // Player-position packet for ourselves → stop everything
        if (packet instanceof ClientboundPlayerPositionPacket) {
            this.forceRelease();
            return;
        }

        // Should we keep backtracking?
        if (!this.shouldBacktrack()) {
            this.drainAndStop();
            return;
        }

        // Release any entries that have exceeded their delay
        this.processQueue();

        // Handle position updates — store the NEW position and delay the packet.
        // Relative-move packets chain off the last stored position so that
        // consecutive cancelled updates don't drift.
        if (packet instanceof ClientboundMoveEntityPacket move) {
            Entity entity = move.getEntity(mc.level);
            if (entity == null || entity.getId() != this.target.getId()) return;

            if (move.hasPosition()) {
                Vec3 base = this.positionMap.getOrDefault(this.target, entity.position());
                Vec3 newPos = base.add(
                    move.getXa() / 4096.0,
                    move.getYa() / 4096.0,
                    move.getZa() / 4096.0
                );
                this.positionMap.put(this.target, newPos);

                event.setCancelled(true);
                this.packetQueue.add(new PacketEntry(packet));
                this.isBacktrackingActive = true;
            }
            return;
        }

        if (packet instanceof ClientboundTeleportEntityPacket tp) {
            if (tp.getId() == this.target.getId()) {
                Vec3 pos = new Vec3(tp.getX(), tp.getY(), tp.getZ());
                this.positionMap.put(this.target, pos);
                event.setCancelled(true);
                this.packetQueue.add(new PacketEntry(packet));
                this.isBacktrackingActive = true;
            }
            return;
        }

        // Target entity removed from world → stop
        if (packet instanceof ClientboundRemoveEntitiesPacket remove) {
            if (remove.getEntityIds().contains(this.target.getId())) {
                this.forceRelease();
            }
            return;
        }

        // All other packets while backtracking: queue
        if (this.isBacktrackingActive) {
            event.setCancelled(true);
            this.packetQueue.add(new PacketEntry(packet));
        }
    }

    @EventTarget
    public void onRender(RenderEvent renderEvent) {
        if (!this.render.getValue()) return;
        if (!this.isBacktrackingActive || this.target == null) return;
        if (mc.player == null || mc.gameRenderer == null) return;

        Vec3 storedPos = this.positionMap.get(this.target);
        if (storedPos == null) return;

        PoseStack poseStack = renderEvent.poseStack();
        double halfWidth = this.target.getBbWidth() / 2.0;
        double height = this.target.getBbHeight();

        poseStack.pushPose();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        poseStack.translate(storedPos.x - camera.x, storedPos.y - camera.y, storedPos.z - camera.z);
        AABB box = new AABB(-halfWidth, 0.0, -halfWidth, halfWidth, height, halfWidth);
        Color fill = new Color(255, 255, 255, 64);
        RenderUtil.drawFilledColoredBox(box, poseStack, fill, fill);
        Color outline = new Color(255, 255, 255, 204);
        RenderUtil.drawColoredBox(box, poseStack, outline, outline);
        poseStack.popPose();
    }

    @EventTarget
    public void onDisconnect(DisconnectEvent event) {
        this.forceRelease();
    }

    /* ======================== Internal Logic ======================== */

    /**
     * Returns true when backtracking is worthwhile:
     * - The target is alive and within maxRange (measured as AABB-to-point
     *   distance, matching the game's own hit detection).
     * - The stored (backtracked) position is NOT closer than the entity's
     *   current client-side position — when the entity has closed the gap
     *   there's no benefit to showing an old further position.
     * - The stored position is still within startRange.
     */
    private boolean shouldBacktrack() {
        if (this.target == null || mc.player == null) return false;

        // Time limit: only applies when trackingStartTime is set (non-zero).
        // If trackingStartTime == 0 (no start recorded), the time gate is skipped entirely.
        if (this.trackingStartTime != 0L) {
            long elapsed = System.currentTimeMillis() - this.trackingStartTime;
            if (elapsed > this.maxTime.getValue().longValue()) return false;
        }

        // Health
        if (this.target instanceof LivingEntity living && living.getHealth() <= 0.0f) return false;

        // Use AABB-to-point distance (more accurate for hit detection)
        Vec3 eyePos = mc.player.getEyePosition();
        double maxRangeSq = this.maxRange.getValue().doubleValue();
        maxRangeSq = maxRangeSq * maxRangeSq;
        double currentDistSq = this.target.getBoundingBox().distanceToSqr(eyePos);
        if (currentDistSq > maxRangeSq) return false;

        // Check stored (backtracked) position if we have one
        Vec3 storedPos = this.positionMap.get(this.target);
        if (storedPos != null) {
            float hw = this.target.getBbWidth() / 2.0f;
            double y = this.target.getBbHeight();
            AABB storedBox = new AABB(
                storedPos.x - hw, storedPos.y, storedPos.z - hw,
                storedPos.x + hw, storedPos.y + y, storedPos.z + hw
            );
            double storedDistSq = storedBox.distanceToSqr(eyePos);
            double startRangeSq = this.startRange.getValue().doubleValue();
            startRangeSq = startRangeSq * startRangeSq;

            // If the stored position is *closer* than the current client-side
            // position, the entity has already closed the gap — no benefit.
            if (storedDistSq < currentDistSq) return false;
            // Stored position within start range → backtrack.
            if (storedDistSq <= startRangeSq) return true;
            // Stored too far → fall through to the default return below.
        }

        // No stored position yet, or stored position is too far:
        // backtrack if Anti-KB isn't interfering.
        return !this.isAntiKBActive();
    }

    /** Release packets that have outlived their delay. */
    private void processQueue() {
        long now = System.currentTimeMillis();
        long delayMs = this.delay.getValue().longValue();
        Iterator<PacketEntry> it = this.packetQueue.iterator();
        while (it.hasNext()) {
            PacketEntry entry = it.next();
            if (now - entry.timestamp < delayMs) break;
            it.remove();
            dispatchPacket(entry.packet);
        }
        if (this.packetQueue.isEmpty()) {
            this.isBacktrackingActive = false;
        }
    }

    /** Release all queued packets without flushing the position map / target. */
    private void releasePackets() {
        this.isBacktrackingActive = false;
        while (!this.packetQueue.isEmpty()) {
            dispatchPacket(this.packetQueue.poll().packet);
        }
        this.packetQueue.clear();
    }

    /** Release packets + clear position map + null target. */
    private void forceRelease() {
        this.releasePackets();
        this.positionMap.clear();
        this.target = null;
        this.trackingStartTime = 0L;
    }

    /** Drain and stop backtracking, but keep the target alive. */
    private void drainAndStop() {
        this.isBacktrackingActive = false;
        while (!this.packetQueue.isEmpty()) {
            dispatchPacket(this.packetQueue.poll().packet);
        }
        this.packetQueue.clear();
        this.positionMap.clear();
        this.target = null;
        this.trackingStartTime = 0L;
    }

    private void cleanup() {
        this.releasePackets();
        this.positionMap.clear();
        this.target = null;
        this.trackingStartTime = 0L;
    }

    private boolean isAntiKBActive() {
        if (AntiKB.INSTANCE != null && AntiKB.INSTANCE.isEnabled()) {
            Optional<AntiKBMode> opt = AntiKBMode.findMode(AntiKB.mode.getValue());
            return opt.isPresent() && opt.get().isActive();
        }
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void dispatchPacket(Packet packet) {
        try {
            if (mc.getConnection() != null) {
                packet.handle(mc.getConnection());
            }
        } catch (Exception ignored) {
        }
    }

    /* ======================== Inner Types ======================== */

    public static final class PacketEntry {
        public final Packet<?> packet;
        public final long timestamp;

        public PacketEntry(Packet<?> packet) {
            this.packet = packet;
            this.timestamp = System.currentTimeMillis();
        }
    }
}
