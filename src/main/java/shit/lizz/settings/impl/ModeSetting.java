package shit.lizz.settings.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Getter;
import shit.lizz.settings.Setting;
import shit.lizz.settings.SettingVisibility;

public class ModeSetting
extends Setting<String> {
    @Getter
    private final String[] modes;

    public ModeSetting(String string, String ... stringArray) {
        super(string, null);
        this.modes = stringArray;
    }

    public ModeSetting withVisibility(SettingVisibility settingVisibility) {
        this.setVisibility(settingVisibility);
        return this;
    }

    public ModeSetting withDefault(String string) {
        this.setValue(string);
        return this;
    }

    public boolean is(String string) {
        return this.getValue().equals(string);
    }

    @Override
    public void save(JsonObject jsonObject) {
        jsonObject.addProperty(this.getName(), this.getValue());
    }

    @Override
    public void load(JsonElement jsonElement) {
        this.setValue(jsonElement.getAsString());
    }

    }