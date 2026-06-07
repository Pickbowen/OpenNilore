package shit.lizz.utils.misc;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadPool {
    private static class ExecutorFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "Lizz-Executor-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }

    private static class SchedulerFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "Lizz-Scheduler-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3, new SchedulerFactory());
    public static ExecutorService executor = Executors.newCachedThreadPool(new ExecutorFactory());

    public static void scheduleAtFixedRate(Runnable runnable, long initial, long period, TimeUnit unit) {
        scheduler.scheduleAtFixedRate(runnable, initial, period, unit);
    }

    public static ScheduledFuture<?> scheduleWithDelay(Runnable runnable, long delay, TimeUnit unit) {
        return scheduler.schedule(runnable, delay, unit);
    }

    public static void submit(Runnable runnable) {
        executor.execute(runnable);
    }
}
