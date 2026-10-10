package client.nilore.gui.neverlose;

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
import client.nilore.gui.neverlose.NeverloseLayout.Motion;
import client.nilore.gui.neverlose.NeverloseLayout.Rect;
import client.nilore.gui.neverlose.NeverloseLayout.Viewport;
import client.nilore.gui.neverlose.NeverloseRenderer.Kind;
import client.nilore.gui.neverlose.NeverloseRenderer.Row;
import client.nilore.gui.neverlose.NeverloseRenderer.Section;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.render.FontPresets;
import client.nilore.render.FontRenderer;
import client.nilore.render.GlHelper;
import client.nilore.render.Renderer;
import client.nilore.settings.Setting;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.MultiSelectSetting;
import client.nilore.settings.impl.NumberSetting;

import static client.nilore.gui.neverlose.NeverloseLayout.*;

/**
 * Click GUI ported from the Neverlose style of the reference client.
 *
 * <p>Layout, colours and interaction flow mirror the original; only the data source changes
 * (Nilore modules / settings instead of the reference feature manager).
 */
public final class NeverloseClickGui extends Screen {
    public static final NeverloseClickGui instance = new NeverloseClickGui();

    private static final Category[] NAV_CATEGORIES = {Category.COMBAT, Category.MOVEMENT, Category.PLAYER,
            Category.RENDER, Category.EXPLOIT, Category.WORLD, Category.MISC};

    private final Map<Module, ModuleState> modules = new HashMap<>();
    private final Map<Object, Motion> controls = new HashMap<>();
    private final Map<Object, Motion> hover = new HashMap<>();
    private final Map<Object, Double> pulses = new HashMap<>();
    private final Motion menu = new Motion(0, 22);
    private final Motion selection = new Motion(navigation(0).y(), 30);
    private final Motion scrolling = new Motion(0, 28);
    private final List<Target> targets = new ArrayList<>();
    private List<Section> sections = List.of();
    private List<Module> packedModules = List.of();
    private List<Integer> moduleColumns = List.of();
    private Field search, draggingEditor;
    private Viewport viewport;
    private int navigation;
    private float mouseX, mouseY, opacity, openingScale = 1, scrollTarget, scroll, maximumScroll;
    private float windowGrabX, windowGrabY, scrollbarGrab;
    private boolean draggingWindow, draggingScrollbar, closing, detached = true;
    private Target draggingSlider;
    private Module binding;
    private Popup popup;
    private NumberSetting editing;
    private String editText = "";
    private boolean editReplace = true;

    private NeverloseClickGui() {
        super(Component.literal("Neverlose"));
    }

    private static double now() {
        return System.nanoTime() / 1_000_000_000.0;
    }

    @Override
    protected void init() {
        if (search == null) {
            search = new Field(120);
            search.responder = this::resetScroll;
        }
        Viewport center = centered(width, height);
        viewport = viewport(width, height, center.x(), center.y());
        if (detached) {
            double time = now();
            menu.snap(0, time);
            menu.to(1, time);
            closing = false;
            detached = false;
            popup = null;
            binding = null;
            releaseDrag();
            search.setFocused(false);
        }
        refresh(now());
    }

    private void resetScroll() {
        scrollTarget = scroll = 0;
        scrolling.snap(0, now());
        if (popup != null) {
            popup.open = false;
        }
    }

    // data

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
        String query = search.getValue().strip().toLowerCase(Locale.ROOT).replace(" ", "");
        boolean all = !query.isEmpty();
        return NiloreClient.getInstance().getModuleManager().getModules().stream()
                .filter(module -> !module.isHiddenInModuleList())
                .filter(module -> all || module.getCategory() == NAV_CATEGORIES[navigation])
                .filter(module -> !all || matchesName(module.getName(), query))
                .sorted(Comparator.comparing(Module::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * Matches the start of the module name or of any of its words.
     *
     * <p>A plain {@code contains} would let "ki" hit "Block In", which is not what a name search
     * is expected to do.
     */
    private static boolean matchesName(String name, String query) {
        String spaced = displayName(name).toLowerCase(Locale.ROOT);
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

    private void refresh(double time) {
        targets.clear();
        List<Module> visible = visibleModules();
        List<List<Setting<?>>> settings = new ArrayList<>();
        List<Float> heights = new ArrayList<>();
        for (Module module : visible) {
            ModuleState state = modules.computeIfAbsent(module, ignored -> new ModuleState());
            List<Setting<?>> values = visibleSettings(module);
            settings.add(values);
            float reveal = state.motion.to(state.expanded ? 1 : 0, time);
            heights.add(SECTION_HEADER + (values.size() + 1) * ROW * reveal);
        }
        if (!visible.equals(packedModules)) {
            packedModules = visible;
            moduleColumns = pack(heights, 0).sections().stream()
                    .map(rect -> rect.x() == CONTENT.x() ? 0 : 1).toList();
        }
        Packed packed = pack(heights, moduleColumns, scroll);
        maximumScroll = Math.max(0, packed.height() - CONTENT.height());
        scrollTarget = clamp(scrollTarget, 0, maximumScroll);
        if (scroll > maximumScroll) {
            scroll = maximumScroll;
            scrolling.snap(scroll, time);
            packed = pack(heights, moduleColumns, scroll);
        }
        List<Section> result = new ArrayList<>();
        for (int i = 0; i < visible.size(); i++) {
            Module module = visible.get(i);
            Rect section = packed.sections().get(i);
            ModuleState state = modules.get(module);
            Rect header = new Rect(section.x(), section.y(), section.width(), SECTION_HEADER);
            targets.add(new Target(header.intersect(CONTENT), header, module, null, Action.HEADER));
            Rect key = binding(section);
            targets.add(new Target(key.intersect(CONTENT), key, module, null, Action.BIND));
            List<Row> rows = new ArrayList<>();
            float y = section.y() + SECTION_HEADER;
            Rect rowBounds = new Rect(section.x(), y, section.width(), ROW);
            rows.add(row(Kind.ENABLED, rowBounds, "Enabled", "", module, module.isEnabled() ? 1 : 0, 45, time, false));
            addRowTarget(rowBounds, section, state, module, null, Action.ENABLED);
            y += ROW;
            for (Setting<?> setting : settings.get(i)) {
                rowBounds = new Rect(section.x(), y, section.width(), ROW);
                if (setting instanceof BooleanSetting value) {
                    rows.add(row(Kind.BOOLEAN, rowBounds, setting.getName(), "", setting, value.getValue() ? 1 : 0, 45,
                            time, false));
                    addRowTarget(rowBounds, section, state, module, setting, Action.BOOLEAN);
                } else if (setting instanceof NumberSetting value) {
                    double min = value.getMin().doubleValue(), max = value.getMax().doubleValue();
                    float fraction = max <= min ? 0 : (float) ((value.getValue().doubleValue() - min) / (max - min));
                    boolean active = editing == value;
                    String display = active ? editText : number(value.getValue().doubleValue());
                    rows.add(row(Kind.NUMBER, rowBounds, setting.getName(), display, setting, fraction, 24, time,
                            active));
                    addRowTarget(rowBounds, section, state, module, setting, Action.NUMBER);
                } else if (setting instanceof ModeSetting mode) {
                    rows.add(row(Kind.CHOICE, rowBounds, setting.getName(), mode.getValue(), setting, 0, 20, time,
                            false));
                    addRowTarget(rowBounds, section, state, module, setting, Action.CHOICE);
                } else if (setting instanceof MultiSelectSetting select) {
                    List<String> selected = new ArrayList<>();
                    for (String option : select.getOptions()) {
                        if (select.isSelected(option)) {
                            selected.add(option);
                        }
                    }
                    rows.add(row(Kind.CHOICE, rowBounds, setting.getName(),
                            selected.isEmpty() ? "None" : String.join(", ", selected), setting, 0, 20, time, false));
                    addRowTarget(rowBounds, section, state, module, setting, Action.CHOICE);
                }
                y += ROW;
            }
            result.add(new Section(section, displayName(module.getName()), keyLabel(module), state.motion.value(),
                    List.copyOf(rows)));
        }
        sections = List.copyOf(result);
        if (draggingSlider != null) {
            Target live = targets.stream()
                    .filter(target -> target.setting == draggingSlider.setting && target.action == Action.NUMBER)
                    .findFirst().orElse(null);
            draggingSlider = live != null && live.hit.height() > 0 ? live : null;
        }
        if (binding != null && !visible.contains(binding)) {
            binding = null;
        }
        updatePopup(time);
    }

    private Row row(Kind kind, Rect rowBounds, String label, String value, Object key, float target, double rate,
                    double time, boolean editing) {
        boolean hovered = rowBounds.intersect(CONTENT).contains(mouseX, mouseY) && popup == null;
        float progress = controls.computeIfAbsent(key, ignored -> new Motion(target, rate)).to(target, time);
        float feedback = hover.computeIfAbsent(key, ignored -> new Motion(0, 24))
                .to(hovered || draggingSlider != null && draggingSlider.setting == key ? 1 : 0, time);
        Double pulse = pulses.get(key);
        if (pulse != null) {
            feedback = Math.max(feedback, (float) Math.exp(-18 * (time - pulse)));
        }
        return new Row(kind, rowBounds, label, value, clamp(progress, 0, 1), feedback, hovered, editing);
    }

    private void addRowTarget(Rect row, Rect section, ModuleState state, Module module, Setting<?> setting,
                              Action action) {
        if (state.expanded && state.motion.value() > .15f) {
            Rect hit = row.intersect(section).intersect(CONTENT);
            targets.add(new Target(hit, row, module, setting, action));
        }
    }

    private String keyLabel(Module module) {
        if (module == binding) {
            return "PRESS KEY";
        }
        String label = module.getBind() == null ? "NONE" : module.getBind().getName();
        if (label == null || "None".equalsIgnoreCase(label)) {
            return "NONE";
        }
        return label.toUpperCase(Locale.ROOT);
    }

    private static String displayName(String name) {
        return name.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ");
    }

    private static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    // render

    @Override
    public void render(GuiGraphics gg, int mx, int my, float partialTick) {
        if (viewport == null) {
            return;
        }
        double time = now();
        opacity = clamp(menu.to(closing ? 0 : 1, time), 0, 1);
        openingScale = .975f + opacity * .025f;
        setMouse(mx, my);
        if (closing && opacity < .01f) {
            minecraft.setScreen(null);
            return;
        }
        scroll = scrolling.to(scrollTarget, time);
        refresh(time);
        NeverloseRenderer.Frame frame = new NeverloseRenderer.Frame(viewport, opacity, openingScale, navigation,
                selection.to(navigation(navigation).y(), time), editor(search), sections, popupFrame(time), scroll,
                maximumScroll, mouseX, mouseY);
        Renderer.renderConsumer(dc -> NeverloseRenderer.paint(dc, gg, frame));
    }

    private static NeverloseRenderer.Editor editor(Field field) {
        return new NeverloseRenderer.Editor(field.value, field.cursor, field.selection, field.focused);
    }

    private void setMouse(double x, double y) {
        if (viewport == null) {
            return;
        }
        mouseX = viewport.localX(x, openingScale);
        mouseY = viewport.localY(y, openingScale);
    }

    private void selectPage(int index) {
        if (navigation == index) {
            return;
        }
        navigation = index;
        binding = null;
        popup = null;
        cancelEditing();
        search.setFocused(false);
        search.setValue("");
        resetScroll();
        refresh(now());
    }

    // input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (closing) {
            return true;
        }
        commitEditing();
        setMouse(mouseX, mouseY);
        refresh(now());
        if (popup != null) {
            if (popupClick()) {
                return true;
            }
            popup.open = false;
            return true;
        }
        if (binding != null) {
            binding = null;
            return true;
        }
        if (button == 0 && SEARCH.contains(this.mouseX, this.mouseY)) {
            focusEditor(search, SEARCH, 30, true);
            return true;
        }
        search.setFocused(false);
        for (int i = 0; i < NAV_NAMES.length; i++) {
            if (navigation(i).contains(this.mouseX, this.mouseY)) {
                if (button == 0) {
                    selectPage(i);
                }
                return true;
            }
        }
        if (button == 0 && maximumScroll > 0
                && new Rect(743, CONTENT.y(), 12, CONTENT.height()).contains(this.mouseX, this.mouseY)) {
            Rect thumb = thumb(scroll, maximumScroll, CONTENT);
            scrollbarGrab = thumb.contains(this.mouseX, this.mouseY) ? this.mouseY - thumb.y() : thumb.height() / 2;
            draggingScrollbar = true;
            updateScrollbar();
            return true;
        }
        for (int i = targets.size() - 1; i >= 0; i--) {
            Target target = targets.get(i);
            if (!target.hit.contains(this.mouseX, this.mouseY)) {
                continue;
            }
            Object pulseKey = target.setting == null ? target.module : target.setting;
            if (pulseKey != null) {
                pulses.put(pulseKey, now());
            }
            switch (target.action) {
                case HEADER -> {
                    if (button == 1 || button == 0 && this.mouseX >= target.bounds.right() - 17) {
                        ModuleState state = modules.get(target.module);
                        state.expanded = !state.expanded;
                    } else if (button == 2) {
                        binding = target.module;
                    } else if (button == 0) {
                        target.module.toggle();
                    }
                }
                case BIND -> {
                    if (button == 0 || button == 2) {
                        binding = target.module;
                    }
                }
                case ENABLED -> {
                    if (button == 0) {
                        target.module.toggle();
                    }
                }
                case BOOLEAN -> {
                    if (button == 0 && target.setting instanceof BooleanSetting value) {
                        value.setValue(!value.getValue());
                    }
                }
                case NUMBER -> {
                    if (button == 0 && target.setting instanceof NumberSetting setting) {
                        if (valueBox(target.bounds).contains(this.mouseX, this.mouseY)) {
                            startEditing(setting);
                        } else if (control(target.bounds).contains(this.mouseX, this.mouseY)) {
                            draggingSlider = target;
                            updateSlider();
                        }
                    }
                }
                case CHOICE -> {
                    if (button == 0) {
                        openPopup(target.setting, control(target.bounds));
                    }
                }
            }
            refresh(now());
            return true;
        }
        if (button == 0 && new Rect(0, 0, WIDTH, HEADER).contains(this.mouseX, this.mouseY)) {
            draggingWindow = true;
            windowGrabX = (float) mouseX - viewport.x();
            windowGrabY = (float) mouseY - viewport.y();
            return true;
        }
        return new Rect(0, 0, WIDTH, HEIGHT).contains(this.mouseX, this.mouseY)
                || super.mouseClicked(mouseX, mouseY, button);
    }

    private void focusEditor(Field field, Rect rect, float padding, boolean selectAll) {
        search.setFocused(true);
        if (selectAll) {
            field.moveCursorToEnd(false);
            field.moveCursorToStart(true);
        } else {
            field.moveCursorTo(cursorAt(field, rect, padding), false);
        }
        draggingEditor = field;
    }

    private int cursorAt(Field field, Rect rect, float padding) {
        FontRenderer font = FontPresets.poppinsRegular(24f);
        float x = mouseX - rect.x() - padding + editorOffset(field, rect, padding);
        String value = field.value;
        for (int index = 0; index < value.length(); ) {
            int next = value.offsetByCodePoints(index, 1);
            float left = GlHelper.getStringWidth(value.substring(0, index), font);
            float right = GlHelper.getStringWidth(value.substring(0, next), font);
            if (x < (left + right) / 2) {
                return index;
            }
            index = next;
        }
        return value.length();
    }

    private static float editorOffset(Field field, Rect box, float padding) {
        if (!field.focused) {
            return 0;
        }
        float width = GlHelper.getStringWidth(field.value.substring(0, field.cursor), FontPresets.poppinsRegular(24f));
        return Math.max(0, width - box.width() + padding + 18);
    }

    private void startEditing(NumberSetting setting) {
        editing = setting;
        // Prefill the box with the current value; the first keystroke replaces it.
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
    private void commitEditing() {
        NumberSetting setting = editing;
        String text = editText;
        cancelEditing();
        if (setting == null || text.isEmpty()) {
            return;
        }
        try {
            double value = Double.parseDouble(text);
            double min = setting.getMin().doubleValue(), max = setting.getMax().doubleValue();
            applyValue(setting, Math.max(min, Math.min(max, value)));
        } catch (NumberFormatException ignored) {
        }
    }

    private void updateSlider() {
        if (draggingSlider == null || !shown(draggingSlider.setting)) {
            draggingSlider = null;
            return;
        }
        NumberSetting setting = (NumberSetting) draggingSlider.setting;
        double min = setting.getMin().doubleValue(), max = setting.getMax().doubleValue();
        applyValue(setting, min + fraction(mouseX, slider(draggingSlider.bounds)) * (max - min));
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

    private void updateScrollbar() {
        Rect thumb = thumb(scroll, maximumScroll, CONTENT);
        scroll = scrollTarget = clamp((mouseY - CONTENT.y() - scrollbarGrab) / (CONTENT.height() - thumb.height())
                * maximumScroll, 0, maximumScroll);
        scrolling.snap(scroll, now());
    }

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
        if (draggingScrollbar) {
            updateScrollbar();
            refresh(now());
            return true;
        }
        if (draggingEditor != null) {
            draggingEditor.moveCursorTo(cursorAt(draggingEditor, SEARCH, 30), true);
            return true;
        }
        if (draggingSlider != null) {
            refresh(now());
            updateSlider();
            refresh(now());
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            boolean captured = draggingWindow || draggingScrollbar || draggingSlider != null || draggingEditor != null;
            releaseDrag();
            if (captured) {
                return true;
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void releaseDrag() {
        draggingWindow = draggingScrollbar = false;
        draggingSlider = null;
        draggingEditor = null;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double vertical) {
        setMouse(x, y);
        if (closing || draggingWindow || draggingScrollbar || draggingSlider != null) {
            return true;
        }
        refresh(now());
        if (popup != null) {
            if (popup.bounds.contains(mouseX, mouseY)) {
                popup.scroll = clamp(popup.scroll - (float) vertical * OPTION * 2, 0, popup.maximumScroll());
            } else {
                popup.open = false;
            }
            return true;
        }
        if (CONTENT.contains(mouseX, mouseY)) {
            scrollTarget = clamp(scrollTarget - (float) vertical * ROW * 2, 0, maximumScroll);
            return true;
        }
        return super.mouseScrolled(x, y, vertical);
    }

    private void openPopup(Setting<?> setting, Rect anchor) {
        popup = new Popup(setting, anchor);
        List<String> labels = popup.labels();
        for (int i = 0; i < labels.size(); i++) {
            if (selected(popup, i)) {
                popup.highlight = i;
                break;
            }
        }
        popup.motion.snap(0, now());
        popup.motion.to(1, now());
        binding = null;
        cancelEditing();
        updatePopup(now());
    }

    private boolean selected(Popup popup, int index) {
        if (popup.setting instanceof ModeSetting mode) {
            return mode.getModes()[index].equals(mode.getValue());
        }
        MultiSelectSetting select = (MultiSelectSetting) popup.setting;
        return select.isSelected(select.getOptions().get(index));
    }

    private void updatePopup(double time) {
        if (popup == null) {
            return;
        }
        if (popup.open) {
            Target target = targets.stream()
                    .filter(entry -> entry.setting == popup.setting && entry.action == Action.CHOICE).findFirst()
                    .orElse(null);
            if (target == null || target.hit.height() < ROW - 1) {
                popup.open = false;
            } else {
                popup.anchor = control(target.bounds);
            }
        }
        popup.bounds = dropdown(popup.anchor, popup.labels().size());
        popup.scroll = clamp(popup.scroll, 0, popup.maximumScroll());
        if (popup.open) {
            syncHighlight(time);
        }
        if (popup.motion.to(popup.open ? 1 : 0, time) < .005f && !popup.open) {
            popup = null;
        }
    }

    /**
     * Moves the dropdown cursor to the item under the mouse, so the highlighted row is always the
     * one a click would pick. Only reacts to mouse movement, otherwise the arrow keys would be
     * overridden every frame.
     */
    private void syncHighlight(double time) {
        if (mouseX == popup.lastMouseX && mouseY == popup.lastMouseY) {
            return;
        }
        popup.lastMouseX = mouseX;
        popup.lastMouseY = mouseY;
        Rect inner = new Rect(popup.bounds.x() + 4, popup.bounds.y() + 4, popup.bounds.width() - 8,
                popup.bounds.height() - 8);
        if (!inner.contains(mouseX, mouseY)) {
            return;
        }
        int hovered = (int) ((mouseY - inner.y() + popup.scroll) / OPTION);
        if (hovered >= 0 && hovered < popup.labels().size()) {
            popup.highlight = hovered;
        }
    }

    private NeverloseRenderer.Dropdown popupFrame(double time) {
        if (popup == null) {
            return null;
        }
        List<NeverloseRenderer.Option> options = new ArrayList<>();
        List<String> labels = popup.labels();
        for (int i = 0; i < labels.size(); i++) {
            options.add(new NeverloseRenderer.Option(labels.get(i), selected(popup, i), i == popup.highlight));
        }
        return new NeverloseRenderer.Dropdown(popup.bounds, popup.motion.to(popup.open ? 1 : 0, time), popup.scroll,
                List.copyOf(options));
    }

    private boolean popupClick() {
        if (!popup.bounds.contains(mouseX, mouseY)) {
            return false;
        }
        if (!popup.open || popup.motion.value() < .3f) {
            return true;
        }
        Rect clip = new Rect(popup.bounds.x() + 4, popup.bounds.y() + 4, popup.bounds.width() - 8,
                Math.min(popup.bounds.height() - 8, popup.bounds.height() * popup.motion.value() - 4));
        if (clip.contains(mouseX, mouseY)) {
            selectOption((int) ((mouseY - popup.bounds.y() - 4 + popup.scroll) / OPTION));
        }
        return true;
    }

    private void selectOption(int index) {
        if (popup == null || index < 0 || index >= popup.labels().size()) {
            return;
        }
        if (popup.setting instanceof ModeSetting mode) {
            mode.setValue(mode.getModes()[index]);
        } else if (popup.setting instanceof MultiSelectSetting select) {
            String option = select.getOptions().get(index);
            List<String> values = new ArrayList<>(select.getValue());
            if (!values.remove(option)) {
                values.add(option);
            }
            select.setValue(values);
        }
        pulses.put(popup.setting, now());
        popup.highlight = index;
        refresh(now());
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
            if (popup != null && popup.open) {
                popup.open = false;
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
            popup = null;
            focusEditor(search, SEARCH, 30, true);
            return true;
        }
        if (popup != null && popup.open) {
            if (keyCode == 264 || keyCode == 265) {
                int direction = keyCode == 264 ? 1 : -1;
                int size = popup.labels().size();
                popup.highlight = popup.highlight < 0 ? direction > 0 ? 0 : size - 1
                        : Math.floorMod(popup.highlight + direction, size);
                float y = popup.highlight * OPTION;
                popup.scroll = clamp(popup.scroll, Math.max(0, y + OPTION - popup.bounds.height() + 8),
                        Math.min(y, popup.maximumScroll()));
                return true;
            }
            if (keyCode == 257 || keyCode == 335) {
                selectOption(Math.max(0, popup.highlight));
                return true;
            }
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
        if (closing || binding != null || popup != null) {
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

    // lifecycle

    @Override
    public void onClose() {
        if (closing) {
            return;
        }
        releaseDrag();
        binding = null;
        popup = null;
        cancelEditing();
        closing = true;
        menu.to(0, now());
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
        popup = null;
        binding = null;
        super.removed();
    }

    private enum Action {HEADER, BIND, ENABLED, BOOLEAN, NUMBER, CHOICE}

    private record Target(Rect hit, Rect bounds, Module module, Setting<?> setting, Action action) {
    }

    private static final class ModuleState {
        boolean expanded = true;
        final Motion motion = new Motion(1, 30);
    }

    private final class Popup {
        final Setting<?> setting;
        final Motion motion = new Motion(0, 36);
        Rect anchor, bounds;
        boolean open = true;
        float scroll;
        int highlight = -1;
        float lastMouseX = Float.NaN, lastMouseY = Float.NaN;

        Popup(Setting<?> setting, Rect anchor) {
            this.setting = setting;
            this.anchor = anchor;
        }

        List<String> labels() {
            if (setting instanceof ModeSetting mode) {
                return List.of(mode.getModes());
            }
            return ((MultiSelectSetting) setting).getOptions();
        }

        float maximumScroll() {
            return Math.max(0, labels().size() * OPTION - bounds.height() + 8);
        }
    }

    /** Minimal text field: value, caret and selection. */
    private static final class Field {
        private String value = "";
        private int cursor;
        private int selection;
        private boolean focused;
        private final int maxLength;
        private Runnable responder;

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
            if (responder != null) {
                responder.run();
            }
        }

        void setFocused(boolean focused) {
            this.focused = focused;
            this.selection = this.cursor;
        }

        void moveCursorToEnd(boolean select) {
            cursor = value.length();
            if (!select) {
                selection = cursor;
            }
        }

        void moveCursorToStart(boolean select) {
            cursor = 0;
            if (!select) {
                selection = cursor;
            }
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
            if (responder != null) {
                responder.run();
            }
        }

        private void delete(int from, int to) {
            value = value.substring(0, from) + value.substring(to);
            cursor = from;
            selection = from;
            if (responder != null) {
                responder.run();
            }
        }
    }
}
