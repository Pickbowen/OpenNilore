package client.nilore.patch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;

import client.nilore.NiloreClient;
import client.nilore.hud.ScoreboardHud;
import client.nilore.render.DrawContext;
import client.nilore.render.Paint;
import client.nilore.render.RoundedRectangle;
import client.nilore.utils.render.RenderUtil;

/**
 * Patches {@link Gui#displayScoreboardSidebar} to cancel vanilla
 * rendering and draw the custom scoreboard on the <em>real</em>
 * GuiGraphics (the one from {@code Gui.render()}), so draw calls
 * actually reach the screen.
 */
@Patch(Gui.class)
public class GuiPatch {

    private static final int   MAX_ROWS  = 15;
    private static final float PAD       = 3.0f;
    private static final float ROW_H     = 10.0f;

    @Inject(
            method = "displayScoreboardSidebar",
            desc = "(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/scores/Objective;)V",
            at = @At(At.Type.HEAD)
    )
    public static void onDisplayScoreboardSidebar(Gui gui, GuiGraphics guiGraphics, Objective objective, CallbackInfo ci) {
        if (!NiloreClient.isReady()) return;

        ScoreboardHud hud = NiloreClient.getInstance().getHudManager().getHudElement(ScoreboardHud.class);
        if (hud == null || !hud.isEnabled()) return;

        // cancel the vanilla render
        ci.cancel();

        // render custom scoreboard on the real GuiGraphics
        renderOnRealGui(guiGraphics, objective, hud);
    }

    private static void renderOnRealGui(GuiGraphics guiGraphics, Objective objective, ScoreboardHud hud) {
        Scoreboard scoreboard = objective.getScoreboard();
        Component title = objective.getDisplayName();
        Collection<Score> allScores = scoreboard.getPlayerScores(objective);

        List<Score> scoreList = allScores.stream()
                .filter(s -> s.getScore() > 0 || scoreboard.getPlayersTeam(s.getOwner()) != null)
                .sorted((a, b) -> Integer.compare(b.getScore(), a.getScore()))
                .limit(MAX_ROWS)
                .collect(Collectors.toList());

        if (scoreList.isEmpty()) return;

        var font = hud.mc.font;
        int titleWidth = font.width(title);
        int maxWidth = titleWidth;

        // name + score widths per row
        List<Component> names = new ArrayList<>();
        List<String> scoreTexts = new ArrayList<>();
        for (Score s : scoreList) {
            PlayerTeam team = scoreboard.getPlayersTeam(s.getOwner());
            Component name = PlayerTeam.formatNameForTeam(team, Component.literal(s.getOwner()));
            String scoreText = ChatFormatting.RED.toString() + s.getScore();
            names.add(name);
            scoreTexts.add(scoreText);
            maxWidth = Math.max(maxWidth, font.width(name) + 2 + font.width(scoreText));
        }

        float cw = maxWidth + PAD * 2;
        float ch = PAD + ROW_H + scoreList.size() * ROW_H + PAD;

        float dx = hud.getX();
        float dy = hud.getY();

        // first-time auto-position — only fires once per session,
        // and only if the HUD is still at its default (0, 20) position
        // (i.e. the player hasn't moved it or config hasn't restored a saved position).
        if (!hud.isAtDefaultPosition()) {
            hud.markPositionLoaded();
        }
        if (!hud.autoPositioned() && hud.isAtDefaultPosition()) {
            dx = hud.mc.getWindow().getGuiScaledWidth() - cw - 3.0f;
            dy = 20.0f;
            hud.setX(dx);
            hud.setY(dy);
            hud.markPositionLoaded();
        }

        float radius = Math.min(hud.backgroundRadius.getValue().floatValue(), Math.min(cw, ch) * 0.5f);

        // The scoreboard is drawn from the middle of the vanilla HUD pass, where a half-built GUI
        // batch is still pending. Flush it before the immediate-mode glow/blur draws below, so they
        // cannot interleave with it and the blur captures everything drawn so far this frame.
        guiGraphics.flush();

        // ── glow (outer ring only — never fills the centre, so it can sit behind a
        //      translucent background without brightening it) ──
        if (hud.glowEnabled.getValue()) {
            int gRadius = hud.glowRadius.getValue().intValue();
            int gAlpha = hud.glowAlpha.getValue().intValue();
            if (gAlpha > 0 && gRadius > 0) {
                DrawContext.getRoundedRectShader().drawGlow(guiGraphics.pose().last().pose(),
                        dx, dy, dx + cw, dy + ch,
                        radius, radius, radius, radius,
                        gRadius, gAlpha << 24);
            }
        }

        // ── background / blur ──
        if (hud.blurEnabled.getValue()) {
            float opacity = hud.blurOpacity.getValue().floatValue();
            if (opacity > 0.0f) {
                RenderUtil.drawBlurredRect(guiGraphics.pose(), dx, dy, cw, ch, radius,
                        hud.blurStrength.getValue().floatValue(), opacity, 0);
            }
        }
        if (hud.backgroundEnabled.getValue()) {
            int alpha = hud.backgroundAlpha.getValue().intValue();
            if (alpha > 0) {
                Paint bgPaint = new Paint();
                bgPaint.setColor((alpha << 24) | 0x000000);
                bgPaint.setAntialias(true);
                DrawContext dc = new DrawContext(guiGraphics, guiGraphics.pose());
                if (radius <= 0.0f) {
                    dc.drawRectXYWH(dx, dy, cw, ch, bgPaint);
                } else {
                    dc.drawRoundedRect(RoundedRectangle.ofXYWHR(dx, dy, cw, ch, radius), bgPaint);
                }
                bgPaint.close();
            }
        }

        // ── title (centered) ──
        int titleX = (int) (dx + PAD + (maxWidth - titleWidth) / 2.0f);
        int titleY = (int) (dy + PAD);
        guiGraphics.drawString(font, title, titleX, titleY, 0xFFFFFF, false);

        // ── rows ──
        float cursorY = dy + PAD + ROW_H;
        for (int i = 0; i < scoreList.size(); i++) {
            int y0 = (int) cursorY;
            guiGraphics.drawString(font, names.get(i), (int) (dx + PAD), y0, 0xFFFFFF, false);
            String st = scoreTexts.get(i);
            guiGraphics.drawString(font, st, (int) (dx + cw - PAD - font.width(st)), y0, 0xFFFFFF, false);
            cursorY += ROW_H;
        }

        hud.setWidth(cw);
        hud.setHeight(ch);
        hud.clampToScreen(cw, ch);
    }
}