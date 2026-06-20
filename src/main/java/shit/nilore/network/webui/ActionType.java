package shit.nilore.network.webui;

import com.google.gson.annotations.SerializedName;

public enum ActionType {
    @SerializedName("a")
    BUTTON,
    @SerializedName("b")
    SLIDER,
    @SerializedName("c")
    TOGGLE
}
