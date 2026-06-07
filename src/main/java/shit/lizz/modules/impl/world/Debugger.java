package shit.lizz.modules.impl.world;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.HashSet;

import shit.lizz.event.impl.DisconnectEvent;
import shit.lizz.modules.Category;
import shit.lizz.modules.Module;
import shit.lizz.utils.misc.ChatUtil;
import shit.lizz.event.EventTarget;

public class Debugger
extends Module {
    public Debugger() {
        super("Debugger", Category.WORLD);
    }

    @EventTarget
    public void onDisconnect(DisconnectEvent disconnectEvent) {
        HashSet<String> suspiciousClasses = new HashSet<>();
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        if (threadMXBean == null) {
            return;
        }
        ThreadInfo[] threads = threadMXBean.dumpAllThreads(false, false);
        int count = 0;
        for (ThreadInfo threadInfo : threads) {
            String threadName = threadInfo.getThreadName();
            StackTraceElement[] stackTrace = threadInfo.getStackTrace();
            if (threadName == null || stackTrace == null) continue;
            for (StackTraceElement stackTraceElement : stackTrace) {
                String className = stackTraceElement.getClassName();
                String fileName = stackTraceElement.getFileName();
                String moduleName = stackTraceElement.getModuleName();
                if (fileName != null || moduleName != null) continue;
                suspiciousClasses.add(className);
                ++count;
            }
        }
        ChatUtil.print("N: " + count + ", Set: ");
        ChatUtil.print("==========================");
        for (String className : suspiciousClasses) {
            ChatUtil.print(className);
        }
        ChatUtil.print("==========================");
    }
}