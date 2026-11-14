package client.nilore.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import client.nilore.NiloreClient;
import client.nilore.modules.impl.render.FirstPersonModel;

/**
 * FirstPersonModel 的真正生效点: 把 {@code PlayerModel.head/hat} 藏掉。
 *
 * <h2>为什么需要这个 patch</h2>
 * <p>{@code FirstPersonModel.onRender} 在调 {@code EntityRenderDispatcher.render} 之前就把
 * {@code model.head.visible} 置了 false, 但那条路走不通 ——
 * {@code PlayerRenderer.render} 的第一件事是 {@code this.setModelProperties(player)}, 而这个
 * 方法体内有:</p>
 * <pre>
 *   playermodel.setAllVisible(true);                                   // head.visible 被改回 true
 *   playermodel.hat.visible = !player.getItemBySlot(HEAD).isEmpty();   // hat 被改回 true
 * </pre>
 * <p>于是外部设置的 visible 一律被覆盖: 头皮肤照画, 相机又在眼位也就是头方块内部,
 * 不剔背面的 {@code entityCutoutNoCull} 让内壁铺满整个视野 —— 现象就是"第一人称只能看见
 * 自己的头的内部", 且调 Hide Head 完全无效。</p>
 *
 * <h2>为什么注入 setModelProperties 的 TAIL</h2>
 * <p>1.20.1 官方映射: {@code void setModelProperties(AbstractClientPlayer)} (PlayerRenderer),
 * 方法体就是那 34 行 visible / ArmPose 赋值。注入它的每个 return 之前, 意味着
 * {@code setAllVisible} 和 {@code hat.visible = ...} 都已经跑完, 我们最后一次写入不会被覆盖,
 * 而且只影响本次渲染 (下一帧 setModelProperties 会重新算)。</p>
 *
 * <p>判定仍由 {@link FirstPersonModel#skipOwnHeadLayers} 把关: 只在自己 + 第一人称 +
 * 模块开启 + Hide Head 开启时生效; 第三人称、别的玩家、别的模块都不受影响。</p>
 */
@Patch(PlayerRenderer.class)
public class PlayerRendererPatch {

    @Inject(
            method = "setModelProperties",
            desc = "(Lnet/minecraft/client/player/AbstractClientPlayer;)V",
            at = @At(At.Type.TAIL)
    )
    public static void afterSetModelProperties(
            PlayerRenderer renderer,
            AbstractClientPlayer player,
            CallbackInfo callbackInfo) {
        if (!NiloreClient.isReady()) {
            return;
        }
        if (!FirstPersonModel.skipOwnHeadLayers(player)) {
            return;
        }
        // 直接写回目标部件: 此刻 setAllVisible(true) 与 hat 赋值都已执行完毕。
        PlayerModel<AbstractClientPlayer> model = renderer.getModel();
        model.head.visible = false;
        model.hat.visible = false;
    }
}
