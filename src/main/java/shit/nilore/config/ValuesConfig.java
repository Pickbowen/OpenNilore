package shit.nilore.config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import shit.nilore.NiloreClient;
import shit.nilore.config.Config;
import shit.nilore.hud.HudElement;
import shit.nilore.modules.Module;
import shit.nilore.settings.impl.BooleanSetting;
import shit.nilore.settings.impl.ModeSetting;
import shit.nilore.settings.impl.MultiSelectSetting;
import shit.nilore.settings.impl.NumberSetting;

public class ValuesConfig
extends Config {
    private static final Logger LOGGER = LogManager.getLogger(ValuesConfig.class);

    public ValuesConfig() {
        super("values.cfg");
    }

    @Override
    public void read(BufferedReader bufferedReader) throws IOException {
        String string;
        while ((string = bufferedReader.readLine()) != null) {
            try {
                String[] stringArray = string.split(":", 4);
                if (stringArray.length != 4) {
                    LOGGER.error("Failed to read line {}!", string);
                    continue;
                }
                String string2 = stringArray[0];
                String string3 = stringArray[1];
                String string4 = stringArray[2];
                String string5 = stringArray[3];
                Module module = NiloreClient.getInstance().getModuleManager().getModule(string3);
                if (module == null) continue;
                String string6 = string;
                if (module instanceof HudElement hudElement) {
                    try {
                        if (string2.equals("P")) {
                            if (string4.equals("X")) {
                                hudElement.setX(Float.parseFloat(string5));
                            }
                            if (string4.equals("Y")) {
                                hudElement.setY(Float.parseFloat(string5));
                            }
                        }
                    } catch (Exception exception) {
                        LOGGER.error("Failed to set position of {}!", hudElement.getName());
                    }
                }
                module.getSettings().forEach(setting -> {
                    if (!string4.equals(setting.getName())) {
                        return;
                    }
                    switch (string2) {
                        case "B":
                            ((BooleanSetting) setting).setValue(Boolean.parseBoolean(string5));
                            break;
                        case "F":
                            ((NumberSetting) setting).setValue(Float.parseFloat(string5));
                            break;
                        case "MV":
                            MultiSelectSetting multiSelectSetting = (MultiSelectSetting) setting;
                            if (string5.isEmpty()) {
                                multiSelectSetting.setValue(new ArrayList<>());
                            } else {
                                multiSelectSetting.setValue(new ArrayList<>(Arrays.asList(string5.split(","))));
                            }
                            break;
                        case "M":
                            ModeSetting modeSetting = (ModeSetting) setting;
                            if (string5.isEmpty()) {
                                LOGGER.error("Failed to read mode value {}!", string6);
                            } else {
                                modeSetting.setValue(string5);
                            }
                            break;
                        default:
                            LOGGER.error("Unknown value type of {}!", string3);
                    }
                });
            } catch (Exception exception) {
                LOGGER.error("Failed to read value {}!: {}", string, exception);
            }
        }
    }

    @Override
    public void save(BufferedWriter bufferedWriter) throws IOException {
        for (Module module : NiloreClient.getInstance().getModuleManager().getModules()) {
            if (module instanceof HudElement hudElement) {
                bufferedWriter.write(String.format((String)"P:%s:X:%s\n", (Object[])new Object[]{module.getName(), hudElement.getX()}));
                bufferedWriter.write(String.format((String)"P:%s:Y:%s\n", (Object[])new Object[]{module.getName(), hudElement.getY()}));
            }
            module.getSettings().forEach(setting -> {
                try {
                    if (setting instanceof BooleanSetting booleanSetting) {
                        bufferedWriter.write(String.format((String)"B:%s:%s:%s\n", (Object[])new Object[]{module.getName(), booleanSetting.getName(), booleanSetting.getValue()}));
                    } else if (setting instanceof NumberSetting numberSetting) {
                        bufferedWriter.write(String.format((String)"F:%s:%s:%s\n", (Object[])new Object[]{module.getName(), numberSetting.getName(), numberSetting.getValue()}));
                    } else if (setting instanceof ModeSetting modeSetting) {
                        bufferedWriter.write(String.format((String)"M:%s:%s:%s\n", (Object[])new Object[]{module.getName(), modeSetting.getName(), modeSetting.getValue()}));
                    } else if (setting instanceof MultiSelectSetting multiSelectSetting) {
                        bufferedWriter.write(String.format((String)"MV:%s:%s:%s\n", (Object[])new Object[]{module.getName(), multiSelectSetting.getName(), String.join(",", multiSelectSetting.getValue())}));
                    } else {
                        LOGGER.error("Unknown value type of {}!", setting.getName());
                    }
                } catch (Exception exception) {
                    LOGGER.error("Failed to save value {}!", setting.getName());
                }
            });
        }
    }
}