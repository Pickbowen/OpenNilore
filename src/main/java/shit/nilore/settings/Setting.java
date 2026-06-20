package shit.nilore.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.Setter;
import lombok.Generated;
import shit.nilore.settings.SettingVisibility;

public abstract class Setting<T> {
    @Getter @Setter
    private String name;
    @Getter
    private T value;
    @Getter @Setter
    private SettingVisibility visibility;

    public Setting(String string, T t) {
        this.name = string;
        this.value = t;
        this.visibility = () -> true;
        this.onInit(t);
    }

    public void onInit(T t) {
    }

    public void onChanged(T t, T t2) {
    }

    public void setValue(T t) {
        T t2 = this.value;
        this.value = t;
        this.onChanged(t2, t);
    }

    public abstract void save(JsonObject var1);

    public abstract void load(JsonElement var1);

    @Generated
    public Setting(String string, T t, SettingVisibility settingVisibility) {
        this.name = string;
        this.value = t;
        this.visibility = settingVisibility;
    }
}