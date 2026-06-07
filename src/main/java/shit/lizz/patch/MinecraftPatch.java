package shit.lizz.patch;

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
import shit.lizz.ClientBase;
import shit.lizz.LizzClient;
import shit.lizz.asm.Invocation;
import shit.lizz.event.impl.DisconnectEvent;
import shit.lizz.event.impl.PostMotionEvent;
import shit.lizz.event.impl.PreMotionEvent;
import shit.lizz.event.impl.TickEvent;
import shit.lizz.modules.impl.movement.NoSlow;
import shit.lizz.modules.impl.render.ESP;
import shit.lizz.render.Renderer;

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
                    ClientBase.mc = LizzClient.getMcInstance();
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
                    new LizzClient();
                    initialized = true;
                }
            }
        }
        if (LizzClient.isReady()) {
            LizzClient.serverTickRate = 1.0f;
            ClientBase.yaw = minecraft.player.getYRot();
            LizzClient.instance.getEventBus().call(new TickEvent());
        }
    }

    @Inject(method = "tick", desc = "()V", at = @At(At.Type.TAIL))
    public static void onTickPost(Minecraft minecraft, CallbackInfo callbackInfo) throws Throwable {
        if (LizzClient.isReady()) {
            LizzClient.instance.getEventBus().call(new PostMotionEvent());
        }
    }

    @Inject(method = "close", desc = "()V", at = @At(At.Type.HEAD))
    public static void onClose(Minecraft minecraft, CallbackInfo callbackInfo) {
        LizzClient.getInstance().shutdown();
    }

    @Inject(method = "setLevel", desc = "(Lnet/minecraft/client/multiplayer/ClientLevel;)V")
    public static void onSetLevel(ClientLevel level, CallbackInfo callbackInfo) {
        if (LizzClient.isReady()) {
            LizzClient.getInstance().getEventBus().call(new DisconnectEvent());
        }
    }

    @Inject(
            method = "handleKeybinds",
            desc = "()V",
            at = @At(value = At.Type.BEFORE_INVOKE, method = "net/minecraft/client/player/LocalPlayer/isUsingItem", desc = "()Z"),
            slice = @Slice(startIndex = 1, endIndex = 1)
    )
    public static void onHandleKeybinds(Minecraft minecraft, CallbackInfo callbackInfo) {
        if (LizzClient.isReady()) {
            PreMotionEvent event = new PreMotionEvent();
            LizzClient.getInstance().getEventBus().call(event);
            if (event.isCancelled()) {
                callbackInfo.cancel();
            }
        }
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
        if (LizzClient.isReady()
                && LizzClient.instance.getModuleManager() != null
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
