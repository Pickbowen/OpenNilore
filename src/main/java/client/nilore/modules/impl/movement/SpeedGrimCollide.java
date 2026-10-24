package client.nilore.modules.impl.movement;

/**
 * 碰撞速度增量: 只补到"这条轴还能吃下多少", 而不是无条件地塞一个固定 boost。
 *
 * <p>服务端的预测在实体碰撞这一块是保守的 —— 它把玩家当成独立个体来积分, 所以贴着别的
 * 实体走时, 客户端实际收到的位移会低于服务端算出来的上限。这里做的就是把差值解析地算
 * 出来: 先把当前水平速度投影到移动方向上({@code along}), 再解出"投影之后正好落在
 * {@code limit} 圆的边界上"所需的额外步长。结果永远非负且不超过 {@code remaining},
 * 因此叠加之后的总水平速度在几何上不可能超出 {@code limit}, 服务端的预测也就不会看到
 * 一个需要解释的多余位移。
 *
 * <p>{@code 4} 的接触数量上限来自服务端自己: 同一 tick 内参与推挤判定的实体数量在
 * 碰撞盒重叠时最多是 4 个(四个象限), 再多也不会改变位移。
 */
final class SpeedGrimCollide {
    /** 单个 tick 允许注入的最大水平增量(格/tick); 0.08 已经接近步行速度的 1/3。 */
    private static final double MAX_BOOST = 0.08;
    /** 速度上限的校验上界; 与模块滑块一致。 */
    private static final double MAX_LIMIT = 1.0;
    /** 服务端碰撞推挤判定同一 tick 内会考虑的实体上限。 */
    private static final int MAX_CONTACTS = 4;

    private SpeedGrimCollide() { }

    static double increment(SpeedAccess.Collision collision, float radians, double boost, double limit) {
        if (collision == null || collision.contacts() <= 0 || !Float.isFinite(radians)
                || !Double.isFinite(collision.motionX()) || !Double.isFinite(collision.motionZ())
                || !Double.isFinite(boost) || boost <= 0 || boost > MAX_BOOST
                || !Double.isFinite(limit) || limit <= 0 || limit > MAX_LIMIT) return 0;
        double x = collision.motionX(), z = collision.motionZ();
        double squared = x * x + z * z;
        if (squared >= limit * limit) return 0;
        double along = -Math.sin(radians) * x + Math.cos(radians) * z;
        double remaining = -along + Math.sqrt(along * along + limit * limit - squared);
        return Math.max(0, Math.min(boost * Math.min(MAX_CONTACTS, collision.contacts()), remaining));
    }

    /** 供模块读取的可调上限, 避免把常量复制到调用点。 */
    static double maxBoost() {
        return MAX_BOOST;
    }
}
