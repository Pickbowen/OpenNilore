package shit.nilore.config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import lombok.Getter;
import shit.nilore.manager.ConfigManager;

public abstract class Config {
    @Getter
    private final String name;
    @Getter
    private final File file;

    public Config(String string) {
        this.name = string;
        this.file = new File(ConfigManager.CONFIG_DIR, string);
    }

    public abstract void read(BufferedReader var1) throws IOException;

    public abstract void save(BufferedWriter var1) throws IOException;

    }