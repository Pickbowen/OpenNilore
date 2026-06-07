package shit.lizz.utils.misc;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import shit.lizz.exception.SilentException;
import sun.misc.Unsafe;

public class UnsafeUtil {
    private static final Unsafe unsafe;
    public static final MethodHandles.Lookup lookup = MethodHandles.lookup();

    static {
        Unsafe u = null;
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            u = (Unsafe) theUnsafe.get(null);
        } catch (Throwable t) {
            // ignore
        }
        unsafe = u;
    }

    public static MethodHandles.Lookup getLookup() {
        return lookup;
    }

    public static void makeAccessible(AccessibleObject accessibleObject) {
        setAccessible(accessibleObject, true);
    }

    public static void setAccessible(AccessibleObject accessibleObject, boolean flag) {
        try {
            accessibleObject.setAccessible(flag);
        } catch (Throwable t) {
            // best-effort, ignore
        }
    }

    public static Field getField(Class<?> clazz, String name) {
        for (Field field : getFields(clazz)) {
            if (name.equals(field.getName())) {
                return field;
            }
        }
        return null;
    }

    public static Field[] getFields(Class<?> clazz) {
        try {
            return clazz.getDeclaredFields();
        } catch (Throwable t) {
            return new Field[0];
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T invoke(Object object, Method method, Object... args) {
        try {
            if (Modifier.isStatic(method.getModifiers())) {
                return (T) lookup.unreflect(method).invokeWithArguments(args);
            }
            return (T) lookup.unreflect(method).bindTo(object).invokeWithArguments(args);
        } catch (Throwable t) {
            throw new SilentException();
        }
    }

    public static Class<?> defineClass(String name, byte[] bytes, ClassLoader loader) {
        return null;
    }

    public static Unsafe getUnsafe() {
        return unsafe;
    }
}
