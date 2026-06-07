package shit.lizz.patch;

import asm.patchify.annotation.Overwrite;
import asm.patchify.annotation.Patch;
import net.minecraft.client.Timer;
import shit.lizz.LizzClient;
import shit.lizz.utils.misc.ReflectionUtil;

@Patch(Timer.class)
public class TimerPatch {
    @Overwrite(method = "advanceTime", desc = "(J)I")
    public static int overwriteAdvanceTime(Timer timer, long currentMs) {
        long lastMs = (Long) ReflectionUtil.getStaticField(timer, "lastMs", "net/minecraft/client/Timer");
        float msPerTick = (Float) ReflectionUtil.getStaticField(timer, "msPerTick", "net/minecraft/client/Timer");
        if (LizzClient.serverTickRate == 1.0f) {
            timer.tickDelta = (float) (currentMs - lastMs) / msPerTick;
        } else {
            timer.tickDelta = (float) (currentMs - lastMs) / msPerTick * LizzClient.serverTickRate;
        }
        ReflectionUtil.setInstanceField(timer, currentMs, "lastMs", "net/minecraft/client/Timer");
        timer.partialTick = timer.partialTick + timer.tickDelta;
        int wholeTicks = (int) timer.partialTick;
        timer.partialTick -= wholeTicks;
        return wholeTicks;
    }
}
