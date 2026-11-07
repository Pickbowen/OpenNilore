package client.nilore.event.impl;

import lombok.Getter;
import lombok.Setter;
import lombok.Generated;
import client.nilore.event.EventMarker;

/**
 * 移动输入事件，在 {@code KeyboardInput.tick} 末尾发出，改完后由 patch 写回
 * {@code Input.forwardImpulse / leftImpulse / jumping}。
 *
 * <p>{@code jumping} 就是 {@code Input.jumping}（跳跃键），不是疾跑状态。
 * 旧版本把这一位命名成 {@code sprinting}，导致 {@code setSprinting(false)}
 * 实际干的是"禁止这一拍起跳"，语义和名字对不上，已修正。
 */
public class StrafeEvent
implements EventMarker {
    @Getter @Setter
    private float forward;
    @Getter @Setter
    private float strafe;
    @Getter @Setter
    private boolean jumping;
    /** 八向 strafe 是否已经被某个模块处理过；避免 Scaffold 与 RotationHandler 重复映射。 */
    @Getter @Setter
    private boolean moveFixed;

    @Generated
    public StrafeEvent(float forward, float strafe, boolean jumping) {
        this.forward = forward;
        this.strafe = strafe;
        this.jumping = jumping;
    }
}
