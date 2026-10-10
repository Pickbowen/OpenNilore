package client.nilore.gui.nilore;

import java.math.BigDecimal;
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
import client.nilore.gui.nilore.NiloreLayout.Motion;
import client.nilore.gui.nilore.NiloreLayout.Rect;
import client.nilore.gui.nilore.NiloreLayout.Viewport;
import client.nilore.gui.nilore.NiloreRenderer.Detail;
import client.nilore.gui.nilore.NiloreRenderer.Editor;
import client.nilore.gui.nilore.NiloreRenderer.Group;
import client.nilore.gui.nilore.NiloreRenderer.Kind;
import client.nilore.gui.nilore.NiloreRenderer.MenuOption;
import client.nilore.gui.nilore.NiloreRenderer.Nav;
import client.nilore.gui.nilore.NiloreRenderer.Note;
import client.nilore.gui.nilore.NiloreRenderer.Pill;
import client.nilore.gui.nilore.NiloreRenderer.Row;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.modules.impl.render.ClickGuiModule;
import client.nilore.render.FontPresets;
import client.nilore.render.FontRenderer;
import client.nilore.render.GlHelper;
import client.nilore.render.Renderer;
import client.nilore.settings.Setting;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.MultiSelectSetting;
import client.nilore.settings.impl.NumberSetting;

import static client.nilore.gui.nilore.NiloreLayout.*;

/**
 * Click GUI ported from the reference's Apple-style notes layout.
 *
 * <p>The window is laid out in the reference's own 1360x860 coordinate space and scaled to fit the
 * screen, so every pane, row and control lands on the same offset as the original.
 */
public final class NiloreClickGui extends Screen {
    public static final NiloreClickGui instance = new NiloreClickGui();

    private static final Category[] NAV_CATEGORIES = {Category.COMBAT, Category.MOVEMENT, Category.PLAYER,
            Category.RENDER, Category.EXPLOIT, Category.WORLD, Category.MISC};
    private static final int[] NAV_ICONS = {0, 2, 3, 4, 6, 5, 1};
    private static final String NAV_LABEL = "Categories";
    private static final Rect EMPTY = new Rect(0, 0, 0, 0);
    private static final float VALUE_BOX_W = 80;

    private final Map<Object, Motion> toggles = new HashMap<>();
    private final Map<Object, Motion> controls = new HashMap<>();
    private final Motion windowMotion = new Motion(0, 22);
    private final Motion listMotion = new Motion(0, 28);
    private final Motion detailMotion = new Motion(0, 28);

    private Field search;
    private Viewport viewport;
    private List<Module> visible = List.of();
    private List<Note> notes = List.of();
    private Detail detail;
    private final List<RowRef> rowRefs = new ArrayList<>();
    private final List<Rect> navRects = new ArrayList<>();
    private Rect docToggle = EMPTY;
    private MenuState menu;
    private Module selected, binding;
    private NumberSetting editing;
    private RowRef draggingSlider;
    private int navigation;
    private float mouseX, mouseY, opacity, openingScale = 1;
    private float listScroll, listScrollTarget, listMax, detailScroll, detailScrollTarget, detailMax;
    private float windowGrabX, windowGrabY, scrollGrab;
    private int draggingScroll;
    private boolean draggingWindow, closing, detached = true;
    private String editText = "";
    private boolean editReplace = true;

    private NiloreClickGui() {
        super(Component.literal("Nilore"));
    }

    private static double now() {
        return System.nanoTime() / 1_000_000_000.0;
    }

    @Override
    protected void init() {
        if (search == null) {
            search = new Field(48);
        }
        Viewport center = centered(width, height);
        viewport = viewport(width, height, center.x(), center.y());
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
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
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
                    toggleMotion(module, module.isEnabled() ? 1 : 0, 45, time)));
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
        float bindWidth = Math.max(82, 22 + GlHelper.getStringWidth(label, FontPresets.poppinsRegular(
                SZ_BODY)));
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
                        value.getValue() ? 1 : 0, 45, time), hovered, false, List.of()));
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
                        FontPresets.poppinsRegular(SZ_BODY)), SELECT_MIN_W, SELECT_MAX_W);
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
                toggleMotion(module, module.isEnabled() ? 1 : 0, 45, time),
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

    private float toggleMotion(Object key, float target, double rate, double time) {
        return clamp(toggles.computeIfAbsent(key, ignored -> new Motion(target, rate)).to(target, time), 0, 1);
    }

    private float progressOf(NumberSetting setting, double time) {
        float fraction = fractionOf(setting);
        return clamp(controls.computeIfAbsent(setting, ignored -> new Motion(fraction, 22)).to(fraction, time), 0, 1);
    }

    private static float fractionOf(NumberSetting setting) {
        double min = setting.getMin().doubleValue(), max = setting.getMax().doubleValue();
        if (max <= min) {
            return 0;
        }
        return clamp((float) ((setting.getValue().doubleValue() - min) / (max - min)), 0, 1);
    }

    // -- render -------------------------------------------------------------------

    @Override
    public void render(GuiGraphics gg, int mx, int my, float partialTick) {
        if (viewport == null) {
            return;
        }
        double time = now();
        opacity = clamp(windowMotion.to(closing ? 0 : 1, time), 0, 1);
        openingScale = .975f + opacity * .025f;
        setMouse(mx, my);
        if (closing && opacity < .01f) {
            minecraft.setScreen(null);
            return;
        }
        listScroll = listMotion.to(listScrollTarget, time);
        detailScroll = detailMotion.to(detailScrollTarget, time);
        refresh(time);

        List<Nav> nav = new ArrayList<>(NAV_CATEGORIES.length);
        for (int i = 0; i < NAV_CATEGORIES.length; i++) {
            Category category = NAV_CATEGORIES[i];
            nav.add(new Nav(category.displayName, categoryCount(category), NAV_ICONS[i], i == navigation,
                    navRects.get(i).contains(mouseX, mouseY)));
        }
        boolean dark = dark();
        NiloreRenderer.Frame frame = new NiloreRenderer.Frame(viewport, opacity, openingScale,
                NiloreTheme.of(dark), dark, NAV_LABEL, nav, new Editor(search.value, search.focused), listTitle(),
                notes, listScroll, listMax, detail, detailScroll, detailMax, menuFrame(time), mouseX, mouseY);
        Renderer.renderConsumer(dc -> NiloreRenderer.paint(dc, gg, frame));
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
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (closing) {
            return true;
        }
        setMouse(mouseX, mouseY);
        if (button == 0 && commitEditing()) {
            return true;
        }
        refresh(now());
        if (menu != null) {
            if (menuClick()) {
                return true;
            }
            // Clicking the control that owns the menu just closes it; anywhere else the click
            // still reaches whatever sits underneath, like the reference's document listener.
            boolean onAnchor = menu.anchor.contains(this.mouseX, this.mouseY);
            menu.open = false;
            if (onAnchor) {
                refresh(now());
                return true;
            }
        }
        if (binding != null) {
            binding = null;
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
            if (!ref.control.contains(this.mouseX, this.mouseY)) {
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
        if (button == 0 && (new Rect(LIST_X, 0, LIST_W, LIST_TOP).contains(this.mouseX, this.mouseY)
                || new Rect(0, 0, SIDEBAR_W, NAV_TOP).contains(this.mouseX, this.mouseY))) {
            draggingWindow = true;
            windowGrabX = (float) mouseX - viewport.x();
            windowGrabY = (float) mouseY - viewport.y();
            return true;
        }
        if (!WINDOW.contains(this.mouseX, this.mouseY)) {
            onClose();
        }
        return true;
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
        FontRenderer font = FontPresets.poppinsRegular(SZ_BODY);
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
        applyValue(setting, min + fraction * (max - min));
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
        float height = Math.min(280, count * MENU_OPTION_H + MENU_PAD * 2);
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
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (closing) {
            return true;
        }
        setMouse(mouseX, mouseY);
        if (button != 0) {
            return false;
        }
        if (draggingWindow) {
            viewport = viewport(width, height, (float) mouseX - windowGrabX, (float) mouseY - windowGrabY);
            setMouse(mouseX, mouseY);
            return true;
        }
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
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && (draggingWindow || draggingScroll != 0 || draggingSlider != null)) {
            releaseDrag();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void releaseDrag() {
        draggingWindow = false;
        draggingScroll = 0;
        draggingSlider = null;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double vertical) {
        setMouse(x, y);
        if (closing || draggingWindow || draggingScroll != 0 || draggingSlider != null) {
            return true;
        }
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
        return super.mouseScrolled(x, y, vertical);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (closing) {
            return true;
        }
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
            } else if (search.focused) {
                search.setFocused(false);
                if (!search.value.isEmpty()) {
                    search.setValue("");
                }
            } else {
                onClose();
            }
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
