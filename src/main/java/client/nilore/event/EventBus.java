package client.nilore.event;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class EventBus {
    private static final Logger LOGGER = LogManager.getLogger(EventBus.class);
    private final Map<Class<? extends EventMarker>, ListenerEntry[]> listeners = new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface EventInvoker {
        void invoke(Object listener, EventMarker event);
    }

    public record ListenerEntry(Object listener, Method method, byte priority, EventInvoker invoker) {
        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ListenerEntry entry)) return false;
            return this.priority == entry.priority
                    && this.listener.equals(entry.listener)
                    && this.method.equals(entry.method);
        }

        @Override
        public int hashCode() {
            return (this.listener.hashCode() * 31 + this.method.hashCode()) * 31 + this.priority;
        }
    }

    public void register(Object object) {
        for (Method method : object.getClass().getDeclaredMethods()) {
            if (!isValidListener(method)) continue;
            addListener(method, object);
        }
    }

    public void registerForClass(Object object, Class<? extends EventMarker> clazz) {
        for (Method method : object.getClass().getDeclaredMethods()) {
            if (!isListenerForClass(method, clazz)) continue;
            addListener(method, object);
        }
    }

    public void unregister(Object object) {
        for (Class<? extends EventMarker> clazz : listeners.keySet()) {
            listeners.computeIfPresent(clazz, (key, entries) -> withoutListener(entries, object));
        }
    }

    public void unregisterForClass(Object object, Class<? extends EventMarker> clazz) {
        listeners.computeIfPresent(clazz, (key, entries) -> withoutListener(entries, object));
    }

    private void addListener(Method method, Object object) {
        @SuppressWarnings("unchecked")
        Class<? extends EventMarker> clazz = (Class<? extends EventMarker>) method.getParameterTypes()[0];
        if (!method.canAccess(object)) {
            method.setAccessible(true);
        }
        ListenerEntry entry = new ListenerEntry(object, method, method.getAnnotation(EventTarget.class).value(), createInvoker(method, object));
        listeners.compute(clazz, (key, entries) -> {
            if (entries == null) {
                return new ListenerEntry[]{entry};
            }
            for (ListenerEntry existing : entries) {
                if (existing.equals(entry)) {
                    return entries;
                }
            }
            ListenerEntry[] grown = Arrays.copyOf(entries, entries.length + 1);
            grown[entries.length] = entry;
            return sortByPriority(grown);
        });
    }

    public void callEventForClass(Class<? extends EventMarker> clazz) {
        Iterator<Map.Entry<Class<? extends EventMarker>, ListenerEntry[]>> iterator = listeners.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getKey().equals(clazz)) {
                iterator.remove();
                break;
            }
        }
    }

    private static ListenerEntry[] withoutListener(ListenerEntry[] entries, Object object) {
        int matches = 0;
        for (ListenerEntry entry : entries) {
            if (entry.listener().equals(object)) {
                matches++;
            }
        }
        if (matches == 0) {
            return entries;
        }
        ListenerEntry[] remaining = new ListenerEntry[entries.length - matches];
        int index = 0;
        for (ListenerEntry entry : entries) {
            if (!entry.listener().equals(object)) {
                remaining[index++] = entry;
            }
        }
        return remaining;
    }

    private static ListenerEntry[] sortByPriority(ListenerEntry[] entries) {
        ListenerEntry[] sorted = new ListenerEntry[entries.length];
        int index = 0;
        for (byte priority : EventPriority.PRIORITIES) {
            for (ListenerEntry entry : entries) {
                if (entry.priority() == priority) {
                    sorted[index++] = entry;
                }
            }
        }
        return Arrays.copyOf(sorted, index);
    }

    private boolean isValidListener(Method method) {
        return method.getParameterTypes().length == 1 && method.isAnnotationPresent(EventTarget.class);
    }

    private boolean isListenerForClass(Method method, Class<? extends EventMarker> clazz) {
        return isValidListener(method) && method.getParameterTypes()[0].equals(clazz);
    }

    public EventMarker call(EventMarker eventMarker) {
        ListenerEntry[] entries = listeners.get(eventMarker.getClass());
        if (entries == null) return eventMarker;
        if (eventMarker instanceof AbstractCancellable abstractCancellable) {
            for (int i = 0; i < entries.length; i++) {
                entries[i].invoker().invoke(entries[i].listener(), eventMarker);
                if (abstractCancellable.isCancelled()) break;
            }
        } else {
            for (int i = 0; i < entries.length; i++) {
                entries[i].invoker().invoke(entries[i].listener(), eventMarker);
            }
        }
        return eventMarker;
    }

    private static EventInvoker createInvoker(Method method, Object listener) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(listener.getClass(), MethodHandles.lookup());
            MethodHandle handle = lookup.unreflect(method)
                    .asType(MethodType.methodType(void.class, Object.class, EventMarker.class));
            return (target, event) -> {
                try {
                    handle.invokeExact(target, event);
                } catch (Throwable throwable) {
                    reportInvocationTarget(listener, method, throwable);
                }
            };
        } catch (Throwable throwable) {
            LOGGER.debug("MethodHandle unavailable for {}, using reflection", method, throwable);
            return createReflectionInvoker(method);
        }
    }

    private static EventInvoker createReflectionInvoker(Method method) {
        return (target, event) -> {
            try {
                method.invoke(target, event);
            } catch (InvocationTargetException e) {
                reportInvocationTarget(target, method, e);
            } catch (Exception e) {
                LOGGER.error("{} {}", target, method);
                e.printStackTrace();
            }
        };
    }

    private static void reportInvocationTarget(Object listener, Method method, Throwable throwable) {
        LOGGER.error("invocation target {} {} {}", listener, method, throwable);
        throwable.printStackTrace();
    }
}
