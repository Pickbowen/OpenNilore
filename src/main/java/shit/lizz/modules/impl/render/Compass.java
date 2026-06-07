package shit.lizz.modules.impl.render;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import shit.lizz.event.impl.GlRenderEvent;
import shit.lizz.event.impl.RenderEvent;
import shit.lizz.event.impl.TickEvent;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.modules.impl.movement.Scaffold;
import shit.lizz.render.DrawContext;
import shit.lizz.render.Paint;
import shit.lizz.render.Path;
import shit.lizz.render.ShadowFactory;
import shit.lizz.render.ShadowMode;
import shit.lizz.settings.impl.BooleanSetting;
import shit.lizz.event.EventTarget;

public class Compass
extends Module {
    private final BooleanSetting compassOnly = new BooleanSetting("Compass Only", true);
    private final BooleanSetting noPlayerOnly = new BooleanSetting("No Player Only", true);
    private boolean hasCompassItemCached = false;
    private BlockPos spawnPosition;
    private float renderYaw;
    private double renderX;
    private double renderZ;

    public Compass() {
        super("Compass", Category.RENDER);
    }

    private BlockPos getSpawnPosition() {
        if (mc.level == null) {
            return null;
        }
        return mc.level.dimensionType().natural() ? mc.level.getSharedSpawnPos() : null;
    }

    private boolean hasPlayer() {
        if (mc.level == null) {
            return false;
        }
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || !(entity instanceof Player)) continue;
            return true;
        }
        return false;
    }

    private boolean hasCompassItem() {
        if (mc.player == null) {
            return false;
        }
        for (ItemStack itemStack : mc.player.getInventory().items) {
            if (itemStack.getItem() != Items.COMPASS) continue;
            return true;
        }
        return mc.player.getInventory().offhand.get(0).getItem() == Items.COMPASS;
    }

    @EventTarget
    public void onTick(TickEvent tickEvent) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        this.hasCompassItemCached = this.hasCompassItem();
        this.spawnPosition = this.getSpawnPosition();
    }

    @EventTarget
    public void onRender(RenderEvent renderEvent) {
        if (mc.player == null) {
            return;
        }
        this.renderX = Mth.lerp(renderEvent.partialTick(), mc.player.xOld, mc.player.getX());
        this.renderZ = Mth.lerp(renderEvent.partialTick(), mc.player.zOld, mc.player.getZ());
        this.renderYaw = Mth.lerp(renderEvent.partialTick(), mc.player.yRotO, mc.player.getYRot());
    }

    @EventTarget
    public void onGlRender(GlRenderEvent glRenderEvent) {
        if (mc.player == null) {
            return;
        }
        if (Scaffold.INSTANCE.isEnabled()) {
            return;
        }
        this.draw(glRenderEvent.drawContext());
    }

    private void draw(DrawContext drawContext) {
        if (Scaffold.INSTANCE != null && Scaffold.INSTANCE.isEnabled()) {
            return;
        }
        if (!this.hasCompassItemCached && this.compassOnly.getValue()) {
            return;
        }
        if (this.hasPlayer() && this.noPlayerOnly.getValue()) {
            return;
        }
        if (this.spawnPosition == null) {
            return;
        }
        float angle = (float)(Math.toDegrees(Math.atan2((double)this.spawnPosition.getZ() - this.renderZ, (double)this.spawnPosition.getX() - this.renderX)) - 90.0 - (double)this.renderYaw);
        float centerX = (float)mc.getWindow().getGuiScaledWidth() / 2.0f;
        float centerY = (float)mc.getWindow().getGuiScaledHeight() / 2.0f;
        drawContext.save();
        drawContext.translate(centerX, centerY);
        drawContext.rotate(angle);
        try (Path path = new Path()){
            path.moveTo(-6.0f, -30.0f);
            path.lineTo(0.0f, -36.0f);
            path.lineTo(6.0f, -30.0f);
            try (Paint paint = new Paint()){
                paint.setAntialias(true);
                paint.setColor(-2130706433);
                paint.setStrokeCap(Paint.StrokeCap.STROKE);
                paint.setStrokeWidth(4.0f);
                paint.setShader(ShadowFactory.createColoredShadow(4.0f, 4.0f, ShadowMode.OUTLINE));
                drawContext.drawPath(path, paint);
            }
            try (Paint paint = new Paint()) {
                paint.setAntialias(true);
                paint.setColor(-1);
                paint.setStrokeCap(Paint.StrokeCap.STROKE);
                paint.setStrokeWidth(2.0f);
                drawContext.drawPath(path, paint);
            }
        }
        drawContext.restore();
    }
}