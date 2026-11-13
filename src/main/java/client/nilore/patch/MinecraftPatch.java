package client.nilore.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import asm.patchify.annotation.Slice;
import asm.patchify.annotation.WrapInvoke;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import client.nilore.ClientBase;
import client.nilore.NiloreClient;
import client.nilore.asm.Invocation;
import client.nilore.event.impl.DisconnectEvent;
import client.nilore.event.impl.PostMotionEvent;
import client.nilore.event.impl.PreMotionEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.modules.impl.movement.NoSlow;
import client.nilore.modules.impl.render.ESP;
import client.nilore.modules.impl.world.PacketBreak;
import client.nilore.render.Renderer;

@Patch(Minecraft.class)
public class MinecraftPatch {
    public static volatile boolean initialized = false;
    private static HitResult savedHitResult;

    public static void markInitialized() {
        initialized = true;
    }

    @Inject(method = "tick", desc = "()V")
    public static void onTick(Minecraft minecraft, CallbackInfo callbackInfo) throws Throwable {
        if (!initialized) {
            synchronized (MinecraftPatch.class) {
                if (!initialized) {
                    ClientBase.mc = NiloreClient.getMcInstance();
                    ClientBase.isLoading = true;
                    ModList.get().getMods().removeIf(modInfo -> modInfo.getModId().equals("hey"));
                    List<IModFileInfo> toRemove = new ArrayList<>();
                    for (IModFileInfo modFile : ModList.get().getModFiles()) {
                        for (IModInfo modInfo : modFile.getMods()) {
                            if (modInfo.getModId().equals("hey")) {
                                toRemove.add(modFile);
                            }
                        }
                    }
                    ModList.get().getModFiles().removeAll(toRemove);
                    new NiloreClient();
                    initialized = true;
                }
            }
        }
        if (NiloreClient.isReady()) {
            NiloreClient.serverTickRate = 1.0f;
            ClientBase.yaw = minecraft.player.getYRot();
            NiloreClient.instance.getEventBus().call(new TickEvent());
        }
    }

    @Inject(method = "tick", desc = "()V", at = @At(At.Type.TAIL))
    public static void onTickPost(Minecraft minecraft, CallbackInfo callbackInfo) throws Throwable {
        if (NiloreClient.isReady()) {
            NiloreClient.instance.getEventBus().call(new PostMotionEvent());
        }
    }

    @Inject(method = "close", desc = "()V", at = @At(At.Type.HEAD))
    public static void onClose(Minecraft minecraft, CallbackInfo callbackInfo) {
        NiloreClient.getInstance().shutdown();
    }

    @Inject(method = "setLevel", desc = "(Lnet/minecraft/client/multiplayer/ClientLevel;)V")
    public static void onSetLevel(ClientLevel level, CallbackInfo callbackInfo) {
        if (NiloreClient.isReady()) {
            NiloreClient.getInstance().getEventBus().call(new DisconnectEvent());
        }
    }

    @Inject(
            method = "handleKeybinds",
            desc = "()V",
            at = @At(value = At.Type.BEFORE_INVOKE, method = "net/minecraft/client/player/LocalPlayer/isUsingItem", desc = "()Z"),
            slice = @Slice(startIndex = 1, endIndex = 1)
    )
    public static void onHandleKeybinds(Minecraft minecraft, CallbackInfo callbackInfo) {
        if (NiloreClient.isReady()) {
            PreMotionEvent event = new PreMotionEvent();
            NiloreClient.getInstance().getEventBus().call(event);
            if (event.isCancelled()) {
                callbackInfo.cancel();
            }
        }
    }

    /**
     * 静默旋转会把原版 pick 拉到正在挖的方块上, 于是每次点击的 START 都指向旧目标, 换不了方块。
     * 这里在点击入口把 hitResult 换成客户端真实准星选中的方块, 后续 startDestroyBlock /
     * 预测窗口 / 本次 tick 的 continueDestroyBlock 就都跟着真实视角走。
     * 不还原: 下一帧 GameRenderer.pick 会重新计算, 而这一 tick 内保持真实命中才不会让
     * continueDestroyBlock 又按旧目标补一次 START。
     */
    @Inject(method = "startAttack", desc = "()Z", at = @At(At.Type.HEAD))
    public static void onStartAttackPre(Minecraft minecraft, CallbackInfo callbackInfo) {
        if (!NiloreClient.isReady() || PacketBreak.INSTANCE == null || !PacketBreak.INSTANCE.isEnabled()) {
            return;
        }
        BlockHitResult clientHit = PacketBreak.INSTANCE.clientViewBlockHit();
        if (clientHit == null) {
            return;
        }
        if (!(minecraft.hitResult instanceof BlockHitResult blockHit)
                || blockHit.getBlockPos().equals(clientHit.getBlockPos())) {
            return;
        }
        minecraft.hitResult = clientHit;
    }

    @Inject(method = "startUseItem", desc = "()V", at = @At(At.Type.HEAD))
    public static void onStartUseItemPre(Minecraft minecraft, CallbackInfo callbackInfo) {
        if (NoSlow.isBlocking(minecraft)) {
            savedHitResult = minecraft.hitResult;
            Vec3 location = savedHitResult.getLocation();
            minecraft.hitResult = BlockHitResult.miss(location, Direction.DOWN, BlockPos.containing(location));
        }
    }

    @Inject(method = "startUseItem", desc = "()V", at = @At(At.Type.TAIL))
    public static void onStartUseItemPost(Minecraft minecraft, CallbackInfo callbackInfo) {
        if (savedHitResult != null) {
            minecraft.hitResult = savedHitResult;
            savedHitResult = null;
        }
    }

    @WrapInvoke(
            method = "shouldEntityAppearGlowing",
            desc = "(Lnet/minecraft/world/entity/Entity;)Z",
            target = "net/minecraft/world/entity/Entity/isCurrentlyGlowing",
            targetDesc = "()Z"
    )
    public static boolean onShouldEntityGlow(Minecraft minecraft, Entity entity, Invocation<Entity, Boolean> original) throws Exception {
        if (NiloreClient.isReady()
                && NiloreClient.instance.getModuleManager() != null
                && ESP.INSTANCE != null
                && ESP.INSTANCE.isGlowing(entity)) {
            return true;
        }
        return original.call();
    }

    @Inject(method = "resizeDisplay", desc = "()V", at = @At(At.Type.TAIL))
    public static void onResizeDisplay(Minecraft minecraft, CallbackInfo callbackInfo) {
        Renderer.setGuiScaleVerified((float) minecraft.getWindow().getGuiScale());
    }
}
