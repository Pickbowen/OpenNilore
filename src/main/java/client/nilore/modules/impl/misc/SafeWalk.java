package client.nilore.modules.impl.misc;

import com.mojang.blaze3d.platform.InputConstants;
import client.nilore.event.impl.MotionEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.event.EventTarget;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class SafeWalk
extends Module {
    /** 判定"脚下踩空"时把玩家碰撞盒向内收多少格。越大 = 越早蹲。 */
    private final NumberSetting edgeDistance = new NumberSetting("Edge Distance", 0.3, 0.1, 0.9, 0.05);

    /**
     * 危险消失后还要再按住 sneak 多少 tick 才松开。
     *
     * <p>这不是可调项：0 会让状态一 tick 松一 tick 压（掉落就是从这些空隙里漏出去的），
     * 而 2 tick 足够盖住"位移算完、onGround 还没更新"的那一拍。
     */
    private static final int RELEASE_DELAY = 2;

    /** 本模块当前是否在按住 sneak。 */
    private boolean sneaking;
    /** 危险消失后已经过去的 tick 数。 */
    private int releaseTicks;

    public SafeWalk() {
        super("SafeWalk", Category.MISC);
    }

    public static boolean isOnBlockEdge(float inset) {
        return hasNoSupport(mc.player.getBoundingBox().move(0.0, -0.5, 0.0).inflate(-inset, 0.0, -inset));
    }

    private static boolean hasNoSupport(AABB box) {
        return !mc.level.getCollisions(mc.player, box).iterator().hasNext();
    }

    /**
     * 这一 tick 是否算"危险"。
     *
     * <p>旧实现只看"当前位置的包围盒下面是不是空的"，而那一刻玩家其实已经半个身子悬空了，
     * 于是松手和掉落几乎同时发生——看上去就是"在边缘概率性松开 sneak"。这里改成两段判定：
     * <ol>
     *     <li>当前位置下方无支撑；</li>
     *     <li>按本 tick 的水平速度外推一格后下方无支撑（提前压住，而不是踩空了才反应）。</li>
     * </ol>
     * 再加一条迟滞：已经按住 sneak、且人在下落，就继续算危险，直到真正落地。
     */
    private boolean isDangerous() {
        float inset = this.edgeDistance.getValue().floatValue();
        AABB box = mc.player.getBoundingBox();

        if (hasNoSupport(box.move(0.0, -0.5, 0.0).inflate(-inset, 0.0, -inset))) {
            return true;
        }

        Vec3 motion = mc.player.getDeltaMovement();
        if (motion.horizontalDistanceSqr() > 1.0E-6
                && hasNoSupport(box.move(motion.x, -0.5, motion.z).inflate(-inset, 0.0, -inset))) {
            return true;
        }

        // 已经走出边缘、正在下落：这一拍 onGround 已经是 false，不能据此松手。
        return this.sneaking && !mc.player.onGround() && motion.y <= 0.0;
    }

    @EventTarget
    public void onMotion(MotionEvent motionEvent) {
        if (!motionEvent.isPost() || mc.player == null || mc.level == null) {
            return;
        }
        if (isShiftPhysicallyDown()) {
            // 玩家自己按着蹲键：完全让路，但别把模块状态留成"按着"。
            this.sneaking = false;
            this.releaseTicks = 0;
            return;
        }

        if (this.isDangerous()) {
            this.sneaking = true;
            this.releaseTicks = 0;
        } else if (this.sneaking && ++this.releaseTicks >= RELEASE_DELAY) {
            this.sneaking = false;
            this.releaseTicks = 0;
        }

        mc.options.keyShift.setDown(this.sneaking);
    }

    private static boolean isShiftPhysicallyDown() {
        return InputConstants.isKeyDown(mc.getWindow().getWindow(), mc.options.keyShift.getKey().getValue());
    }

    @Override
    public void onDisable() {
        this.sneaking = false;
        this.releaseTicks = 0;
        mc.options.keyShift.setDown(isShiftPhysicallyDown());
        super.onDisable();
    }
}
