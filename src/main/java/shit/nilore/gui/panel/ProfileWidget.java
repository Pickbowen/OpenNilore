package shit.nilore.gui.panel;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import shit.nilore.ClientBase;
import shit.nilore.NiloreClient;
import shit.nilore.gui.panel.SettingsPopup;
import shit.nilore.render.DrawContext;
import shit.nilore.render.FontPresets;
import shit.nilore.render.FontRenderer;
import shit.nilore.render.GlHelper;
import shit.nilore.render.Renderer;
import shit.nilore.render.TextGlow;
import shit.nilore.utils.math.LerpUtil;
import shit.nilore.utils.render.RenderUtil;

public class ProfileWidget
extends ClientBase {
    private float hoverAlpha = 0.0f;
    private boolean isHovered = false;
    private final SettingsPopup settingsPopup;

    public ProfileWidget(Consumer<Float> scaleChangeCallback) {
        this.settingsPopup = new SettingsPopup(scaleChangeCallback);
    }

    public void render(GuiGraphics guiGraphics, int originX, int originY, int mouseX, int mouseY, float scale, float alpha) {
        if (mc.player == null) {
            return;
        }
        try {
            int avatarSize = (int)(20.0f * scale);
            int marginX = (int)(20.0f * scale);
            int textGap = (int)(10.0f * scale);
            int marginY = (int)(20.0f * scale);
            float cornerRadius = 6.0f * scale;
            int baseX = originX + marginX;
            int baseY = originY + marginY;
            String userId = this.getUserId();
            String userRole = this.getUserRole();
            int textX = baseX + avatarSize + textGap - (int)(11.0f * scale);
            int textY = baseY + avatarSize / 2 - (int)(10.0f * scale);
            int avatarX = textX - avatarSize - (int)(5.0f * scale);
            int avatarY = textY - (int)(8.0f * scale);
            this.checkHover(avatarX, avatarY, mouseX, mouseY, avatarSize);
            this.updateHoverAlpha();
            Renderer.renderConsumer(drawContext -> {
                if (this.hoverAlpha > 0.01f) {
                    int hoverColor = new Color(255, 255, 255, (int)(30.0f * this.hoverAlpha * alpha)).getRGB();
                    RenderUtil.drawRoundedRect(guiGraphics.pose(), avatarX - 2, avatarY - 2, avatarSize + 4, avatarSize + 4, cornerRadius + 1.0f, hoverColor);
                }
                if (mc.player instanceof AbstractClientPlayer) {
                    GlHelper.drawPlayerHeadRounded(mc.player, avatarX, avatarY, avatarSize, avatarSize, alpha, cornerRadius);
                }
                FontRenderer nameFont = FontPresets.axiformaRegular(14.0f * scale);
                int glowColor = new Color(255, 255, 255, (int)(100.0f * alpha)).getRGB();
                TextGlow.drawGlowText(userId, textX, textY, nameFont, this.applyAlpha(-1, alpha), glowColor, 8.0f * scale);
                float nameWidth = GlHelper.getStringWidth(userId, nameFont);
                int roleBoxX = (int)((float)textX + nameWidth + 8.0f * scale);
                int roleBoxY = textY - (int)(6.0f * scale);
                Color roleColor = this.getRoleColor(userRole);
                FontRenderer roleFont = FontPresets.axiformaBold(11.0f * scale);
                float roleStrWidth = GlHelper.getStringWidth(userRole, roleFont);
                int roleBoxW = (int)(roleStrWidth + 8.0f * scale);
                int roleBoxH = (int)(10.0f * scale);
                int roleShadowColor = new Color(roleColor.getRed(), roleColor.getGreen(), roleColor.getBlue(), (int)(180.0f * alpha)).getRGB();
                RenderUtil.drawRoundedRect(guiGraphics.pose(), roleBoxX - 3, (float)roleBoxY + 2.5f * scale - 2.0f, roleBoxW + 2, roleBoxH + 2, 5.0f * scale, this.applyAlpha(roleShadowColor, alpha * 0.35f));
                RenderUtil.drawRoundedRect(guiGraphics.pose(), roleBoxX - 2, (float)roleBoxY + 3.5f * scale - 2.0f, roleBoxW, roleBoxH, 4.0f * scale, this.applyAlpha(roleColor.getRGB(), alpha));
                int roleTextGlow = new Color(255, 255, 255, (int)(120.0f * alpha)).getRGB();
                TextGlow.drawGlowText(userRole, (float)roleBoxX + 1.5f * scale, (float)roleBoxY + 6.5f * scale, roleFont, this.applyAlpha(-1, alpha), roleTextGlow, 5.0f * scale);
            });
            this.settingsPopup.render(guiGraphics, mouseX, mouseY, scale, alpha);
        } catch (Exception exception) {
            // empty catch block
        }
    }

    private int applyAlpha(int color, float alpha) {
        int origAlpha = color >> 24 & 0xFF;
        int newAlpha = (int)((float)origAlpha * alpha);
        return newAlpha << 24 | color & 0xFFFFFF;
    }

    private String getUserId() {
        return NiloreClient.username != null && !NiloreClient.username.isEmpty() ? NiloreClient.username : "Unknown";
    }

    private String getUserRole() {
        try {
            if (false) {
                List<String> roles = new ArrayList<>();
                if (roles.contains("ROLE_OWNER")) return "Premium";
                if (roles.contains("ROLE_ADMIN")) return "Admin";
                if (roles.contains("ROLE_BETA")) return "Beta";
                return roles.get(0).replace("ROLE_", "");
            }
        } catch (Exception exception) {
            // empty catch block
        }
        return "User";
    }

    public boolean isMouseOverAvatar(int originX, int originY, int mouseX, int mouseY, float scale) {
        int avatarSize = (int)(20.0f * scale);
        int marginX = (int)(20.0f * scale);
        int textGap = (int)(10.0f * scale);
        int marginY = (int)(20.0f * scale);
        int baseX = originX + marginX;
        int baseY = originY + marginY;
        int textX = baseX + avatarSize + textGap - (int)(11.0f * scale);
        int textY = baseY + avatarSize / 2 - (int)(10.0f * scale);
        int avatarX = textX - avatarSize - (int)(5.0f * scale);
        int avatarY = textY - (int)(8.0f * scale);
        return mouseX >= avatarX && mouseX <= avatarX + avatarSize && mouseY >= avatarY && mouseY <= avatarY + avatarSize;
    }

    public boolean onMouseClick(int originX, int originY, int mouseX, int mouseY, float scale) {
        if (this.settingsPopup.isOpen() && this.settingsPopup.onMouseClick(mouseX, mouseY, scale)) {
            return true;
        }
        if (this.isMouseOverAvatar(originX, originY, mouseX, mouseY, scale)) {
            this.settingsPopup.toggleOpen();
            return true;
        }
        return false;
    }

    private Color getRoleColor(String role) {
        switch (role.toLowerCase()) {
            case "owner":
                return new Color(220, 53, 69);
            case "admin":
                return new Color(255, 193, 7);
            case "beta":
                return new Color(108, 117, 225);
            case "vip":
                return new Color(40, 167, 69);
            case "premium":
                return new Color(102, 16, 242);
            default:
                return new Color(108, 117, 125);
        }
    }

    private void checkHover(int avatarX, int avatarY, int mouseX, int mouseY, int avatarSize) {
        this.isHovered = mouseX >= avatarX && mouseX <= avatarX + avatarSize && mouseY >= avatarY && mouseY <= avatarY + avatarSize;
    }

    private void updateHoverAlpha() {
        this.hoverAlpha = this.isHovered ? LerpUtil.lerp(this.hoverAlpha, 1.0f, 0.12f) : LerpUtil.lerp(this.hoverAlpha, 0.0f, 0.12f);
    }

    public void onMouseDrag(int mouseX, int mouseY) {
        this.settingsPopup.onMouseDrag(mouseX, mouseY);
    }

    public void onMouseRelease() {
        this.settingsPopup.stopDrag();
    }

    public boolean isPopupOpen() {
        return this.settingsPopup.isOpen();
    }
}
