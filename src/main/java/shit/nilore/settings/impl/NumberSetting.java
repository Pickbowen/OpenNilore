package shit.nilore.settings.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Getter;
import shit.nilore.settings.Setting;
import shit.nilore.settings.SettingVisibility;

public class NumberSetting
extends Setting<Number> {
    @Getter
    private final Number min;
    @Getter
    private final Number max;
    @Getter
    private final Number step;

    public NumberSetting(String name, Number number, Number min, Number max, Number step) {
        super(name, number);
        this.min = min;
        this.max = max;
        this.step = step;
    }

    public NumberSetting(String name, Number number, Number min, Number max, Number step, SettingVisibility settingVisibility) {
        super(name, number, settingVisibility);
        this.min = min;
        this.max = max;
        this.step = step;
    }

    @Override
    public void save(JsonObject jsonObject) {
        jsonObject.addProperty(this.getName(), this.getValue());
    }

    @Override
    public void load(JsonElement jsonElement) {
        this.setValue(jsonElement.getAsNumber());
    }

    }