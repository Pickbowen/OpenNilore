package shit.nilore.network.webui;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;

@Getter
public class ActionEntry {
    @SerializedName("a")
    private final String name;
    @SerializedName("b")
    private final ActionType type;
    @SerializedName("c")
    private final double current;
    @SerializedName("d")
    private final double min;
    @SerializedName("e")
    private final double max;

    public ActionEntry(String name, ActionType type, double current, double min, double max) {
        this.name = name;
        this.type = type;
        this.current = current;
        this.min = min;
        this.max = max;
    }
}
