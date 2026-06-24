package shit.nilore.modules;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Generated;
import shit.nilore.ClientBase;
import shit.nilore.NiloreClient;
import shit.nilore.hud.ModuleListHud;
import shit.nilore.modules.Category;
import shit.nilore.modules.KeyBind;
import shit.nilore.settings.Setting;

public abstract class Module
extends ClientBase {
    @Getter
    private final String name;
    @Getter
    private final Category category;
    private int keyCode;
    @Getter
    private final KeyBind bind;
    @Getter
    private boolean enabled;
    @Getter
    private final List<Setting<?>> settings;
    private static final String REGISTER_FAIL_MSG = "Failed to register value for module ";

    protected Module(String name, Category category) {
        this.name = name;
        this.category = category;
        this.keyCode = 0;
        this.bind = new KeyBind(this.keyCode);
        this.settings = new ArrayList<>();
    }

    protected Module(String name, Category category, int keyCode) {
        this.name = name;
        this.category = category;
        this.keyCode = keyCode;
        this.bind = new KeyBind(this.keyCode);
        this.settings = new ArrayList<>();
    }

    public void setKey(int keyCode) {
        this.keyCode = keyCode;
        this.bind.setKey(keyCode);
    }

    public void addSetting(Setting<?> setting) {
        this.settings.add(setting);
    }

    public void registerSettings() {
        for (Field field : this.getClass().getDeclaredFields()) {
            try {
                Object value;
                if (!field.isAccessible()) {
                    field.setAccessible(true);
                }
                if (!((value = field.get(this)) instanceof Setting)) continue;
                this.addSetting((Setting)value);
            } catch (IllegalAccessException ex) {
                System.out.println(REGISTER_FAIL_MSG + this.getName() + "!");
            }
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;

        if (enabled) {
            NiloreClient.getInstance().getEventBus().register(this);
            this.onEnable();
        } else {
            this.onDisable();
            NiloreClient.getInstance().getEventBus().unregister(this);
        }
    }

    public void toggle() {
        this.setEnabled(!this.isEnabled());
    }

    protected void onEnable() {
    }

    protected void onDisable() {
    }

    public boolean isHiddenInModuleList() {
        return false;
    }

    public String getSuffix() {
        return null;
    }

    public void registerSetting(Setting<?>... settings) {
        for (Setting<?> setting : settings) {
            this.addSetting(setting);
        }
    }

    @Generated
    public int getKey() {
        return this.keyCode;
    }

}