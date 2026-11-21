package client.nilore.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import client.nilore.modules.impl.render.Ambience;

@Patch(Biome.class)
public class BiomePatch {
    @Inject(method = "hasPrecipitation", desc = "()Z", at = @At(At.Type.HEAD))
    public static void onHasPrecipitation(Biome biome, CallbackInfo callbackInfo) {
        if (Ambience.isSnowy()) {
            callbackInfo.result = true;
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = "getPrecipitationAt",
            desc = "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome$Precipitation;",
            at = @At(At.Type.HEAD)
    )
    public static void onGetPrecipitationAt(Biome biome, BlockPos pos, CallbackInfo callbackInfo) {
        if (Ambience.isSnowy()) {
            callbackInfo.result = Biome.Precipitation.SNOW;
            callbackInfo.cancel();
        }
    }
}
