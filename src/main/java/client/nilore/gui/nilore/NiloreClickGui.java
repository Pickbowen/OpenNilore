package client.nilore.gui.nilore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import client.nilore.NiloreClient;
import client.nilore.config.SavedConfigs;
import client.nilore.gui.nilore.NiloreLayout.Motion;
import client.nilore.gui.nilore.NiloreLayout.Rect;
import client.nilore.gui.nilore.NiloreLayout.Viewport;
import client.nilore.gui.nilore.NiloreRenderer.Chrome;
import client.nilore.gui.nilore.NiloreRenderer.Detail;
import client.nilore.gui.nilore.NiloreRenderer.Editor;
import client.nilore.gui.nilore.NiloreRenderer.Group;
import client.nilore.gui.nilore.NiloreRenderer.Kind;
import client.nilore.gui.nilore.NiloreRenderer.MenuOption;
import client.nilore.gui.nilore.NiloreRenderer.Nav;
import client.nilore.gui.nilore.NiloreRenderer.Note;
import client.nilore.gui.nilore.NiloreRenderer.PaneRow;
import client.nilore.gui.nilore.NiloreRenderer.Pill;
import client.nilore.gui.nilore.NiloreRenderer.Row;
import client.nilore.gui.nilore.NiloreTheme;
import client.nilore.gui.nilore.NiloreTheme.Palette;
import client.nilore.modules.Category;
import client.nilore.modules.KeyBind;
import client.nilore.modules.Module;
import client.nilore.modules.impl.render.ClickGuiModule;
import client.nilore.render.FontPresets;
import client.nilore.render.FontRenderer;
import client.nilore.render.Fonts;
import client.nilore.render.GlHelper;
import client.nilore.render.Renderer;
import client.nilore.settings.Setting;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.MultiSelectSetting;
import client.nilore.settings.impl.NumberSetting;

import static client.nilore.gui.nilore.NiloreLayout.*;

public final class NiloreClickGui extends Screen {
    public static final NiloreClickGui instance = new NiloreClickGui();

    private static final Category[] NAV_CATEGORIES = {Category.COMBAT, Category.MOVEMENT, Category.PLAYER,
            Category.RENDER, Category.EXPLOIT, Category.WORLD, Category.MISC};
    private static final int[] NAV_ICONS = {0, 2, 3, 4, 6, 5, 1};
    private static final String NAV_LABEL = "Categories";
    private static final Rect EMPTY = new Rect(0, 0, 0, 0);
    private static final float VALUE_BOX_W = 80;
    private static final float RESIZE_EDGE = 6, RESIZE_CORNER = 14;
    private static final float PANE_SETTINGS_W = 520, PANE_SETTINGS_H = 320;
    private static final float PANE_CONFIGS_W = 560, PANE_CONFIGS_H = 460;
    private static final String TEXT_SETTINGS = "Settings", TEXT_CONFIGS = "Configs", TEXT_MODULES = "Modules";

    private enum Id { MODULES, SETTINGS, CONFIGS }

    private final List<Win> order = new ArrayList<>();
    private final Map<Object, Motion> toggles = new HashMap<>();
    private final Map<Object, Motion> controls = new HashMap<>();
    private final Motion listMotion = new Motion(0, 28);
    private final Motion detailMotion = new Motion(0, 28);
    private final Motion selectionMotion = new Motion(navItem(0).y(), 30);
    private final Motion windowMotion = new Motion(0, 22);

    private Field search;
    private final Field configName = new Field(32);
    private Viewport viewport;
    private float scale = 1;
    private float openingScale = 1;
    private List<Module> visible = List.of();
    private List<Note> notes = List.of();
    private Detail detail;
    private final List<RowRef> rowRefs = new ArrayList<>();
    private final List<Rect> navRects = new ArrayList<>();
    private final List<Rect> footerRects = new ArrayList<>();
    private final List<PaneRow> paneRows = new ArrayList<>();
    private final List<String> configNames = new ArrayList<>();
    private Rect docToggle = EMPTY;
    private MenuState menu;
    private Module selected, binding;
    private NumberSetting editing;
    private RowRef draggingSlider;
    private Win dragging, resizing;
    private int resizeMode;
    private float dragGrabX, dragGrabY, resizeBaseW, resizeBaseH;
    private int navigation;
    private float mouseX, mouseY, opacity, screenX, screenY;
    private float listScroll, listScrollTarget, listMax, detailScroll, detailScrollTarget, detailMax;
    private float windowGrabX, windowGrabY, scrollGrab;
    private int draggingScroll, hoverDot = -1, lastClickWin = -1;
    private double lastClickTime, lastClickY;
    private boolean closing, detached = true, configsLoaded;
    private String editText = "";
    private boolean editReplace = true;

    private NiloreClickGui() {
        super(Component.literal("Nilore"));
    }

    private static double now() {
        return System.nanoTime() / 1_000_000_000.0;
    }

    private float rasterScale() {
        return Fonts.deviceScale(scale);
    }

    @Override
    protected void init() {
        if (search == null) {
            search = new Field(48);
        }
        scale = NiloreLayout.scale(width, height);
        if (order.isEmpty()) {
            Win main = new Win(Id.MODULES, TEXT_MODULES, 3, NATURAL_W, NATURAL_H);
            main.x = (width - main.w * scale) / 2f;
            main.y = Math.max(2, (height - main.h * scale) / 2f);
            main.open.snap(1, now());
            order.add(main);
        }
        clampWindows();
        useLayout(focusedOrMain());
        if (detached) {
            double time = now();
            windowMotion.snap(0, time);
            windowMotion.to(1, time);
            closing = false;
            detached = false;
            menu = null;
            binding = null;
            releaseDrag();
            search.setFocused(false);
        }
        refresh(now());
    }

    // -- windows ------------------------------------------------------------------

    private final class Win {
        final Id id;
        final String title;
        final int icon;
        final Motion open = new Motion(0, NiloreTheme.Motion.NORMAL_RATE);
        float x, y, w, h;
        float restoreX, restoreY, restoreW, restoreH;
        boolean maximized;

        Win(Id id, String title, int icon, float w, float h) {
            this.id = id;
            this.title = title;
            this.icon = icon;
            this.w = w;
            this.h = h;
        }

        Rect screen() {
            return new Rect(x, y, w * scale, h * scale);
        }
    }

    private Win modules() {
        for (Win win : order) {
            if (win.id == Id.MODULES) {
                return win;
            }
        }
        return null;
    }

    private Win focused() {
        return order.isEmpty() ? null : order.get(order.size() - 1);
    }

    private Win focusedOrMain() {
        Win win = focused();
        return win == null ? modules() : win;
    }

    private Win find(Id id) {
        for (Win win : order) {
            if (win.id == id) {
                return win;
            }
        }
        return null;
    }

    private void useLayout(Win win) {
        if (win == null) {
            return;
        }
        NiloreLayout.resize(win.w, win.h);
        viewport = new Viewport(win.x, win.y, scale);
    }

    private void focus(Win win) {
        if (win == null) {
            return;
        }
        order.remove(win);
        order.add(win);
        win.open.to(1, now());
    }

    private Win openWindow(Id id) {
        Win win = find(id);
        if (win != null) {
            focus(win);
            return win;
        }
        boolean settings = id == Id.SETTINGS;
        win = new Win(id, settings ? TEXT_SETTINGS : TEXT_CONFIGS, settings ? 5 : 7,
                settings ? PANE_SETTINGS_W : PANE_CONFIGS_W, settings ? PANE_SETTINGS_H : PANE_CONFIGS_H);
        Win last = focusedOrMain();
        win.x = last == null ? 40 : last.x + 28;
        win.y = last == null ? 40 : last.y + 28;
        win.open.snap(0, now());
        order.add(win);
        clampWindows();
        win.open.to(1, now());
        if (id == Id.CONFIGS) {
            configsLoaded = false;
        }
        refresh(now());
        return win;
    }

    private void closeWindow(Win win) {
        if (win == null) {
            return;
        }
        if (win.id == Id.MODULES) {
            onClose();
            return;
        }
        order.remove(win);
        if (win.id == Id.CONFIGS) {
            configName.setFocused(false);
        }
    }

    private void clampWindows() {
        for (Win win : order) {
            float sw = win.w * scale, sh = win.h * scale;
            if (win.x + sw > width - 2) {
                win.x = Math.max(2, width - sw - 2);
            }
            if (win.y + sh > height - 2) {
                win.y = Math.max(2, height - sh - 2);
            }
            win.x = Math.max(2, win.x);
            win.y = Math.max(2, win.y);
        }
    }

    private Win windowAt(double x, double y) {
        for (int i = order.size() - 1; i >= 0; i--) {
            Win win = order.get(i);
            Rect bounds = win.screen();
            if (x >= bounds.x() && x < bounds.right() && y >= bounds.y() && y < bounds.bottom()) {
                return win;
            }
        }
        return null;
    }

    private Rect chromeRect(Win win) {
        return new Rect(win.x, win.y, win.w * scale, TITLEBAR_H * scale);
    }

    private int resizeHandle(Win win, double x, double y) {
        Rect bounds = win.screen();
        boolean nearRight = x >= bounds.right() - RESIZE_EDGE && x < bounds.right() + 2;
        boolean nearBottom = y >= bounds.bottom() - RESIZE_EDGE && y < bounds.bottom() + 2;
        if (x >= bounds.right() - RESIZE_CORNER && x < bounds.right() + 2 && y >= bounds.bottom() - RESIZE_CORNER
                && y < bounds.bottom() + 2) {
            return 3;
        }
        if (nearRight && y > bounds.y() && y < bounds.bottom()) {
            return 1;
        }
        if (nearBottom && x > bounds.x() && x < bounds.right()) {
            return 2;
        }
        return 0;
    }

    // -- data ---------------------------------------------------------------------

    private boolean dark() {
        ClickGuiModule module = NiloreClient.getInstance().getModuleManager().getModule(ClickGuiModule.class);
        return module != null && module.niloreTheme.is("Dark");
    }

    private static boolean shown(Setting<?> setting) {
        return setting.getVisibility() == null || setting.getVisibility().displayable();
    }

    private static List<Setting<?>> visibleSettings(Module module) {
        List<Setting<?>> result = new ArrayList<>();
        for (Setting<?> setting : module.getSettings()) {
            if (shown(setting) && (setting instanceof BooleanSetting || setting instanceof NumberSetting
                    || setting instanceof ModeSetting || setting instanceof MultiSelectSetting)) {
                result.add(setting);
            }
        }
        return result;
    }

    private List<Module> visibleModules() {
        String query = search.getValue().strip().toLowerCase(Locale.ROOT);
        List<Module> all = NiloreClient.getInstance().getModuleManager().getModules();
        if (query.isEmpty()) {
            List<Module> result = new ArrayList<>();
            for (Module module : all) {
                if (!module.isHiddenInModuleList() && module.getCategory() == NAV_CATEGORIES[navigation]) {
                    result.add(module);
                }
            }
            result.sort(Comparator.comparing(Module::getName, String.CASE_INSENSITIVE_ORDER));
            return result;
        }
        List<Module> result = new ArrayList<>();
        for (Module module : all) {
            if (!module.isHiddenInModuleList() && matchesName(module.getName(), query.replace(" ", ""))) {
                result.add(module);
            }
        }
        result.sort(Comparator.comparing(Module::getName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /**
     * Matches the start of the module name or of any of its words, so "ki" does not hit "Block In".
     */
    private static boolean matchesName(String name, String query) {
        String spaced = spaced(name).toLowerCase(Locale.ROOT);
        if (spaced.replace(" ", "").startsWith(query)) {
            return true;
        }
        for (String word : spaced.split("\\s+")) {
            if (word.startsWith(query)) {
                return true;
            }
        }
        return false;
    }

    private static String spaced(String name) {
        return name.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ");
    }

    private static String number(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private String keyLabel(Module module) {
        if (module == binding) {
            return "Press...";
        }
        String label = module.getBind() == null ? null : module.getBind().getName();
        return label == null || "None".equalsIgnoreCase(label) ? "None" : label;
    }

    private String subtitle(Module module) {
        List<Setting<?>> values = visibleSettings(module);
        if (values.isEmpty()) {
            return "No settings";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size() && i < 3; i++) {
            if (i > 0) {
                builder.append(" / ");
            }
            builder.append(values.get(i).getName());
        }
        return values.size() > 3 ? builder.append(" / ...").toString() : builder.toString();
    }

    // -- layout -------------------------------------------------------------------

    private void refresh(double time) {
        Win main = modules();
        if (main != null) {
            useLayout(main);
        }
        List<Module> modules = visibleModules();
        if (selected == null || !modules.contains(selected)) {
            selected = modules.isEmpty() ? null : modules.get(0);
            detailScroll = detailScrollTarget = 0;
            detailMotion.snap(0, time);
        }
        visible = modules;

        List<Note> rows = new ArrayList<>(modules.size());
        for (int i = 0; i < modules.size(); i++) {
            Module module = modules.get(i);
            Rect bounds = noteRow(i, listScroll);
            rows.add(new Note(bounds, module.getName(), subtitle(module), module.isEnabled(), module == selected,
                    bounds.intersect(LIST_VIEWPORT).contains(mouseX, mouseY),
                    toggleMotion(module, module.isEnabled() ? 1 : 0, time)));
        }
        notes = List.copyOf(rows);
        listMax = Math.max(0, LIST_PAD_TOP + GROUP_TITLE_H + modules.size() * NOTE_H + LIST_PAD_BOTTOM
                - LIST_VIEWPORT.height());
        listScrollTarget = clamp(listScrollTarget, 0, listMax);
        if (listScroll > listMax) {
            listScroll = listMax;
            listMotion.snap(listScroll, time);
        }

        navRects.clear();
        for (int i = 0; i < NAV_CATEGORIES.length; i++) {
            navRects.add(navItem(i));
        }
        footerRects.clear();
        footerRects.add(footerItem(0));
        footerRects.add(footerItem(1));

        rowRefs.clear();
        docToggle = EMPTY;
        detail = selected == null ? null : buildDetail(selected, time);
        if (selected == null) {
            detailMax = 0;
        }
        detailScrollTarget = clamp(detailScrollTarget, 0, detailMax);
        if (detailScroll > detailMax) {
            detailScroll = detailMax;
            detailMotion.snap(detailScroll, time);
        }
        updateMenu(time);
    }

    private Detail buildDetail(Module module, double time) {
        float contentX = rowContentX();
        float contentRight = GROUP_X + GROUP_W - ROW_PAD_X;
        float y = DETAIL_PAD_TOP - detailScroll;
        Rect head = new Rect(DETAIL_X, y, DETAIL_W, DOC_H);
        docToggle = new Rect(DETAIL_X + DETAIL_W - DOC_PAD_X - SWITCH_W, y + (DOC_H - SWITCH_H) / 2f, SWITCH_W,
                SWITCH_H);
        y += DOC_H;

        List<Row> rows = new ArrayList<>();
        Rect bindBounds = new Rect(GROUP_X, y, GROUP_W, ROW_SELECT_H);
        String label = keyLabel(module);
        // .keybind { min-width: 82px; padding: 0 10px }
        float bindWidth = Math.max(82, 20 + GlHelper.getStringWidth(label, FontPresets.poppinsRegular(
                SZ_BODY, rasterScale())));
        Rect bindControl = new Rect(contentRight - bindWidth, y + (ROW_SELECT_H - 30) / 2f, bindWidth, 30);
        rows.add(new Row(Kind.KEYBIND, bindBounds, bindControl, EMPTY, "Bind", label, 0,
                bindBounds.contains(mouseX, mouseY), module == binding, List.of()));
        rowRefs.add(new RowRef(bindBounds, bindControl, EMPTY, Kind.KEYBIND, module, null, List.of()));
        y += ROW_SELECT_H;

        for (Setting<?> setting : visibleSettings(module)) {
            boolean hovered = false;
            if (setting instanceof BooleanSetting value) {
                Rect bounds = new Rect(GROUP_X, y, GROUP_W, ROW_SWITCH_H);
                hovered = bounds.contains(mouseX, mouseY);
                Rect control = new Rect(contentRight - SWITCH_W, y + (ROW_SWITCH_H - SWITCH_H) / 2f, SWITCH_W,
                        SWITCH_H);
                rows.add(new Row(Kind.SWITCH, bounds, control, EMPTY, setting.getName(), "", toggleMotion(setting,
                        value.getValue() ? 1 : 0, time), hovered, false, List.of()));
                rowRefs.add(new RowRef(bounds, control, EMPTY, Kind.SWITCH, module, setting, List.of()));
                y += ROW_SWITCH_H;
            } else if (setting instanceof NumberSetting value) {
                Rect bounds = new Rect(GROUP_X, y, GROUP_W, ROW_SLIDER_H);
                hovered = bounds.contains(mouseX, mouseY);
                Rect control = new Rect(contentX, y + COL_PAD_TOP + ROW_LABEL_H + COL_BLOCK_GAP, rowContentW(),
                        SLIDER_H);
                Rect valueBox = new Rect(contentRight - VALUE_BOX_W, y + COL_PAD_TOP, VALUE_BOX_W, ROW_LABEL_H);
                boolean active = editing == value;
                String display = active ? editText : number(value.getValue().doubleValue());
                rows.add(new Row(Kind.SLIDER, bounds, control, valueBox, setting.getName(), display,
                        progressOf(value, time), hovered, active, List.of()));
                rowRefs.add(new RowRef(bounds, control, valueBox, Kind.SLIDER, module, setting, List.of()));
                y += ROW_SLIDER_H;
            } else if (setting instanceof ModeSetting value) {
                Rect bounds = new Rect(GROUP_X, y, GROUP_W, ROW_SELECT_H);
                hovered = bounds.contains(mouseX, mouseY);
                float width = clamp(SELECT_PAD_LEFT + 19 + GlHelper.getStringWidth(value.getValue(),
                        FontPresets.poppinsRegular(SZ_BODY, rasterScale())), SELECT_MIN_W, SELECT_MAX_W);
                Rect control = new Rect(contentRight - width, y + (ROW_SELECT_H - SELECT_H) / 2f, width, SELECT_H);
                rows.add(new Row(Kind.SELECT, bounds, control, EMPTY, setting.getName(), value.getValue(), 0,
                        hovered, menu != null && menu.setting == value && menu.open, List.of()));
                rowRefs.add(new RowRef(bounds, control, EMPTY, Kind.SELECT, module, setting, List.of()));
                y += ROW_SELECT_H;
            } else if (setting instanceof MultiSelectSetting value) {
                Rect bounds = new Rect(GROUP_X, y, GROUP_W, ROW_SEGMENTED_H);
                hovered = bounds.contains(mouseX, mouseY);
                Rect control = new Rect(contentX, y + COL_PAD_TOP + ROW_LABEL_H + COL_BLOCK_GAP, rowContentW(),
                        SEGMENTED_H);
                List<Pill> pills = pills(value, control, time);
                rows.add(new Row(Kind.SEGMENTED, bounds, control, EMPTY, setting.getName(), "", 0, hovered, false,
                        pills));
                rowRefs.add(new RowRef(bounds, control, EMPTY, Kind.SEGMENTED, module, setting, pills));
                y += ROW_SEGMENTED_H;
            }
        }

        float body = y - (DETAIL_PAD_TOP - detailScroll) - DOC_H;
        Rect group = new Rect(GROUP_X, y - body, GROUP_W, body);
        detailMax = Math.max(0, DOC_H + body + GROUP_MARGIN_BOTTOM - (HEIGHT - DETAIL_PAD_TOP));
        return new Detail(head, docToggle, module.getName(), subtitle(module),
                toggleMotion(module, module.isEnabled() ? 1 : 0, time),
                List.of(new Group(group, "", List.copyOf(rows))));
    }

    private List<Pill> pills(MultiSelectSetting setting, Rect control, double time) {
        List<String> options = setting.getOptions();
        int count = options.size();
        float inner = control.width() - SEGMENT_PAD * 2;
        float width = (inner - SEGMENT_GAP * (count - 1)) / count;
        List<Pill> pills = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String option = options.get(i);
            Rect bounds = new Rect(control.x() + SEGMENT_PAD + i * (width + SEGMENT_GAP), control.y() + SEGMENT_PAD,
                    width, SEGMENTED_H - SEGMENT_PAD * 2);
            pills.add(new Pill(bounds, option, setting.isSelected(option)));
        }
        return List.copyOf(pills);
    }

    private float toggleMotion(Object key, float target, double time) {
        return clamp(toggles.computeIfAbsent(key, ignored -> new Motion(target, NiloreTheme.Motion.NORMAL_RATE))
                .to(target, time), 0, 1);
    }

    private float progressOf(NumberSetting setting, double time) {
        float fraction = fractionOf(setting);
        return clamp(controls.computeIfAbsent(setting, ignored -> new Motion(fraction, NiloreTheme.Motion.SLOW_RATE))
                .to(fraction, time), 0, 1);
    }

    private static float fractionOf(NumberSetting setting) {
        double min = setting.getMin().doubleValue(), max = setting.getMax().doubleValue();
        if (max <= min) {
            return 0;
        }
        return clamp((float) ((setting.getValue().doubleValue() - min) / (max - min)), 0, 1);
    }

    // -- panes --------------------------------------------------------------------

    private void buildPane(Win win) {
        paneRows.clear();
        if (win == null || win.id == Id.MODULES) {
            return;
        }
        Viewport pane = new Viewport(win.x, win.y, scale);
        float mx = pane.localX(screenX, openingScale);
        float my = pane.localY(screenY, openingScale);
        if (win.id == Id.SETTINGS) {
            buildSettingsPane(mx, my);
        } else {
            buildConfigsPane(mx, my);
        }
    }

    private void buildSettingsPane(float mx, float my) {
        ClickGuiModule module = NiloreClient.getInstance().getModuleManager().getModule(ClickGuiModule.class);
        if (module == null) {
            return;
        }
        float x = 16, width = WIDTH - 32;
        float y = CONTENT_TOP + 18;
        List<Setting<?>> settings = new ArrayList<>();
        settings.add(module.styleSetting);
        settings.add(module.niloreTheme);
        for (Setting<?> setting : settings) {
            if (!(setting instanceof ModeSetting value)) {
                continue;
            }
            Rect bounds = new Rect(x, y, width, ROW_SEGMENTED_H);
            Rect control = new Rect(x, y + COL_PAD_TOP + ROW_LABEL_H, width, SEGMENTED_H);
            List<Pill> pills = modePills(value, control);
            paneRows.add(new PaneRow(Kind.SEGMENTED, bounds, control, setting.getName(), value.getValue(), 0,
                    false, false, false, pills));
            y += ROW_SEGMENTED_H + 4;
        }
    }

    private List<Pill> modePills(ModeSetting setting, Rect control) {
        String[] modes = setting.getModes();
        float width = (control.width() - SEGMENT_PAD * 2 - SEGMENT_GAP * (modes.length - 1)) / modes.length;
        List<Pill> pills = new ArrayList<>(modes.length);
        for (int i = 0; i < modes.length; i++) {
            Rect bounds = new Rect(control.x() + SEGMENT_PAD + i * (width + SEGMENT_GAP), control.y() + SEGMENT_PAD,
                    width, SEGMENTED_H - SEGMENT_PAD * 2);
            pills.add(new Pill(bounds, modes[i], modes[i].equals(setting.getValue())));
        }
        return List.copyOf(pills);
    }

    private void buildConfigsPane(float mx, float my) {
        float x = 16, width = WIDTH - 32;
        float y = CONTENT_TOP + 18;
        Rect input = new Rect(x, y, width - 96, 34);
        paneRows.add(new PaneRow(Kind.INPUT, input, input, "Config name", configName.getValue(), 0,
                input.contains(mx, my), false, configName.focused, List.of()));
        Rect save = new Rect(x + width - 86, y, 86, 34);
        paneRows.add(new PaneRow(Kind.BUTTON, save, save, "Save", "", 0, save.contains(mx, my), false,
                false, List.of()));
        y += 34 + 18;
        if (!configsLoaded) {
            configNames.clear();
            configNames.addAll(SavedConfigs.list());
            configsLoaded = true;
        }
        for (String name : configNames) {
            Rect row = new Rect(x, y, width, 40);
            paneRows.add(new PaneRow(Kind.LIST, row, row, name, "Load", 0, row.contains(mx, my), false,
                    false, List.of()));
            y += 40;
        }
        if (configNames.isEmpty()) {
            Rect row = new Rect(x, y, width, 40);
            paneRows.add(new PaneRow(Kind.LIST, row, row, "No saved configs", "", 0, false, false, false, List.of()));
        }
    }

    // -- render -------------------------------------------------------------------

    @Override
    public void render(GuiGraphics gg, int mx, int my, float partialTick) {
        double time = now();
        scale = NiloreLayout.scale(width, height);
        opacity = clamp(windowMotion.to(closing ? 0 : 1, time), 0, 1);
        openingScale = .975f + opacity * .025f;
        screenX = mx;
        screenY = my;
        if (closing && opacity < .01f) {
            minecraft.setScreen(null);
            return;
        }
        Win top = focused();
        useLayout(modules());
        setMouse(mx, my);
        listScroll = listMotion.to(listScrollTarget, time);
        detailScroll = detailMotion.to(detailScrollTarget, time);
        refresh(time);
        hoverDot = trafficHover(time);
        boolean dark = dark();
        Palette palette = NiloreTheme.of(dark);

        Renderer.renderConsumer(dc -> {
            for (Win win : order) {
                useLayout(win);
                float reveal = win.open.to(1, time);
                float windowScale = .96f + .04f * reveal;
                Viewport pane = new Viewport(win.x, win.y, scale);
                boolean isTop = win == top;
                Chrome chrome = new Chrome(win.title, isTop, win.maximized, isTop ? hoverDot : -1);
                if (win.id == Id.MODULES) {
                    List<Nav> nav = new ArrayList<>(NAV_CATEGORIES.length);
                    for (int i = 0; i < NAV_CATEGORIES.length; i++) {
                        Category category = NAV_CATEGORIES[i];
                        nav.add(new Nav(category.displayName, categoryCount(category), NAV_ICONS[i], i == navigation,
                                navRects.get(i).contains(mouseX, mouseY)));
                    }
                    List<Nav> footer = List.of(
                            new Nav(TEXT_SETTINGS, -1, 5, false, footerRects.get(0).contains(mouseX, mouseY)),
                            new Nav(TEXT_CONFIGS, -1, 7, false, footerRects.get(1).contains(mouseX, mouseY)));
                    NiloreRenderer.Frame frame = new NiloreRenderer.Frame(pane, opacity * reveal, windowScale, chrome,
                            selectionMotion.to(navItem(navigation).y(), time), palette, dark, NAV_LABEL, nav, footer,
                            new Editor(search.value, search.focused), listTitle(), notes, listScroll, listMax, detail,
                            detailScroll, detailMax, menuFrame(time), mouseX, mouseY);
                    NiloreRenderer.paintModules(dc, gg, frame);
                } else {
                    buildPane(win);
                    float localX = pane.localX(screenX, openingScale), localY = pane.localY(screenY, openingScale);
                    NiloreRenderer.Pane view = new NiloreRenderer.Pane(pane, opacity * reveal, windowScale, chrome,
                            palette, dark, List.copyOf(paneRows), localX, localY);
                    NiloreRenderer.paintPane(dc, gg, view);
                }
            }
        });
    }

    private int trafficHover(double time) {
        Win top = focused();
        if (top == null) {
            return -1;
        }
        Rect chrome = chromeRect(top);
        if (!chrome.contains(screenX, screenY)) {
            return -1;
        }
        float localX = (screenX - top.x) / scale;
        for (int i = 0; i < 3; i++) {
            Rect dot = trafficLight(i);
            if (localX >= dot.x() - 5 && localX < dot.right() + 5) {
                return i;
            }
        }
        return -1;
    }

    private String listTitle() {
        return search.getValue().strip().isEmpty() ? NAV_CATEGORIES[navigation].displayName : "Results";
    }

    private int categoryCount(Category category) {
        int count = 0;
        for (Module module : NiloreClient.getInstance().getModuleManager().getModules()) {
            if (!module.isHiddenInModuleList() && module.getCategory() == category) {
                count++;
            }
        }
        return count;
    }

    private void setMouse(double x, double y) {
        if (viewport == null) {
            return;
        }
        mouseX = viewport.localX(x, openingScale);
        mouseY = viewport.localY(y, openingScale);
    }

    // -- input --------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (closing) {
            return true;
        }
        screenX = (float) x;
        screenY = (float) y;
        scale = NiloreLayout.scale(width, height);
        useLayout(modules());
        setMouse(x, y);
        if (button == 0 && commitEditing()) {
            return true;
        }
        refresh(now());
        Win win = windowAt(x, y);
        if (win == null) {
            if (button == 0) {
                onClose();
            }
            return true;
        }
        focus(win);
        useLayout(win);
        setMouse(x, y);
        refresh(now());

        int handle = resizeHandle(win, x, y);
        if (button == 0 && handle != 0) {
            resizing = win;
            resizeMode = handle;
            resizeBaseW = win.w;
            resizeBaseH = win.h;
            dragGrabX = (float) x;
            dragGrabY = (float) y;
            return true;
        }
        if (chromeRect(win).contains(x, y)) {
            if (button != 0) {
                return true;
            }
            int dot = trafficHoverAt(win, x);
            if (dot == 0 || dot == 1) {
                closeWindow(win);
                return true;
            }
            if (dot == 2) {
                toggleMaximize(win);
                return true;
            }
            double time = now();
            if (lastClickWin == win.id.ordinal() && time - lastClickTime < .35
                    && Math.abs(y - lastClickY) < 6) {
                toggleMaximize(win);
                lastClickWin = -1;
                return true;
            }
            lastClickWin = win.id.ordinal();
            lastClickTime = time;
            lastClickY = (float) y;
            dragging = win;
            windowGrabX = (float) x - win.x;
            windowGrabY = (float) y - win.y;
            return true;
        }
        if (win.id == Id.MODULES) {
            useLayout(win);
            setMouse(x, y);
            return moduleClick(button);
        }
        useLayout(win);
        buildPane(win);
        return paneClick(win, button);
    }

    private int trafficHoverAt(Win win, double x) {
        float localX = (float) ((x - win.x) / scale);
        for (int i = 0; i < 3; i++) {
            Rect dot = trafficLight(i);
            if (localX >= dot.x() - 5 && localX < dot.right() + 5) {
                return i;
            }
        }
        return -1;
    }

    private void toggleMaximize(Win win) {
        if (win.maximized) {
            win.x = win.restoreX;
            win.y = win.restoreY;
            win.w = win.restoreW;
            win.h = win.restoreH;
            win.maximized = false;
        } else {
            win.restoreX = win.x;
            win.restoreY = win.y;
            win.restoreW = win.w;
            win.restoreH = win.h;
            win.x = 2;
            win.y = 2;
            win.w = Math.max(MIN_W, (width - 4) / scale);
            win.h = Math.max(MIN_H, (height - 4) / scale);
            win.maximized = true;
        }
        clampWindows();
    }

    private boolean moduleClick(int button) {
        if (menu != null) {
            if (menuClick()) {
                return true;
            }
            boolean onAnchor = menu.anchor.contains(this.mouseX, this.mouseY);
            menu.open = false;
            if (onAnchor) {
                refresh(now());
                return true;
            }
        }
        if (binding != null) {
            if (button >= 0 && button <= 7) {
                binding.setKey(KeyBind.mouseCode(button));
                binding = null;
                refresh(now());
            }
            return true;
        }
        Rect box = searchBox();
        if (button == 0 && box.contains(this.mouseX, this.mouseY)) {
            search.setFocused(true);
            search.moveCursorTo(cursorAt(box), false);
            refresh(now());
            return true;
        }
        search.setFocused(false);
        for (int i = 0; i < navRects.size(); i++) {
            if (navRects.get(i).contains(this.mouseX, this.mouseY)) {
                if (button == 0) {
                    selectPage(i);
                }
                return true;
            }
        }
        for (int i = 0; i < footerRects.size(); i++) {
            if (footerRects.get(i).contains(this.mouseX, this.mouseY)) {
                if (button == 0) {
                    openWindow(i == 0 ? Id.SETTINGS : Id.CONFIGS);
                    refresh(now());
                }
                return true;
            }
        }
        if (button == 0 && chooseScrollbar()) {
            return true;
        }
        if (docToggle.contains(this.mouseX, this.mouseY)) {
            if (button == 0 && selected != null) {
                selected.toggle();
                refresh(now());
            }
            return true;
        }
        for (int i = rowRefs.size() - 1; i >= 0; i--) {
            RowRef ref = rowRefs.get(i);
            if (!ref.control.contains(this.mouseX, this.mouseY)
                    && !ref.valueBox.contains(this.mouseX, this.mouseY)) {
                continue;
            }
            switch (ref.kind) {
                case SWITCH -> {
                    if (button == 0 && ref.setting instanceof BooleanSetting value) {
                        value.setValue(!value.getValue());
                    }
                }
                case KEYBIND -> {
                    if (button == 0) {
                        binding = ref.module;
                    }
                }
                case SELECT -> {
                    if (button == 0 && ref.setting instanceof ModeSetting value) {
                        openMenu(value, ref.control);
                    }
                }
                case SLIDER -> {
                    if (button == 0 && ref.setting instanceof NumberSetting value) {
                        if (ref.valueBox.contains(this.mouseX, this.mouseY)) {
                            startEditing(value);
                        } else {
                            draggingSlider = ref;
                            updateSlider();
                        }
                    }
                }
                case SEGMENTED -> {
                    if (button == 0 && ref.setting instanceof MultiSelectSetting value) {
                        toggleOption(value, ref, i);
                    }
                }
                default -> {
                }
            }
            refresh(now());
            return true;
        }
        for (int i = 0; i < notes.size(); i++) {
            Note note = notes.get(i);
            if (!note.bounds().contains(this.mouseX, this.mouseY) || !LIST_VIEWPORT.contains(this.mouseX, this.mouseY)) {
                continue;
            }
            Module module = visible.get(i);
            if (button == 0 && rowSwitch(note.bounds()).contains(this.mouseX, this.mouseY)) {
                module.toggle();
                refresh(now());
                return true;
            }
            if (button == 0 && module != selected) {
                selected = module;
                detailScroll = detailScrollTarget = 0;
                detailMotion.snap(0, now());
                refresh(now());
            }
            return true;
        }
        return true;
    }

    private boolean paneClick(Win win, int button) {
        if (button != 0) {
            return true;
        }
        Viewport pane = new Viewport(win.x, win.y, scale);
        float mx = pane.localX(screenX, openingScale);
        float my = pane.localY(screenY, openingScale);
        if (win.id == Id.SETTINGS) {
            for (PaneRow row : paneRows) {
                if (row.kind() != Kind.SEGMENTED) {
                    continue;
                }
                for (Pill pill : row.pills()) {
                    if (pill.bounds().contains(mx, my)) {
                        applyMode(row.label(), pill.label());
                        return true;
                    }
                }
            }
            return true;
        }
        for (PaneRow row : paneRows) {
            if (row.kind() == Kind.INPUT && row.bounds().contains(mx, my)) {
                configName.setFocused(true);
                return true;
            }
            if (row.kind() == Kind.BUTTON && row.bounds().contains(mx, my)) {
                saveConfig();
                return true;
            }
            if (row.kind() == Kind.LIST && row.bounds().contains(mx, my)) {
                if (!configNames.isEmpty()) {
                    SavedConfigs.load(row.label());
                    refresh(now());
                }
                return true;
            }
        }
        configName.setFocused(false);
        return true;
    }

    private void applyMode(String label, String value) {
        ClickGuiModule module = NiloreClient.getInstance().getModuleManager().getModule(ClickGuiModule.class);
        if (module == null) {
            return;
        }
        if ("Theme".equals(label)) {
            module.niloreTheme.setValue(value);
        } else if ("Mode".equals(label)) {
            module.styleSetting.setValue(value);
        }
        refresh(now());
    }

    private void saveConfig() {
        String name = configName.getValue().strip();
        if (name.isEmpty() || !SavedConfigs.isValidName(name)) {
            return;
        }
        SavedConfigs.save(name);
        configName.setValue("");
        configName.setFocused(false);
        configsLoaded = false;
        refresh(now());
    }

    private boolean chooseScrollbar() {
        if (listMax > 0 && scrollbarContains(LIST_VIEWPORT, listScroll, listMax)) {
            draggingScroll = 1;
        } else if (detailMax > 0 && scrollbarContains(DETAIL_VIEWPORT, detailScroll, detailMax)) {
            draggingScroll = 2;
        } else {
            return false;
        }
        updateScrollbar();
        return true;
    }

    private boolean scrollbarContains(Rect viewport, float scroll, float maximum) {
        Rect gutter = new Rect(viewport.right() - SCROLL_GUTTER, viewport.y(), SCROLL_GUTTER, viewport.height());
        if (!gutter.contains(mouseX, mouseY)) {
            return false;
        }
        Rect knob = thumb(scroll, maximum, viewport);
        scrollGrab = knob.contains(mouseX, mouseY) ? mouseY - knob.y() : knob.height() / 2f;
        return true;
    }

    private void updateScrollbar() {
        Rect viewport = draggingScroll == 1 ? LIST_VIEWPORT : DETAIL_VIEWPORT;
        float maximum = draggingScroll == 1 ? listMax : detailMax;
        float current = draggingScroll == 1 ? listScroll : detailScroll;
        Rect knob = thumb(current, maximum, viewport);
        float travel = Math.max(1, viewport.height() - knob.height());
        float value = clamp((mouseY - viewport.y() - scrollGrab) / travel * maximum, 0, maximum);
        if (draggingScroll == 1) {
            listScroll = listScrollTarget = value;
            listMotion.snap(value, now());
        } else {
            detailScroll = detailScrollTarget = value;
            detailMotion.snap(value, now());
        }
    }

    private void selectPage(int index) {
        if (navigation == index) {
            return;
        }
        navigation = index;
        binding = null;
        menu = null;
        cancelEditing();
        search.setFocused(false);
        if (!search.getValue().isEmpty()) {
            search.setValue("");
        }
        listScroll = listScrollTarget = 0;
        listMotion.snap(0, now());
        detailScroll = detailScrollTarget = 0;
        detailMotion.snap(0, now());
        refresh(now());
    }

    private int cursorAt(Rect box) {
        FontRenderer font = FontPresets.poppinsRegular(SZ_BODY, rasterScale());
        float left = box.x() + SEARCH_INNER_PAD + SEARCH_ICON + SEARCH_ICON_GAP;
        float x = mouseX - left;
        String value = search.value;
        for (int index = 0; index < value.length(); ) {
            int next = value.offsetByCodePoints(index, 1);
            float a = GlHelper.getStringWidth(value.substring(0, index), font);
            float b = GlHelper.getStringWidth(value.substring(0, next), font);
            if (x < (a + b) / 2) {
                return index;
            }
            index = next;
        }
        return value.length();
    }

    // -- number editing -----------------------------------------------------------

    private void startEditing(NumberSetting setting) {
        editing = setting;
        editText = number(setting.getValue().doubleValue());
        editReplace = true;
        draggingSlider = null;
        search.setFocused(false);
    }

    private void cancelEditing() {
        editing = null;
        editText = "";
        editReplace = true;
    }

    /** Applies the typed value, clamped to the setting's range; an empty field leaves it untouched. */
    private boolean commitEditing() {
        NumberSetting setting = editing;
        String text = editText;
        if (setting == null) {
            return false;
        }
        cancelEditing();
        if (!text.isEmpty()) {
            try {
                double value = Double.parseDouble(text);
                double min = setting.getMin().doubleValue(), max = setting.getMax().doubleValue();
                applyValue(setting, Math.max(min, Math.min(max, value)));
            } catch (NumberFormatException ignored) {
            }
        }
        refresh(now());
        return true;
    }

    private void updateSlider() {
        if (draggingSlider == null || !(draggingSlider.setting instanceof NumberSetting setting)) {
            draggingSlider = null;
            return;
        }
        Rect track = draggingSlider.control;
        float fraction = clamp((mouseX - track.x() - SLIDER_KNOB / 2f) / (track.width() - SLIDER_KNOB), 0, 1);
        double min = setting.getMin().doubleValue(), max = setting.getMax().doubleValue();
        double step = setting.getStep().doubleValue();
        double raw = min + fraction * (max - min);
        double snapped = step > 0.0 ? Math.round((raw - min) / step) * step + min : raw;
        double clamped = Math.max(min, Math.min(max, snapped));
        applyValue(setting, Math.round(clamped * 1000.0) / 1000.0);
    }

    /** Preserves the numeric type of the setting so downstream code keeps its casts valid. */
    private static void applyValue(NumberSetting setting, double value) {
        if (setting.getValue() instanceof Integer) {
            setting.setValue((int) Math.round(value));
        } else if (setting.getValue() instanceof Long) {
            setting.setValue(Math.round(value));
        } else if (setting.getValue() instanceof Float) {
            setting.setValue((float) value);
        } else {
            setting.setValue(value);
        }
    }

    // -- dropdown -----------------------------------------------------------------

    private void openMenu(ModeSetting setting, Rect anchor) {
        menu = new MenuState(setting, anchor, new Rect(anchor.x(), anchor.bottom() + 6, 0, 0));
        for (int i = 0; i < setting.getModes().length; i++) {
            if (setting.getModes()[i].equals(setting.getValue())) {
                menu.highlight = i;
                break;
            }
        }
        menu.motion.snap(0, now());
        menu.motion.to(1, now());
        binding = null;
        cancelEditing();
        updateMenu(now());
    }

    private void updateMenu(double time) {
        if (menu == null) {
            return;
        }
        if (menu.open && (selected == null || !rowRefs.contains(menu.anchorRef))) {
            menu.anchorRef = null;
            for (RowRef ref : rowRefs) {
                if (ref.setting == menu.setting) {
                    menu.anchorRef = ref;
                    break;
                }
            }
            if (menu.anchorRef == null) {
                menu.open = false;
            } else {
                menu.anchor = menu.anchorRef.control;
            }
        }
        int count = menu.setting.getModes().length;
        float width = Math.max(menu.anchor.width(), MENU_MIN_W);
        // Fit the whole option list. The old 280px cap silently hid everything past the eighth
        // entry — Gradient Theme's "Drift"/"Custom" were unreachable.
        float height = Math.min(HEIGHT - 16, count * MENU_OPTION_H + MENU_PAD * 2);
        float x = clamp(menu.anchor.x(), 8, WIDTH - width - 8);
        float y = menu.anchor.bottom() + 6;
        if (y + height > HEIGHT - 8) {
            y = Math.max(8, menu.anchor.y() - height - 6);
        }
        menu.bounds = new Rect(x, y, width, height);
        menu.motion.to(menu.open ? 1 : 0, time);
        syncHighlight();
        if (!menu.open && menu.motion.value() < .02f) {
            menu = null;
        }
    }

    /** Keeps the highlighted row on the option under the mouse, so click and highlight agree. */
    private void syncHighlight() {
        if (mouseX == menu.lastMouseX && mouseY == menu.lastMouseY) {
            return;
        }
        menu.lastMouseX = mouseX;
        menu.lastMouseY = mouseY;
        Rect inner = new Rect(menu.bounds.x(), menu.bounds.y() + MENU_PAD, menu.bounds.width(),
                menu.bounds.height() - MENU_PAD * 2);
        if (!inner.contains(mouseX, mouseY)) {
            return;
        }
        int index = (int) ((mouseY - inner.y()) / MENU_OPTION_H);
        if (index >= 0 && index < menu.setting.getModes().length) {
            menu.highlight = index;
        }
    }

    private NiloreRenderer.Menu menuFrame(double time) {
        if (menu == null) {
            return null;
        }
        String[] modes = menu.setting.getModes();
        List<MenuOption> options = new ArrayList<>(modes.length);
        for (int i = 0; i < modes.length; i++) {
            options.add(new MenuOption(modes[i], modes[i].equals(menu.setting.getValue()), i == menu.highlight));
        }
        return new NiloreRenderer.Menu(menu.bounds, menu.motion.to(menu.open ? 1 : 0, time), List.copyOf(options));
    }

    private boolean menuClick() {
        if (!menu.bounds.contains(mouseX, mouseY)) {
            return false;
        }
        if (menu.open && menu.motion.value() > .3f) {
            int index = (int) ((mouseY - menu.bounds.y() - MENU_PAD) / MENU_OPTION_H);
            String[] modes = menu.setting.getModes();
            if (index >= 0 && index < modes.length) {
                menu.setting.setValue(modes[index]);
                menu.highlight = index;
            }
        }
        menu.open = false;
        refresh(now());
        return true;
    }

    private void toggleOption(MultiSelectSetting setting, RowRef ref, int index) {
        for (Pill pill : ref.pills) {
            if (!pill.bounds().contains(mouseX, mouseY)) {
                continue;
            }
            List<String> values = new ArrayList<>(setting.getValue());
            if (!values.remove(pill.label())) {
                values.add(pill.label());
            }
            setting.setValue(values);
            return;
        }
    }

    // -- lifecycle ----------------------------------------------------------------

    @Override
    public boolean mouseDragged(double x, double y, int button, double dragX, double dragY) {
        if (closing) {
            return true;
        }
        screenX = (float) x;
        screenY = (float) y;
        if (button != 0) {
            return false;
        }
        if (dragging != null) {
            dragging.x = (float) x - windowGrabX;
            dragging.y = (float) y - windowGrabY;
            clampWindows();
            useLayout(dragging);
            setMouse(x, y);
            return true;
        }
        if (resizing != null) {
            float dx = (float) ((x - dragGrabX) / scale);
            float dy = (float) ((y - dragGrabY) / scale);
            float maxW = (width - resizing.x - 4) / scale;
            float maxH = (height - resizing.y - 4) / scale;
            if (resizeMode == 1 || resizeMode == 3) {
                resizing.w = clamp(resizeBaseW + dx, MIN_W, Math.min(MAX_W, maxW));
            }
            if (resizeMode == 2 || resizeMode == 3) {
                resizing.h = clamp(resizeBaseH + dy, MIN_H, Math.min(MAX_H, maxH));
            }
            useLayout(resizing);
            setMouse(x, y);
            refresh(now());
            return true;
        }
        useLayout(modules());
        setMouse(x, y);
        if (draggingScroll != 0) {
            updateScrollbar();
            refresh(now());
            return true;
        }
        if (draggingSlider != null) {
            updateSlider();
            refresh(now());
            return true;
        }
        return super.mouseDragged(x, y, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (button == 0 && (dragging != null || resizing != null || draggingScroll != 0
                || draggingSlider != null)) {
            releaseDrag();
            return true;
        }
        return super.mouseReleased(x, y, button);
    }

    private void releaseDrag() {
        dragging = null;
        resizing = null;
        resizeMode = 0;
        draggingScroll = 0;
        draggingSlider = null;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double vertical) {
        screenX = (float) x;
        screenY = (float) y;
        if (closing || dragging != null || resizing != null || draggingScroll != 0 || draggingSlider != null) {
            return true;
        }
        Win win = windowAt(x, y);
        if (win == null || win.id != Id.MODULES) {
            return true;
        }
        useLayout(win);
        setMouse(x, y);
        refresh(now());
        if (menu != null) {
            return true;
        }
        if (LIST_VIEWPORT.contains(mouseX, mouseY) && listMax > 0) {
            listScrollTarget = clamp(listScrollTarget - (float) vertical * NOTE_H, 0, listMax);
            return true;
        }
        if (DETAIL_VIEWPORT.contains(mouseX, mouseY) && detailMax > 0) {
            detailScrollTarget = clamp(detailScrollTarget - (float) vertical * 48, 0, detailMax);
            return true;
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (closing) {
            return true;
        }
        Win top = focused();
        useLayout(modules());
        if (binding != null) {
            binding.setKey(keyCode == 256 || keyCode == 259 || keyCode == 261 ? 0 : keyCode);
            binding = null;
            return true;
        }
        if (editing != null) {
            if (keyCode == 257 || keyCode == 335) {
                commitEditing();
            } else if (keyCode == 256) {
                cancelEditing();
                refresh(now());
            } else if (keyCode == 259) {
                if (editReplace) {
                    editText = "";
                    editReplace = false;
                } else if (!editText.isEmpty()) {
                    editText = editText.substring(0, editText.length() - 1);
                }
            } else if (keyCode == 261) {
                editText = "";
                editReplace = false;
            }
            refresh(now());
            return true;
        }
        if (keyCode == 256) {
            if (menu != null && menu.open) {
                menu.open = false;
            } else if (configName.focused) {
                configName.setFocused(false);
            } else if (search.focused) {
                search.setFocused(false);
                if (!search.value.isEmpty()) {
                    search.setValue("");
                }
            } else if (top != null && top.id != Id.MODULES) {
                closeWindow(top);
            } else {
                onClose();
            }
            refresh(now());
            return true;
        }
        if ((modifiers & 2) != 0 && keyCode == 70) {
            menu = null;
            search.setFocused(true);
            search.moveCursorTo(search.value.length(), false);
            search.selection = 0;
            refresh(now());
            return true;
        }
        if (menu != null && menu.open) {
            return true;
        }
        if (top != null && top.id == Id.CONFIGS && configName.focused) {
            if (keyCode == 257 || keyCode == 335) {
                saveConfig();
            } else {
                configName.keyPressed(keyCode, modifiers);
            }
            refresh(now());
            return true;
        }
        if (search.focused) {
            if (keyCode == 257 || keyCode == 335) {
                search.setFocused(false);
                return true;
            }
            search.keyPressed(keyCode, modifiers);
            refresh(now());
            // Swallow every other key while the field is focused so typing cannot reach keybinds.
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (closing || binding != null || menu != null) {
            return true;
        }
        if (editing != null) {
            boolean minus = codePoint == '-' && (editReplace || editText.isEmpty());
            if (Character.isDigit(codePoint) || codePoint == '.' || minus) {
                if (editReplace) {
                    editText = "";
                    editReplace = false;
                }
                if (editText.length() < 7 && !(codePoint == '.' && editText.contains("."))) {
                    editText += codePoint;
                    refresh(now());
                }
            }
            return true;
        }
        Win top = focused();
        if (top != null && top.id == Id.CONFIGS && configName.focused) {
            configName.charTyped(codePoint);
            refresh(now());
            return true;
        }
        if (!search.focused) {
            return super.charTyped(codePoint, modifiers);
        }
        search.charTyped(codePoint);
        refresh(now());
        return true;
    }

    @Override
    public void onClose() {
        if (closing) {
            return;
        }
        releaseDrag();
        binding = null;
        menu = null;
        cancelEditing();
        configName.setFocused(false);
        closing = true;
        windowMotion.to(0, now());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        NiloreClient client = NiloreClient.getInstance();
        if (client != null && client.getConfigManager() != null) {
            client.getConfigManager().saveAll();
        }
        detached = true;
        releaseDrag();
        menu = null;
        binding = null;
        super.removed();
    }

    private record RowRef(Rect bounds, Rect control, Rect valueBox, Kind kind, Module module, Setting<?> setting,
                          List<Pill> pills) {
    }

    private final class MenuState {
        final ModeSetting setting;
        final Motion motion = new Motion(0, 36);
        Rect anchor, bounds;
        RowRef anchorRef;
        boolean open = true;
        int highlight = -1;
        float lastMouseX = Float.NaN, lastMouseY = Float.NaN;

        MenuState(ModeSetting setting, Rect anchor, Rect bounds) {
            this.setting = setting;
            this.anchor = anchor;
            this.bounds = bounds;
        }
    }

    /** Minimal text field: value, caret and selection. */
    private static final class Field {
        private String value = "";
        private int cursor;
        private int selection;
        private boolean focused;
        private final int maxLength;

        Field(int maxLength) {
            this.maxLength = maxLength;
        }

        String getValue() {
            return value;
        }

        void setValue(String value) {
            this.value = value;
            this.cursor = value.length();
            this.selection = this.cursor;
        }

        void setFocused(boolean focused) {
            this.focused = focused;
            this.selection = this.cursor;
        }

        void moveCursorTo(int position, boolean select) {
            cursor = Math.max(0, Math.min(position, value.length()));
            if (!select) {
                selection = cursor;
            }
        }

        void keyPressed(int keyCode, int modifiers) {
            boolean control = (modifiers & 2) != 0;
            switch (keyCode) {
                case 259 -> {
                    if (cursor > 0) {
                        delete(cursor - 1, cursor);
                    }
                }
                case 261 -> {
                    if (cursor < value.length()) {
                        delete(cursor, cursor + 1);
                    }
                }
                case 263 -> {
                    cursor = Math.max(0, cursor - 1);
                    if (!control) {
                        selection = cursor;
                    }
                }
                case 262 -> {
                    cursor = Math.min(value.length(), cursor + 1);
                    if (!control) {
                        selection = cursor;
                    }
                }
                case 268 -> {
                    cursor = 0;
                    if (!control) {
                        selection = cursor;
                    }
                }
                case 269 -> {
                    cursor = value.length();
                    if (!control) {
                        selection = cursor;
                    }
                }
                case 65 -> {
                    if (control) {
                        selection = 0;
                        cursor = value.length();
                    }
                }
                default -> {
                }
            }
        }

        void charTyped(char codePoint) {
            if (codePoint < 32 || codePoint == 127 || value.length() >= maxLength) {
                return;
            }
            if (cursor != selection) {
                delete(Math.min(cursor, selection), Math.max(cursor, selection));
            }
            value = value.substring(0, cursor) + codePoint + value.substring(cursor);
            cursor++;
            selection = cursor;
        }

        private void delete(int from, int to) {
            value = value.substring(0, from) + value.substring(to);
            cursor = from;
            selection = from;
        }
    }
}
