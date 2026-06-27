package shit.nilore.modules.impl.misc;

import shit.nilore.NiloreClient;
import shit.nilore.event.EventTarget;
import shit.nilore.event.impl.*;
import shit.nilore.hud.HudElement;
import shit.nilore.settings.impl.NumberSetting;
import shit.nilore.utils.misc.PacketUtil;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.login.*;
import net.minecraft.network.protocol.status.*;
import org.lwjgl.glfw.GLFW;
import java.awt.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BalancedTimer extends HudElement {
    private final NumberSetting mouseButton = new NumberSetting("MouseButton", 3, 0, 7, 1);
    private final NumberSetting value = new NumberSetting("Value", 0.8, 0.1, 0.8, 0.01);

    private final ConcurrentLinkedQueue<Packet<?>> packets = new ConcurrentLinkedQueue<>();
    private static final int RELEASE_SPEED = 2;
    private static int balance = 0;
    private static int delay = 0;
    private boolean needSkip = false;
    private Stage stage = Stage.IDLE;
    private float animProgress = 0f;

    public BalancedTimer() {
        super("BalancedTimer");
        this.setX(4.0f);
        this.setY(4.0f);
        this.setWidth(0.0f);
        this.setHeight(0.0f);
    }

    private void resetTimer() {
        stage = Stage.IDLE;
        NiloreClient.serverTickRate = 1.0f;
        balance = 0;
    }

    @Override
    protected void onDisable() {
        resetTimer();
        delay = 0;
        needSkip = false;
        if (!packets.isEmpty()) {
            if (!mc.isSingleplayer()) {
                packets.forEach(p -> PacketUtil.sendQueued((Packet<ServerGamePacketListener>) p));
            }
            packets.clear();
        }
    }

    @EventTarget
    public void onTick(TickEvent e) {
        if (mc.player == null || mc.level == null) {
            resetTimer();
            return;
        }
        if (GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), mouseButton.getValue().intValue()) == 1
                && balance < 20 && !needSkip && delay <= 0) {
            stage = Stage.STORE;
            NiloreClient.serverTickRate = value.getValue().floatValue();
            balance++;
        } else if (balance >= 2 && !needSkip && delay <= 0) {
            stage = Stage.RELEASE;
            NiloreClient.serverTickRate = RELEASE_SPEED;
            balance--;
        } else {
            needSkip = false;
            if (!packets.isEmpty()) {
                if (!mc.isSingleplayer()) {
                    packets.forEach(p -> PacketUtil.sendQueued((Packet<ServerGamePacketListener>) p));
                }
                packets.clear();
            }
            stage = Stage.IDLE;
            NiloreClient.serverTickRate = 1.0f;
            balance = 0;
            delay--;
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.isIncoming()) {
            Packet<?> p = event.getPacket();
            if (p instanceof ClientboundSetEntityMotionPacket s12 && s12.getId() == mc.player.getId() ||
                    p instanceof ClientboundTeleportEntityPacket s18 && s18.getId() == mc.player.getId() ||
                    p instanceof ClientboundPlayerPositionPacket) {
                needSkip = true;
                resetTimer();
                if (!packets.isEmpty()) {
                    if (!mc.isSingleplayer()) {
                        packets.forEach(pk -> PacketUtil.sendQueued((Packet<ServerGamePacketListener>) pk));
                    }
                    packets.clear();
                }
                delay = 50;
            }
        } else {
            if (stage != Stage.IDLE) {
                Packet<?> p = event.getPacket();
                if (p instanceof ServerboundHelloPacket ||
                        p instanceof ServerboundStatusRequestPacket ||
                        p instanceof ServerboundPingRequestPacket ||
                        p instanceof ServerboundKeyPacket ||
                        p instanceof ServerboundUseItemPacket ||
                        p instanceof ServerboundInteractPacket ||
                        p instanceof ServerboundChatPacket ||
                        p instanceof ServerboundPlayerActionPacket ||
                        p instanceof ServerboundUseItemOnPacket ||
                        p instanceof ServerboundMovePlayerPacket ||
                        p instanceof ServerboundSwingPacket ||
                        p instanceof ServerboundKeepAlivePacket) {
                    return;
                }
                event.setCancelled(true);
                packets.add(event.getPacket());
            }
        }
    }

    @EventTarget
    public void onWorldChange(WorldChangeEvent e) {
        balance = 0;
        delay = 0;
        stage = Stage.IDLE;
        NiloreClient.serverTickRate = 1.0f;
        packets.clear();
    }

    @Override
    public void onRender2D(Render2DEvent event, float x, float y) {
        if (!isEnabled() || mc.player == null || mc.level == null) return;

        // 计算宽高用于拖拽
        float target = Math.min(balance * 5.8F, 116);
        animProgress += (target - animProgress) * 0.3f;
        if (Math.abs(animProgress - target) < 0.1f) animProgress = target;

        String text = "Balanced Timer: " + (balance * 5) + "%";
        int textWidth = mc.font.width(text);
        int totalWidth = Math.max(textWidth, 116) + 12;
        int totalHeight = 38;

        if (balance <= 0 && animProgress <= 0.01f) {
            // 不可见时宽高设为0，避免点到隐形区域
            this.setWidth(0);
            this.setHeight(0);
            return;
        }

        this.setWidth(totalWidth);
        this.setHeight(totalHeight);

        var pose = event.poseStack();
        pose.pushPose();

        float centerX = x + totalWidth / 2f;

        // 文字
        event.guiGraphics().drawString(mc.font, text,
                (int) (centerX - textWidth / 2f), (int) (y + 8),
                new Color(200, 200, 200, 255).getRGB());

        // 进度线背景
        int barX = (int) (centerX - 58);
        int barY = (int) (y + 22);
        int barW = 116;
        int barH = 3;
        event.guiGraphics().fill(barX, barY, barX + barW, barY + barH,
                new Color(30, 30, 30, 255).getRGB());

        // 进度线前景
        if (animProgress > 0.5f) {
            event.guiGraphics().fill(barX, barY, barX + (int) animProgress, barY + barH,
                    new Color(255, 150, 150, 255).getRGB());
        }

        pose.popPose();
    }

    @Override
    public void onGlRender(GlRenderEvent glRenderEvent, float x, float y) {
    }

    @Override
    public void onSettings() {
    }

    @Override
    public void stopDragging() {
        boolean wasDragging = this.isDragging();
        super.stopDragging();
        if (wasDragging) {
            NiloreClient.getInstance().getConfigManager().saveAll();
        }
    }

    public enum Stage {
        STORE,
        IDLE,
        RELEASE
    }
}
