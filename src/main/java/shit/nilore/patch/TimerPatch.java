package shit.nilore.patch;

import asm.patchify.annotation.Overwrite;
import asm.patchify.annotation.Patch;
import net.minecraft.client.Timer;
import shit.nilore.NiloreClient;
import shit.nilore.utils.misc.ReflectionUtil;

@Patch(Timer.class)
public class TimerPatch {
    @Overwrite(method = "advanceTime", desc = "(J)I")
    public static int overwriteAdvanceTime(Timer timer, long currentMs) {
        long lastMs = (Long) ReflectionUtil.getStaticField(timer, "lastMs", "net/minecraft/client/Timer");
        float msPerTick = (Float) ReflectionUtil.getStaticField(timer, "msPerTick", "net/minecraft/client/Timer");
        if (NiloreClient.serverTickRate == 1.0f) {
            timer.tickDelta = (float) (currentMs - lastMs) / msPerTick;
        } else {
            timer.tickDelta = (float) (currentMs - lastMs) / msPerTick * NiloreClient.serverTickRate;
        }
        ReflectionUtil.setInstanceField(timer, currentMs, "lastMs", "net/minecraft/client/Timer");
        timer.partialTick = timer.partialTick + timer.tickDelta;
        int wholeTicks = (int) timer.partialTick;
        timer.partialTick -= wholeTicks;
        return wholeTicks;
    }
}
