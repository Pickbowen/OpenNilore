package shit.nilore.modules.impl.movement;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;
import shit.nilore.event.impl.MotionEvent;
import shit.nilore.event.impl.PacketEvent;
import shit.nilore.event.impl.Render2DEvent;
import shit.nilore.event.impl.SprintEvent;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.utils.game.MovementUtil;
import shit.nilore.utils.misc.ChatUtil;
import shit.nilore.utils.render.RenderUtil;
import shit.nilore.utils.rotation.Rotation;
import shit.nilore.event.EventTarget;

public class FireballBlink extends Module {
    public static FireballBlink INSTANCE;
    public static Rotation rotation;

    private boolean isBackwardsMode;
    private boolean isBypassing;
    private int fireballPrepareTick;
    private int fireChargeSlot;
    private boolean isBlinking;
    private boolean wasRepositioned;
    private boolean isThrowingFireball;
    private boolean hasFiredSinceEnable;
    private boolean isSprinting;
    private long blinkStartTime;
    private int queuedFireballs;
    private int impulseCount;
    private int fireballTick;
    private int pendingReleases;
    private final List<Integer> impulsePacketBoundaries;
    private final LinkedBlockingQueue<Packet<?>> packetQueue;

    public FireballBlink() {
        super("FireballBlink", Category.MOVEMENT);
        this.isBackwardsMode = false;
        this.isBypassing = false;
        this.fireballPrepareTick = 0;
        this.fireChargeSlot = -1;
        this.isBlinking = false;
        this.wasRepositioned = false;
        this.isThrowingFireball = false;
        this.hasFiredSinceEnable = false;
        this.isSprinting = false;
        this.blinkStartTime = 0L;
        this.queuedFireballs = 0;
        this.impulseCount = 0;
        this.fireballTick = 0;
        this.pendingReleases = 0;
        this.impulsePacketBoundaries = new ArrayList<>();
        this.packetQueue = new LinkedBlockingQueue<>();
        INSTANCE = this;
    }

    private void flushPackets() {
        while (!this.packetQueue.isEmpty()) {
            try {
                Packet<?> packet = this.packetQueue.poll();
                if (packet == null || mc.getConnection() == null) continue;
                handlePacket(packet, mc.getConnection());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void handlePacket(Packet packet, PacketListener listener) {
        packet.handle(listener);
    }

    private void releaseImpulse(int index) {
        if (index >= this.impulsePacketBoundaries.size()) return;
        int boundary = this.impulsePacketBoundaries.get(index);
        int count = 0;
        while (!this.packetQueue.isEmpty() && count <= boundary) {
            try {
                Packet<?> packet = this.packetQueue.poll();
                if (packet != null && mc.getConnection() != null) {
                    handlePacket(packet, mc.getConnection());
                }
                ++count;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        for (int i = index + 1; i < this.impulsePacketBoundaries.size(); ++i) {
            this.impulsePacketBoundaries.set(i, this.impulsePacketBoundaries.get(i) - (boundary + 1));
        }
    }

    private int getFireChargeSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 36; ++i) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty() || stack.getItem() != Items.FIRE_CHARGE) continue;
            return i;
        }
        return -1;
    }

    private int countFireCharges() {
        if (mc.player == null) return 0;
        int total = 0;
        for (int i = 0; i < 36; ++i) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() != Items.FIRE_CHARGE) continue;
            total += stack.getCount();
        }
        return total;
    }

    private int ensureFireChargeSlot() {
        int slot = this.getFireChargeSlot();
        if (slot == -1) {
            ChatUtil.print("No fire charge found");
            this.setEnabled(false);
        }
        return slot;
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        this.hasFiredSinceEnable = false;
        this.impulseCount = 0;
        this.queuedFireballs = 0;
        this.pendingReleases = 0;
        this.impulsePacketBoundaries.clear();
        this.packetQueue.clear();
        this.isBackwardsMode = false;
        this.isBypassing = false;
        this.fireballPrepareTick = 0;
        this.fireChargeSlot = -1;
        this.isBlinking = false;
        this.wasRepositioned = false;
        this.isThrowingFireball = false;
        this.isSprinting = false;
        this.blinkStartTime = 0L;
        this.fireballTick = 0;
    }

    @Override
    protected void onDisable() {
        this.isBlinking = false;
        this.flushPackets();
        this.impulsePacketBoundaries.clear();
        this.packetQueue.clear();
        rotation = null;
        super.onDisable();
    }

    @EventTarget
    public void onSprint(SprintEvent event) {
        if (!this.isEnabled() || mc.player == null) {
            return;
        }
        if (this.wasRepositioned) {
            this.setEnabled(false);
            return;
        }
        if (this.isBypassing) {
            if (!MovementUtil.isInputActive()) {
                this.isBackwardsMode = true;
            }
            this.isBypassing = false;
        }
        boolean middleDown = GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), 3) == 1;
        if (middleDown && !this.hasFiredSinceEnable) {
            this.hasFiredSinceEnable = true;
            if (!this.isThrowingFireball && this.fireballPrepareTick == 0) {
                int slot = this.ensureFireChargeSlot();
                if (slot != -1) {
                    this.fireChargeSlot = mc.player.getInventory().selected;
                    mc.player.getInventory().selected = slot;
                    this.fireballPrepareTick = 1;
                    ChatUtil.print("Queued fireball " + (this.queuedFireballs + 1));
                }
            }
        } else if (!middleDown) {
            this.hasFiredSinceEnable = false;
        }
        boolean mouse4Down = GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), 4) == 1;
        if (mouse4Down && !this.isSprinting) {
            this.isSprinting = true;
            if (this.isBlinking && this.pendingReleases < this.impulseCount) {
                ChatUtil.print("Release " + (this.pendingReleases + 1) + "/" + this.impulseCount);
                this.releaseImpulse(this.pendingReleases);
                this.pendingReleases++;
                if (this.pendingReleases >= this.impulseCount) {
                    ChatUtil.print("Blink finished");
                    this.isBlinking = false;
                    this.setEnabled(false);
                }
            } else if (!this.isBlinking) {
                ChatUtil.print("No blink to release");
                this.setEnabled(false);
            } else {
                ChatUtil.print("All impulses released");
            }
        } else if (!mouse4Down) {
            this.isSprinting = false;
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        String status;
        if (this.isBlinking) {
            int qSize = this.packetQueue.size();
            long elapsed = System.currentTimeMillis() - this.blinkStartTime;
            status = String.format("Blinking: %d packets, %.1fs, %d/%d", qSize, elapsed / 1000.0f, this.pendingReleases, this.impulseCount);
        } else if (this.isThrowingFireball) {
            status = "Throwing fireball x" + this.queuedFireballs;
        } else {
            status = "FireballBlink ready";
        }
        float textX = width / 2.0f - mc.font.width(status) / 2.0f;
        float textY = height / 2.0f + 20.0f;
        event.guiGraphics().drawString(mc.font, status, (int) textX, (int) textY, -1);
        if (this.isBlinking) {
            float barWidth = 180.0f;
            float barHeight = 6.0f;
            float barX = width / 2.0f - barWidth / 2.0f;
            float barY = height / 2.0f + 35.0f;
            long elapsed = System.currentTimeMillis() - this.blinkStartTime;
            float progress = Math.min(1.0f, elapsed / 10000.0f);
            RenderUtil.drawRoundedRect(event.poseStack(), barX, barY, barWidth, barHeight, 2.0f, new Color(0, 0, 0, 128).getRGB());
            if (progress > 0.0f) {
                Color color = new Color(0, 200, 0, 200);
                RenderUtil.drawRoundedRect(event.poseStack(), barX, barY, barWidth * progress, barHeight, 2.0f, color.getRGB());
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent packetEvent) {
        if (!this.isEnabled() || mc.level == null || mc.player == null) {
            if (this.isBlinking) {
                mc.execute(() -> {
                    this.flushPackets();
                    this.isBlinking = false;
                });
            }
            return;
        }
        if (this.isBlinking && packetEvent.isIncoming()) {
            Packet<?> packet = packetEvent.getPacket();
            if (packet instanceof ClientboundPlayerPositionPacket) {
                this.wasRepositioned = true;
                packetEvent.setCancelled(true);
                return;
            }
            if (packet instanceof ClientboundSetEntityMotionPacket motion
                    && motion.getId() == mc.player.getId()) {
                ++this.impulseCount;
                this.impulsePacketBoundaries.add(this.packetQueue.size());
                mc.execute(() -> ChatUtil.print("Impulse " + this.impulseCount + " queued"));
            }
            packetEvent.setCancelled(true);
            this.packetQueue.add(packet);
            return;
        }
        Packet<?> packet = packetEvent.getPacket();
        if (packet instanceof ClientboundSetEntityMotionPacket motion
                && packetEvent.isIncoming()
                && motion.getId() == mc.player.getId()
                && this.queuedFireballs > 0
                && !this.isBlinking) {
            ++this.impulseCount;
            this.impulsePacketBoundaries.add(this.packetQueue.size());
            mc.execute(() -> ChatUtil.print("Fireball impulse " + this.impulseCount));
            packetEvent.setCancelled(true);
            this.packetQueue.add(packetEvent.getPacket());
            this.isBlinking = true;
            this.blinkStartTime = System.currentTimeMillis();
            mc.execute(() -> ChatUtil.print("Blink started"));
        }
    }

    @EventTarget
    public void onMotion(MotionEvent event) {
        if (!this.isEnabled() || mc.player == null) {
            return;
        }
        if (event.isPost()) {
            if (this.fireballPrepareTick > 0) {
                if (this.fireballPrepareTick == 1) {
                    this.queuedFireballs++;
                    ChatUtil.print("Preparing fireball #" + this.queuedFireballs);
                    mc.options.keyJump.setDown(true);
                    float yaw;
                    float pitch;
                    if (!this.isBackwardsMode) {
                        yaw = mc.player.getYRot() - 180.0f;
                        pitch = 88.0f;
                    } else {
                        yaw = mc.player.getYRot();
                        pitch = 90.0f;
                    }
                    rotation = new Rotation(yaw, pitch);
                }
                if (this.fireballPrepareTick >= 2) {
                    this.fireballPrepareTick = 0;
                    int slot = this.ensureFireChargeSlot();
                    if (slot != -1) {
                        mc.player.getInventory().selected = slot;
                        this.fireballTick = this.countFireCharges();
                        mc.options.keyUse.setDown(true);
                        this.isThrowingFireball = true;
                        ChatUtil.print("Throwing fireball " + this.queuedFireballs + " (charges=" + this.fireballTick + ")");
                    } else {
                        this.setEnabled(false);
                    }
                }
                if (this.fireballPrepareTick != 0) {
                    this.fireballPrepareTick++;
                }
            }
        } else if (this.isThrowingFireball) {
            int current = this.countFireCharges();
            if (current < this.fireballTick) {
                mc.options.keyUse.setDown(false);
                mc.options.keyJump.setDown(false);
                rotation = null;
                this.isThrowingFireball = false;
                ChatUtil.print("Used fireball " + this.queuedFireballs + " (had " + this.fireballTick + ", now " + current + ")");
            } else if (this.getFireChargeSlot() == -1) {
                mc.options.keyUse.setDown(false);
                mc.options.keyJump.setDown(false);
                rotation = null;
                this.isThrowingFireball = false;
                ChatUtil.print("Out of fire charges");
            }
        }
    }
}
