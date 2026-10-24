package client.nilore.modules.impl.movement;

/**
 * 碰撞速度计算的最小输入面。
 *
 * <p>{@link SpeedGrimCollide} 只依赖三件事: 这一 tick 接触到的实体数量, 以及沿 x/z 的
 * 运动分量。把它们收进一个不可变的 {@code Collision} 里, 预测逻辑就不需要再回头去查
 * 世界状态 —— 纯函数的好处是它可以被单测、被回放, 并且永远不可能因为实体列表变化
 * 而在两次调用之间给出不同的答案。
 */
public interface SpeedAccess {

    /**
     * 一次碰撞快照。
     *
     * @param contacts 这一 tick 与玩家 AABB 相交的可推挤实体数量
     * @param motionX  玩家当前水平运动 x 分量(格/tick)
     * @param motionZ  玩家当前水平运动 z 分量(格/tick)
     */
    record Collision(int contacts, double motionX, double motionZ) {
        public static Collision none() {
            return new Collision(0, 0.0, 0.0);
        }

        public static Collision of(int contacts, double motionX, double motionZ) {
            return new Collision(contacts, motionX, motionZ);
        }

        /** 水平速度平方, 供调用方复用而不必重复乘。 */
        public double horizontalSquared() {
            return this.motionX * this.motionX + this.motionZ * this.motionZ;
        }
    }
}
