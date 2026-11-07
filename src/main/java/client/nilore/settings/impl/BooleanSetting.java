package client.nilore.settings.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import client.nilore.settings.Setting;
import client.nilore.settings.SettingVisibility;

public class BooleanSetting
extends Setting<Boolean> {
    public BooleanSetting(String string, Boolean bl) {
        super(string, bl);
    }
    public BooleanSetting(String string, Boolean bl, SettingVisibility settingVisibility) {
        super(string, bl, settingVisibility);
    }

    @Override
    public void save(JsonObject jsonObject) {
        jsonObject.addProperty(this.getName(), this.getValue());
    }

    @Override
    public void load(JsonElement jsonElement) {
        this.setValue(jsonElement.getAsBoolean());
    }
}