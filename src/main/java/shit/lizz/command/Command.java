package shit.lizz.command;

import lombok.Getter;
import lombok.Generated;

public abstract class Command {
    @Getter
    private final String prefix;
    @Getter
    private final String[] aliases;

    public abstract void onCommand(String[] var1);

    public abstract String[] onTab(String[] var1);

    @Generated
    public Command(String string, String[] stringArray) {
        this.prefix = string;
        this.aliases = stringArray;
    }

    }