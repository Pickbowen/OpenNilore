package client.nilore.modules.impl.render;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import client.nilore.gui.NewClickGui;
import client.nilore.gui.OldClickGui;
import client.nilore.gui.PanelClickGui;
import client.nilore.gui.neverlose.NeverloseClickGui;
import client.nilore.gui.nilore.NiloreClickGui;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.ModeSetting;

public class ClickGuiModule
extends Module {
    public static final Logger LOGGER = LogManager.getLogger(ClickGuiModule.class);
    public final ModeSetting styleSetting = new ModeSetting("Mode", "Old", "Panel", "New", "Neverlose").withDefault("Old");
    public final ModeSetting niloreTheme = new ModeSetting("Theme", "Light", "Dark")
            .withVisibility(() -> this.styleSetting.is("Nilore"));

    public ClickGuiModule() {
        super("ClickGui", Category.RENDER, 344);
    }

    @Override
    protected void onEnable() {
        try {
            if (this.styleSetting.is("Old")) {
                mc.setScreen(new OldClickGui());
            } else if (this.styleSetting.is("Panel")) {
                mc.setScreen(PanelClickGui.panelClickGui);
            } else if (this.styleSetting.is("Neverlose")) {
                mc.setScreen(NeverloseClickGui.instance);
            } else if (this.styleSetting.is("Nilore")) {
                mc.setScreen(NiloreClickGui.instance);
            } else {
                mc.setScreen(new NewClickGui());
            }
            LOGGER.info("ClickGUI opened successfully");
        } catch (Exception exception) {
            LOGGER.error("Error opening ClickGUI", exception);
        } finally {
            this.setEnabled(false);
        }
    }
}
