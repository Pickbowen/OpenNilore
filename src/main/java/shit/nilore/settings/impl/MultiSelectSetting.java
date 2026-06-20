package shit.nilore.settings.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import lombok.Getter;
import shit.nilore.settings.Setting;
import shit.nilore.settings.SettingVisibility;

public class MultiSelectSetting
extends Setting<List<String>> {
    @Getter
    private final List<String> options;

    public MultiSelectSetting(String string, String ... stringArray) {
        super(string, new ArrayList<>());
        this.options = Arrays.asList(stringArray);
    }

    public MultiSelectSetting withDefaults(String ... stringArray) {
        ArrayList<String> arrayList = new ArrayList<>(Arrays.asList(stringArray));
        this.setValue(arrayList);
        return this;
    }

    public boolean isSelected(String string) {
        return this.getValue().contains(string);
    }

    public MultiSelectSetting withVisibility(SettingVisibility settingVisibility) {
        this.setVisibility(settingVisibility);
        return this;
    }

    @Override
    public void save(JsonObject jsonObject) {
        JsonArray jsonArray = new JsonArray();
        for (String string : this.getValue()) {
            jsonArray.add(string);
        }
        jsonObject.add(this.getName(), jsonArray);
    }

    @Override
    public void load(JsonElement jsonElement) {
        ArrayList<String> arrayList = new ArrayList<>();
        if (jsonElement.isJsonArray()) {
            for (JsonElement jsonElement2 : jsonElement.getAsJsonArray()) {
                arrayList.add(jsonElement2.getAsString());
            }
        }
        this.setValue(arrayList);
    }

    }