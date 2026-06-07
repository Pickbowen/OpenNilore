package shit.lizz.gui.newclickgui;

import lombok.Getter;
import lombok.Generated;
import shit.lizz.gui.newclickgui.CategoryPanel;
import shit.lizz.gui.newclickgui.UIElement;
import shit.lizz.settings.Setting;
import shit.lizz.utils.animation.SmoothAnimationTimer;

public abstract class SettingElement<T extends Setting<?>>
extends UIElement {
    @Getter
    protected final CategoryPanel parentPanel;
    @Getter
    protected final T setting;
    @Getter
    protected final SmoothAnimationTimer visibilityTimer = new SmoothAnimationTimer();

    @Generated
    public SettingElement(CategoryPanel categoryPanel, T setting) {
        this.parentPanel = categoryPanel;
        this.setting = setting;
    }
}