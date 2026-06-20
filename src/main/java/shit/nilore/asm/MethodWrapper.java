package shit.nilore.asm;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.Type;

/**
 * Accumulator for the (receiver, args) tuple captured at a wrapped invoke site, paired with a
 * cached {@link MethodHandle} so the wrapper can re-invoke the original implementation on
 * demand. The instances are short-lived — one per wrapped call.
 */
public final class MethodWrapper {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final Map<String, MethodHandle> CACHE = new ConcurrentHashMap<>();

    private final MethodHandle handle;
    private final List<Object> params = new LinkedList<>();

    private MethodWrapper(MethodHandle handle) {
        this.handle = handle;
    }

    public static MethodWrapper getInstance(String classOwner, String methodName, String methodDesc) throws Exception {
        String key = classOwner + "/" + methodName + methodDesc;
        MethodHandle cached = CACHE.get(key);
        if (cached != null) {
            return new MethodWrapper(cached);
        }
        Class<?> clazz = Class.forName(classOwner.replace('/', '.'), false,
                Thread.currentThread().getContextClassLoader());
        MethodHandle handle = lookup(clazz, methodName, methodDesc);
        if (handle == null) {
            throw new NoSuchMethodException("Method " + methodName + methodDesc + " not found on " + classOwner);
        }
        CACHE.put(key, handle);
        return new MethodWrapper(handle);
    }

    private static MethodHandle lookup(Class<?> clazz, String methodName, String methodDesc) throws Exception {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName) && Type.getMethodDescriptor(method).equals(methodDesc)) {
                method.setAccessible(true);
                return LOOKUP.unreflect(method);
            }
        }
        return null;
    }

    public List<Object> getMethodParams() {
        return params;
    }

    /**
     * Bytecode entry point: pushes one argument onto the wrapper. Args are accumulated in reverse
     * (the call site pushes them last-arg first), so we always insert at index 0 — the final
     * list ends up in original calling order.
     */
    public MethodWrapper addParam(Object param) {
        params.add(0, param);
        return this;
    }

    public Object call(Object instance) throws Throwable {
        if (instance == null) {
            if (params.isEmpty()) {
                return handle.invoke();
            }
            return handle.invokeWithArguments(params);
        }
        if (params.isEmpty()) {
            return handle.invoke(instance);
        }
        params.add(0, instance);
        return handle.invokeWithArguments(params);
    }
}
