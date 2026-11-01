package client.nilore.modules.impl.render;

import net.minecraft.core.BlockPos;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.RenderEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.event.impl.WorldChangeEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.render.terrain.TerrainLodRenderer;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.math.FastMath;

/**
 *
 * <ul>
 * </ul>
 *
 */
public class BetterFPS extends Module {
    public static BetterFPS INSTANCE;

    private final BooleanSetting fastMath = new BooleanSetting("Fast Math", true);

    private final BooleanSetting terrainLod = new BooleanSetting("Terrain LOD", false);

    private final NumberSetting lodStart = new NumberSetting("LOD Start", 96, 32, 512, 8,
            () -> (Boolean) this.terrainLod.getValue());

    private final NumberSetting lodTier1 = new NumberSetting("LOD Tier 1 End", 144, 48, 768, 8,
            () -> (Boolean) this.terrainLod.getValue());

    private final NumberSetting lodTier2 = new NumberSetting("LOD Tier 2 End", 192, 64, 1024, 8,
            () -> (Boolean) this.terrainLod.getValue());

    public BetterFPS() {
        super("BetterFPS", Category.RENDER);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.syncFastMath();
        super.onEnable();
    }

    @Override
    public void onDisable() {
        this.syncFastMath();
        TerrainLodRenderer.reset();
        super.onDisable();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        this.syncFastMath();
    }

    @EventTarget
    public void onWorldChange(WorldChangeEvent event) {
        TerrainLodRenderer.reset();
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (!this.terrainLodEnabled()) {
            return;
        }
        TerrainLodRenderer.render(event.poseStack(), startDistance(), tier1End(), tier2End());
    }

    private void syncFastMath() {
        FastMath.setEnabled(this.isEnabled() && (Boolean) this.fastMath.getValue());
    }

    public boolean terrainLodEnabled() {
        return this.isEnabled() && (Boolean) this.terrainLod.getValue();
    }

    public boolean covers(BlockPos origin, double distanceSq) {
        return this.terrainLodEnabled()
                && TerrainLodRenderer.shouldReplace(origin, distanceSq, startDistance());
    }

    public static double startDistance() {
        return INSTANCE == null ? 0.0 : INSTANCE.lodStart.getValue().doubleValue();
    }

    public static double tier1End() {
        return INSTANCE == null ? 0.0 : INSTANCE.lodTier1.getValue().doubleValue();
    }

    public static double tier2End() {
        return INSTANCE == null ? 0.0 : INSTANCE.lodTier2.getValue().doubleValue();
    }

    @Override
    public String getSuffix() {
        if (!this.terrainLodEnabled()) {
            return null;
        }
        return Integer.toString(this.lodStart.getValue().intValue());
    }
}
