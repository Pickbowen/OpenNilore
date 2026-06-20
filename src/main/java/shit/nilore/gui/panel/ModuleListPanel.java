package shit.nilore.gui.panel;

import java.awt.Color;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.client.gui.GuiGraphics;
import shit.nilore.ClientBase;
import shit.nilore.NiloreClient;
import shit.nilore.gui.PanelClickGui;
import shit.nilore.modules.Category;
import shit.nilore.modules.Module;
import shit.nilore.render.DrawContext;
import shit.nilore.render.FontPresets;
import shit.nilore.render.FontRenderer;
import shit.nilore.render.GlHelper;
import shit.nilore.render.Rectangle;
import shit.nilore.render.Renderer;
import shit.nilore.render.TextGlow;
import shit.nilore.utils.math.LerpUtil;
import shit.nilore.utils.render.RenderUtil;

public class ModuleListPanel
extends ClientBase {
    public enum AnimationState { NONE, FADE_IN, FADE_OUT, SWITCHING }

    private static final int HOVER_BG_COLOR = new Color(255, 255, 255, 20).getRGB();
    private final Map<Module, Float> hoverAnimations = new HashMap<>();
    private Module hoveredModule;
    private ModuleListPanel.AnimationState animationState = ModuleListPanel.AnimationState.NONE;
    private float animProgress = 0.0f;
    private Category currentCategory;
    private Category prevCategory;
    private List<Module> currentModules;
    private List<Module> prevModules;
    private float lastScale = 1.0f;
    private float scrollOffset = 0.0f;
    private float scrollTarget = 0.0f;
    private float totalContentHeight = 0.0f;
    private boolean isDraggingScrollbar = false;
    private float scrollbarDragStartY = 0.0f;
    private float scrollOffsetAtDragStart = 0.0f;
    private float scrollbarAlpha = 0.0f;
    private long lastScrollTime = 0L;
    private String searchQuery = "";
    private List<Module> searchResults;

    public void setSearchQuery(String query) {
        if (query == null) {
            this.searchQuery = "";
            this.searchResults = null;
            return;
        }
        this.searchQuery = query;
        this.searchResults = !query.isEmpty() ? NiloreClient.instance.getModuleManager().getModules().stream().filter(module -> module.getName().toLowerCase().contains(query.toLowerCase())).sorted(Comparator.comparing(Module::getName)).collect(Collectors.toList()) : NiloreClient.instance.getModuleManager().getModules().stream().sorted(Comparator.comparing(Module::getName)).collect(Collectors.toList());
        this.currentCategory = null;
        this.scrollOffset = 0.0f;
        this.scrollTarget = 0.0f;
        this.animationState = ModuleListPanel.AnimationState.NONE;
    }

    public void render(GuiGraphics guiGraphics, int originX, int originY, int mouseX, int mouseY, Category category, float scale, float alpha) {
        if (Math.abs(scale - this.lastScale) > 0.001f) {
            this.rescaleScroll(scale);
            this.lastScale = scale;
        }
        this.scrollOffset = Math.abs(this.scrollOffset - this.scrollTarget) > 0.01f ? LerpUtil.smoothLerp(this.scrollOffset, this.scrollTarget, 0.35f) : this.scrollTarget;
        if (this.searchResults != null) {
            this.renderSearchResults(guiGraphics, originX, originY, mouseX, mouseY, scale, alpha);
            return;
        }
        if (this.currentCategory != category) {
            this.scrollOffset = 0.0f;
            this.scrollTarget = 0.0f;
            if (this.currentCategory != null) {
                this.prevCategory = this.currentCategory;
                this.prevModules = this.currentModules;
                this.animationState = ModuleListPanel.AnimationState.FADE_OUT;
                this.animProgress = 0.0f;
            } else if (category != null) {
                this.animationState = ModuleListPanel.AnimationState.FADE_IN;
                this.animProgress = 0.0f;
            }
            this.currentCategory = category;
            List<Module> filtered = this.currentModules = category == null ? null : NiloreClient.instance.getModuleManager().getModules().stream().filter(module -> module.getCategory() == category).sorted(Comparator.comparing(Module::getName)).collect(Collectors.toList());
        }
        if (this.animationState != ModuleListPanel.AnimationState.NONE) {
            this.animProgress = LerpUtil.smoothLerp(this.animProgress, 1.0f, 0.18f);
            if (this.animProgress > 0.99f) {
                this.animProgress = 1.0f;
                if (this.animationState == ModuleListPanel.AnimationState.FADE_OUT) {
                    this.prevCategory = null;
                    this.prevModules = null;
                    if (this.currentCategory != null) {
                        this.animationState = ModuleListPanel.AnimationState.FADE_IN;
                        this.animProgress = 0.0f;
                    } else {
                        this.animationState = ModuleListPanel.AnimationState.NONE;
                    }
                } else {
                    this.animationState = ModuleListPanel.AnimationState.NONE;
                }
            }
        }
        try {
            int panelWidth = (int)(160.0f * scale);
            int marginX = (int)(20.0f * scale);
            int marginY = (int)(20.0f * scale);
            int baseSize = (int)(400.0f * scale);
            int panelX = originX + marginX - (int)(8.0f * scale);
            int panelY = originY + marginY + (int)(23.0f * scale);
            int panelHeight = baseSize - 2 * marginX - (int)(20.0f * scale);
            RenderUtil.drawRoundedRect(guiGraphics.pose(), panelX, panelY, panelWidth, panelHeight, 4.0f * scale, this.applyAlpha(HOVER_BG_COLOR, alpha));
            Renderer.render(guiGraphics, drawContext -> {
                Category renderCategory = this.currentCategory;
                List<Module> renderList = this.currentModules;
                float slideOffset = 0.0f;
                float renderAlpha = alpha;
                if (this.animationState == ModuleListPanel.AnimationState.FADE_OUT) {
                    renderCategory = this.prevCategory;
                    renderList = this.prevModules;
                    slideOffset = this.animProgress * 20.0f * scale;
                    renderAlpha = (1.0f - this.animProgress) * alpha;
                } else if (this.animationState == ModuleListPanel.AnimationState.FADE_IN) {
                    renderCategory = this.currentCategory;
                    renderList = this.currentModules;
                    slideOffset = (1.0f - this.animProgress) * 20.0f * scale;
                    renderAlpha = this.animProgress * alpha;
                }
                drawContext.save();
                drawContext.clip(Rectangle.ofXYWH(panelX, panelY, panelWidth, panelHeight));
                if (renderCategory != null) {
                    drawContext.save();
                    drawContext.translate(0.0f, slideOffset);
                    this.renderModuleList(renderCategory, renderList, panelX, panelY, panelHeight, mouseX, mouseY, renderAlpha, this.animationState != ModuleListPanel.AnimationState.NONE, scale);
                    drawContext.restore();
                }
                drawContext.restore();
            });
            if (this.animationState == ModuleListPanel.AnimationState.NONE) {
                this.renderScrollbar(guiGraphics, panelX, panelY, panelHeight, scale, alpha);
            }
        } catch (Exception exception) {
            // empty catch block
        }
    }

    private void renderSearchResults(GuiGraphics guiGraphics, int originX, int originY, int mouseX, int mouseY, float scale, float alpha) {
        try {
            int panelWidth = (int)(160.0f * scale);
            int marginX = (int)(20.0f * scale);
            int marginY = (int)(20.0f * scale);
            int baseSize = (int)(400.0f * scale);
            int panelX = originX + marginX - (int)(8.0f * scale);
            int panelY = originY + marginY + (int)(23.0f * scale);
            int panelHeight = baseSize - 2 * marginX - (int)(20.0f * scale);
            RenderUtil.drawRoundedRect(guiGraphics.pose(), panelX, panelY, panelWidth, panelHeight, 4.0f * scale, this.applyAlpha(HOVER_BG_COLOR, alpha));
            Renderer.render(guiGraphics, drawContext -> this.renderModuleList(null, this.searchResults, panelX, panelY, panelHeight, mouseX, mouseY, alpha, false, scale));
            this.renderScrollbar(guiGraphics, panelX, panelY, panelHeight, scale, alpha);
        } catch (Exception exception) {
            // empty catch block
        }
    }

    private void renderScrollbar(GuiGraphics guiGraphics, int panelX, int panelY, int panelHeight, float scale, float alpha) {
        float targetAlpha;
        float headerHeight = 30.0f * scale;
        float visibleHeight = (float)panelHeight - headerHeight;
        if (this.totalContentHeight <= visibleHeight) {
            targetAlpha = 0.0f;
        } else {
            long sinceScroll = System.currentTimeMillis() - this.lastScrollTime;
            if (this.isDraggingScrollbar || sinceScroll < 500L) {
                targetAlpha = 1.0f;
            } else if (sinceScroll < 1000L) {
                long fadeMs = sinceScroll - 500L;
                targetAlpha = 1.0f - (float)fadeMs / 500.0f;
            } else {
                targetAlpha = 0.0f;
            }
        }
        this.scrollbarAlpha = Math.abs(this.scrollbarAlpha - targetAlpha) > 0.01f ? LerpUtil.smoothLerp(this.scrollbarAlpha, targetAlpha, 0.35f) : targetAlpha;
        if (this.scrollbarAlpha <= 0.01f) {
            return;
        }
        float maxScroll = this.totalContentHeight - visibleHeight;
        if (maxScroll <= 0.0f) {
            return;
        }
        float thumbHeight = Math.max(20.0f * scale, visibleHeight / this.totalContentHeight * visibleHeight);
        float thumbY = (float)panelY + headerHeight + this.scrollOffset / maxScroll * (visibleHeight - thumbHeight);
        int thumbX = panelX + (int)(160.0f * scale) - (int)(4.0f * scale) - 2;
        float thumbWidth = 4.0f * scale;
        int thumbColor = new Color(1.0f, 1.0f, 1.0f, this.scrollbarAlpha * alpha).getRGB();
        RenderUtil.drawRoundedRect(guiGraphics.pose(), thumbX, thumbY, thumbWidth, thumbHeight, thumbWidth / 2.0f, thumbColor);
    }

    private void updateModuleHover(Module module, int rowX, int rowY, int mouseX, int mouseY, float scale) {
        if (module.isEnabled()) {
            this.hoverAnimations.put(module, 0.0f);
            return;
        }
        float current = this.hoverAnimations.getOrDefault(module, 0.0f).floatValue();
        boolean hovered = this.isMouseOverModule(module, rowX, rowY, mouseX, mouseY, scale);
        this.hoverAnimations.put(module, LerpUtil.lerp(current, hovered ? 1.0f : 0.0f, 0.12f));
    }

    private int applyAlpha(int color, float alpha) {
        int origAlpha = color >> 24 & 0xFF;
        int newAlpha = (int)((float)origAlpha * alpha);
        return newAlpha << 24 | color & 0xFFFFFF;
    }

    private void renderModuleList(Category category, List<Module> modules, int panelX, int panelY, int panelHeight, int mouseX, int mouseY, float alpha, boolean animating, float scale) {
        float headerHeight = 30.0f * scale;
        int panelWidth = (int)(160.0f * scale);
        FontRenderer titleFont = FontPresets.axiformaBold(20.0f * scale);
        String titleText = category == null ? "Search" : category.name().substring(0, 1).toUpperCase() + category.name().substring(1).toLowerCase();
        GlHelper.drawText(titleText, (float)panelX + 10.0f * scale, (float)panelY + 12.0f * scale, titleFont, this.applyAlpha(-1, alpha));
        FontRenderer subtitleFont = FontPresets.axiformaRegular(12.0f * scale);
        String subtitle = category == null ? modules.size() + " Results" : "Sorting: A-Z";
        float subtitleWidth = GlHelper.getStringWidth(subtitle, subtitleFont);
        float subtitleX = (float)(panelX + panelWidth) - subtitleWidth - 10.0f * scale;
        GlHelper.drawText(subtitle, subtitleX, (float)panelY + 14.0f * scale, subtitleFont, this.applyAlpha(-5592406, alpha));
        DrawContext drawContext = GlHelper.getCanvas();
        drawContext.save();
        drawContext.clip(Rectangle.ofXYWH(panelX, (float)panelY + headerHeight, panelWidth, (float)panelHeight - headerHeight));
        // Recaf used +2*scale here, but the deobfuscated GlyphMetrics flips the ascent sign so
        // GlHelper.drawText draws ~7*scale ABOVE drawY — at +2 the first row's text top slips out
        // of the clip. +8 leaves ~1*scale headroom under the clip top.
        int rowY = panelY + (int)headerHeight + (int)(8.0f * scale);
        if (modules != null) {
            this.totalContentHeight = modules.size() * Math.round(18.0f * scale);
            for (Module module : modules) {
                float drawY = (float)rowY - this.scrollOffset;
                if (!animating) {
                    this.updateModuleHover(module, panelX, (int)drawY, mouseX, mouseY, scale);
                }
                FontRenderer moduleFont = module.isEnabled() ? FontPresets.axiformaBold(16.0f * scale) : FontPresets.axiformaRegular(16.0f * scale);
                String moduleName = module.getName();
                if (this.searchResults != null) {
                    String categorySuffix = module.getCategory().name().substring(1).toLowerCase();
                    char categoryInitial = module.getCategory().name().charAt(0);
                    moduleName = moduleName + " (" + categoryInitial + categorySuffix + ")";
                }
                if (module.isEnabled()) {
                    int textColor = this.applyAlpha(-1, alpha);
                    int glowColor = this.applyAlpha(new Color(255, 255, 255, 150).getRGB(), alpha);
                    TextGlow.drawGlowText(moduleName, (float)panelX + 10.0f * scale, drawY, moduleFont, textColor, glowColor, 8.0f * scale);
                    String bindName = module.getBind().getName();
                    if (!bindName.equalsIgnoreCase("None")) {
                        FontRenderer iconFont = FontPresets.materialIcons(16.0f * scale);
                        String iconText = "\uE312";
                        FontRenderer bindFont = FontPresets.axiformaRegular(16.0f * scale);
                        float bindWidth = GlHelper.getStringWidth(bindName, bindFont);
                        float iconWidth = GlHelper.getStringWidth(iconText, iconFont);
                        float spacing = 2.0f * scale;
                        float totalWidth = bindWidth + iconWidth + spacing;
                        float bindX = (float)(panelX + panelWidth) - totalWidth - 10.0f * scale;
                        TextGlow.drawGlowText(iconText, bindX, drawY - (float)Math.round(scale), iconFont, textColor, glowColor, 8.0f * scale);
                        TextGlow.drawGlowText(bindName, bindX + iconWidth + spacing, drawY - 2.0f, moduleFont, textColor, glowColor, 8.0f * scale);
                    }
                } else {
                    float hoverAmount = this.hoverAnimations.getOrDefault(module, 0.0f).floatValue();
                    int rFrom = 170;
                    int gFrom = 170;
                    int bFrom = 170;
                    int rTo = 204;
                    int gTo = 204;
                    int bTo = 204;
                    int r = (int)((float)rFrom + (float)(rTo - rFrom) * hoverAmount);
                    int g = (int)((float)gFrom + (float)(gTo - gFrom) * hoverAmount);
                    int b = (int)((float)bFrom + (float)(bTo - bFrom) * hoverAmount);
                    int rgbColor = 0xFF000000 | r << 16 | g << 8 | b;
                    int textColor = this.applyAlpha(rgbColor, alpha);
                    GlHelper.drawText(moduleName, (float)panelX + 10.0f * scale, drawY, moduleFont, textColor);
                    String bindName = module.getBind().getName();
                    if (!bindName.equalsIgnoreCase("None")) {
                        int glowByte = (int)(alpha * 0.39215687f * hoverAmount);
                        int glowColor = this.applyAlpha(-3355444, (float)glowByte / 255.0f);
                        FontRenderer iconFont = FontPresets.materialIcons(16.0f * scale);
                        String iconText = "\uE312";
                        FontRenderer bindFont = FontPresets.axiformaRegular(16.0f * scale);
                        float bindWidth = GlHelper.getStringWidth(bindName, bindFont);
                        float iconWidth = GlHelper.getStringWidth(iconText, iconFont);
                        float spacing = 2.0f * scale;
                        float totalWidth = bindWidth + iconWidth + spacing;
                        float bindX = (float)(panelX + panelWidth) - totalWidth - 10.0f * scale;
                        TextGlow.drawGlowText(iconText, bindX, drawY - (float)Math.round(scale), iconFont, textColor, glowColor, 5.0f * scale);
                        TextGlow.drawGlowText(bindName, bindX + iconWidth + spacing, drawY - 2.0f, moduleFont, textColor, glowColor, 5.0f * scale);
                    }
                }
                rowY += Math.round(18.0f * scale);
            }
        } else {
            this.totalContentHeight = 0.0f;
        }
        drawContext.restore();
    }

    public boolean onMouseClick(int originX, int originY, int mouseX, int mouseY, Category category, int button, float scale) {
        List<Module> modules;
        List<Module> dup = modules = !this.searchQuery.isEmpty() ? this.searchResults : this.currentModules;
        if (modules == null || this.searchQuery.isEmpty() && this.animationState != ModuleListPanel.AnimationState.NONE) {
            return false;
        }
        int panelWidth = (int)(160.0f * scale);
        int marginX = (int)(20.0f * scale);
        int marginY = (int)(20.0f * scale);
        int baseSize = (int)(400.0f * scale);
        int headerHeight = (int)(30.0f * scale);
        int panelX = originX + marginX - (int)(8.0f * scale);
        int panelY = originY + marginY + (int)(23.0f * scale);
        int panelHeight = baseSize - 2 * marginX - (int)(20.0f * scale);
        float visibleHeight = panelHeight - headerHeight;
        if (this.totalContentHeight > visibleHeight) {
            float maxScroll = this.totalContentHeight - visibleHeight;
            float thumbHeight = Math.max(20.0f * scale, visibleHeight / this.totalContentHeight * visibleHeight);
            float thumbY = (float)(panelY + headerHeight) + this.scrollOffset / maxScroll * (visibleHeight - thumbHeight);
            float thumbWidth = 4.0f * scale;
            int thumbX = panelX + panelWidth - (int)thumbWidth - 2;
            if (mouseX >= thumbX && (float)mouseX <= (float)thumbX + thumbWidth && (float)mouseY >= thumbY && (float)mouseY <= thumbY + thumbHeight) {
                this.isDraggingScrollbar = true;
                this.scrollbarDragStartY = mouseY;
                this.scrollOffsetAtDragStart = this.scrollOffset;
                this.lastScrollTime = System.currentTimeMillis();
                return true;
            }
        }
        if (!this.isMouseOverPanel(originX, originY, mouseX, mouseY, scale)) {
            return false;
        }
        // Keep in sync with renderModuleList's rowY offset (+8*scale) so click hit-boxes match
        // the visible row positions.
        int rowY = panelY + headerHeight + Math.round(8.0f * scale);
        for (Module module : modules) {
            int adjustedRowY;
            if (this.isMouseOverModule(module, panelX, adjustedRowY = (int)((float)rowY - this.scrollOffset), mouseX, mouseY, scale)) {
                if (button == 0) {
                    module.toggle();
                    String stateLabel = module.isEnabled() ? "On" : "Off";
                    PanelClickGui.panelClickGui.addToast(module.getName() + " Module " + stateLabel);
                } else if (button == 1) {
                    this.hoveredModule = module;
                } else if (button == 2) {
                    PanelClickGui.panelClickGui.selectModule(module);
                }
                return true;
            }
            rowY += Math.round(18.0f * scale);
        }
        return false;
    }

    private boolean isMouseOverModule(Module module, int rowX, int rowY, int mouseX, int mouseY, float scale) {
        int rowHeight = Math.round(18.0f * scale);
        int panelWidth = (int)(160.0f * scale);
        boolean withinX = mouseX >= rowX && mouseX <= rowX + panelWidth;
        boolean withinY = mouseY >= rowY - rowHeight / 2 && mouseY <= rowY + rowHeight / 2;
        return withinX && withinY;
    }

    public void onScroll(double scrollDelta, float scale) {
        int contentHeight = (int)(400.0f * scale) - (int)(40.0f * scale) - (int)(20.0f * scale);
        float headerHeight = 30.0f * scale;
        float visibleHeight = (float)contentHeight - headerHeight;
        if (this.totalContentHeight > visibleHeight) {
            float maxScroll = this.totalContentHeight - visibleHeight;
            this.scrollTarget -= (float)scrollDelta * 18.0f * scale;
            this.scrollTarget = Math.max(0.0f, Math.min(this.scrollTarget, maxScroll));
            this.lastScrollTime = System.currentTimeMillis();
        }
    }

    public void onMouseDrag(double mouseX, double mouseY, float scale) {
        if (this.isDraggingScrollbar) {
            int contentHeight = (int)(400.0f * scale) - (int)(40.0f * scale) - (int)(20.0f * scale);
            float visibleHeight = (float)contentHeight - 30.0f * scale;
            float maxScroll = this.totalContentHeight - visibleHeight;
            float thumbHeight = Math.max(20.0f * scale, visibleHeight / this.totalContentHeight * visibleHeight);
            float trackHeight = visibleHeight - thumbHeight;
            if (trackHeight > 0.0f) {
                float dragDeltaY = (float)mouseY - this.scrollbarDragStartY;
                float scrollDelta = dragDeltaY / trackHeight * maxScroll;
                this.scrollOffset = this.scrollOffsetAtDragStart + scrollDelta;
                this.scrollTarget = this.scrollOffset = Math.max(0.0f, Math.min(this.scrollOffset, maxScroll));
            }
            this.lastScrollTime = System.currentTimeMillis();
        }
    }

    public void onMouseRelease() {
        this.isDraggingScrollbar = false;
        this.lastScrollTime = System.currentTimeMillis();
    }

    public boolean isMouseOverPanel(int originX, int originY, int mouseX, int mouseY, float scale) {
        int panelWidth = (int)(160.0f * scale);
        int marginX = (int)(20.0f * scale);
        int marginY = (int)(20.0f * scale);
        int baseSize = (int)(400.0f * scale);
        int headerHeight = (int)(30.0f * scale);
        int panelX = originX + marginX - (int)(8.0f * scale);
        int panelY = originY + marginY + (int)(23.0f * scale);
        int panelHeight = baseSize - 2 * marginX - (int)(20.0f * scale);
        return mouseX >= panelX && mouseX <= panelX + panelWidth && mouseY >= panelY + headerHeight && mouseY <= panelY + panelHeight;
    }

    public Module getHoveredModule() {
        return this.hoveredModule;
    }

    public void setHoveredModule(Module module) {
        this.hoveredModule = module;
    }

    private void rescaleScroll(float scale) {
        List<Module> modules;
        if (this.lastScale <= 0.0f) {
            return;
        }
        float scaleRatio = scale / this.lastScale;
        this.scrollOffset *= scaleRatio;
        this.scrollTarget *= scaleRatio;
        int contentHeight = (int)(400.0f * scale) - (int)(40.0f * scale) - (int)(20.0f * scale);
        float headerHeight = 30.0f * scale;
        float visibleHeight = (float)contentHeight - headerHeight;
        List<Module> dup = modules = !this.searchQuery.isEmpty() ? this.searchResults : this.currentModules;
        if (modules != null) {
            this.totalContentHeight = modules.size() * Math.round(18.0f * scale);
        }
        if (this.totalContentHeight > visibleHeight) {
            float maxScroll = this.totalContentHeight - visibleHeight;
            this.scrollOffset = Math.max(0.0f, Math.min(this.scrollOffset, maxScroll));
            this.scrollTarget = Math.max(0.0f, Math.min(this.scrollTarget, maxScroll));
        } else {
            this.scrollOffset = 0.0f;
            this.scrollTarget = 0.0f;
        }
    }
}
