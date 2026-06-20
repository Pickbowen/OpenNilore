package shit.nilore.event;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class EventBus {
    private static final Logger LOGGER = LogManager.getLogger(EventBus.class);
    private final Map<Class<? extends EventMarker>, List<ListenerEntry>> listeners = new HashMap<>();

    public record ListenerEntry(Object listener, Method method, byte priority) {
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
        for (List<ListenerEntry> list : listeners.values()) {
            list.removeIf(e -> e.listener().equals(object));
        }
    }

    public void unregisterForClass(Object object, Class<? extends EventMarker> clazz) {
        if (listeners.containsKey(clazz)) {
            listeners.get(clazz).removeIf(e -> e.listener().equals(object));
        }
    }

    private void addListener(Method method, Object object) {
        @SuppressWarnings("unchecked")
        Class<? extends EventMarker> clazz = (Class<? extends EventMarker>) method.getParameterTypes()[0];
        ListenerEntry entry = new ListenerEntry(object, method, method.getAnnotation(EventTarget.class).value());
        if (!entry.method().isAccessible()) {
            entry.method().setAccessible(true);
        }
        List<ListenerEntry> list = listeners.computeIfAbsent(clazz, k -> new CopyOnWriteArrayList<>());
        if (!list.contains(entry)) {
            list.add(entry);
            sortByPriority(clazz);
        }
    }

    public void callEventForClass(Class<? extends EventMarker> clazz) {
        Iterator<Map.Entry<Class<? extends EventMarker>, List<ListenerEntry>>> iterator = listeners.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getKey().equals(clazz)) {
                iterator.remove();
                break;
            }
        }
    }

    private void sortByPriority(Class<? extends EventMarker> clazz) {
        List<ListenerEntry> existing = listeners.get(clazz);
        CopyOnWriteArrayList<ListenerEntry> sorted = new CopyOnWriteArrayList<>();
        for (byte priority : EventPriority.PRIORITIES) {
            for (ListenerEntry entry : existing) {
                if (entry.priority() == priority) {
                    sorted.add(entry);
                }
            }
        }
        listeners.put(clazz, sorted);
    }

    private boolean isValidListener(Method method) {
        return method.getParameterTypes().length == 1 && method.isAnnotationPresent(EventTarget.class);
    }

    private boolean isListenerForClass(Method method, Class<? extends EventMarker> clazz) {
        return isValidListener(method) && method.getParameterTypes()[0].equals(clazz);
    }

    public EventMarker call(EventMarker eventMarker) {
        List<ListenerEntry> list = listeners.get(eventMarker.getClass());
        if (list == null) return eventMarker;
        if (eventMarker instanceof AbstractCancellable abstractCancellable) {
            for (ListenerEntry entry : list) {
                dispatchToListener(entry, eventMarker);
                if (abstractCancellable.isCancelled()) break;
            }
        } else {
            for (ListenerEntry entry : list) {
                dispatchToListener(entry, eventMarker);
            }
        }
        return eventMarker;
    }

    private void dispatchToListener(ListenerEntry entry, EventMarker eventMarker) {
        try {
            entry.method().invoke(entry.listener(), eventMarker);
        } catch (InvocationTargetException e) {
            LOGGER.error("invocation target {} {} {}", entry.listener, entry.method, e);
            e.printStackTrace();
        } catch (Exception e) {
            LOGGER.error("{} {}", entry.listener, entry.method);
            e.printStackTrace();
        }
    }
}
