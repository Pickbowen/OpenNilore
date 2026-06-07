package shit.lizz.utils.misc;

import com.mojang.blaze3d.pipeline.RenderTarget;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongRBTreeMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLongImmutablePair;
import it.unimi.dsi.fastutil.objects.ObjectLongPair;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.BrewingStandMenu;
import sun.misc.Unsafe;
import shit.lizz.ClientBase;
import shit.lizz.LizzClient;

public final class ReflectionUtil {
    private static final MethodHandles.Lookup lookup = MethodHandles.lookup();
    private static final Object2ObjectMap<String, MethodHandles.Lookup> lookupCache = new Object2ObjectOpenHashMap<>(500);
    private static final Object2ObjectMap<String, Class<?>> classCache = new Object2ObjectOpenHashMap<>(500);
    private static final Object2ObjectMap<String, VarHandle> varHandleCache = new Object2ObjectOpenHashMap<>(1000);
    private static final Object2LongMap<String> offsetCache = new Object2LongRBTreeMap<>();
    private static final Object2ObjectMap<String, ObjectLongPair<Object>> staticFieldCache = new Object2ObjectOpenHashMap<>();
    private static final Unsafe unsafe;

    public static class ReflectionException extends RuntimeException {
        public ReflectionException(Throwable cause) {
            super(cause);
        }
    }

    static {
        Unsafe u;
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            u = (Unsafe) field.get(null);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
        unsafe = u;
    }

    public static int getClassRedefinedCount(Class<?> clazz) {
        if (clazz == null) return 0;
        try {
            Field field = Class.class.getDeclaredField("classRedefinedCount");
            UnsafeUtil.makeAccessible(field);
            return field.getInt(clazz);
        } catch (Throwable throwable) {
            throw new Error(throwable);
        }
    }

    public static void setClassRedefinedCount(Class<?> clazz, int count) {
        if (clazz == null) return;
        try {
            Field field = Class.class.getDeclaredField("classRedefinedCount");
            UnsafeUtil.makeAccessible(field);
            field.setInt(clazz, count);
        } catch (Throwable throwable) {
            throw new Error(throwable);
        }
    }

    public static void incrementClassRedefinedCount(Class<?> clazz, int delta) {
        if (clazz == null) return;
        try {
            Field field = Class.class.getDeclaredField("classRedefinedCount");
            UnsafeUtil.makeAccessible(field);
            field.setInt(clazz, field.getInt(clazz) + delta);
        } catch (Throwable throwable) {
            throw new Error(throwable);
        }
    }

    public static void setJumpDelay(int delay) {
        if (ClientBase.mc == null || ClientBase.mc.player == null) return;
        setJumpDelay(ClientBase.mc.player, delay);
    }

    public static void setJumpDelay(LivingEntity livingEntity, int delay) {
        if (livingEntity == null) return;
        try {
            Field field = findField(livingEntity.getClass(), "noJumpDelay", "f_20954_");
            field.setInt(livingEntity, delay);
        } catch (Exception ex) {
            ClientBase.logger.error("Failed to set noJumpDelay field", ex);
        }
    }

    public static void setRightClickDelay(int delay) {
        if (ClientBase.mc == null) return;
        try {
            Field field = findField(ClientBase.mc.getClass(), "rightClickDelay", "f_91011_");
            field.setInt(ClientBase.mc, delay);
        } catch (Exception ex) {
            ClientBase.logger.error("Failed to set rightClickDelay field", ex);
        }
    }

    public static float getYRot(ServerboundMovePlayerPacket packet) {
        if (ClientBase.mc.gameMode == null) return 0.0f;
        Field field = findField(packet.getClass(), LizzClient.isMCPMapped ? "f_134121_" : "yRot");
        try {
            return field.getFloat(packet);
        } catch (Exception ex) {
            ClientBase.logger.error("Failed to get yrot field", ex);
            return 0.0f;
        }
    }

    public static float getXRot(ServerboundMovePlayerPacket packet) {
        if (ClientBase.mc.gameMode == null) return 0.0f;
        Field field = findField(packet.getClass(), LizzClient.isMCPMapped ? "f_134122_" : "xRot");
        try {
            return field.getFloat(packet);
        } catch (Exception ex) {
            ClientBase.logger.error("Failed to get xrot field", ex);
            return 0.0f;
        }
    }

    public static void setYRot(ServerboundMovePlayerPacket packet, float yaw) {
        if (ClientBase.mc.gameMode == null) return;
        Field field = findField(packet.getClass(), LizzClient.isMCPMapped ? "f_134121_" : "yRot");
        try {
            field.setFloat(packet, yaw);
        } catch (Exception ex) {
            ClientBase.logger.error("Failed to set yrot field", ex);
        }
    }

    public static void setDepthBufferId(RenderTarget renderTarget, int depthBufferId) {
        Field field = findField(renderTarget.getClass(), LizzClient.isMCPMapped ? "f_83924_" : "depthBufferId");
        try {
            field.setInt(renderTarget, depthBufferId);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public static void setXRot(ServerboundMovePlayerPacket packet, float pitch) {
        if (ClientBase.mc.gameMode == null) return;
        Field field = findField(packet.getClass(), LizzClient.isMCPMapped ? "f_134122_" : "xRot");
        try {
            field.setFloat(packet, pitch);
        } catch (Exception ex) {
            ClientBase.logger.error("Failed to set xrot field", ex);
        }
    }

    public static Container getBrewingStand(BrewingStandMenu menu) {
        if (menu == null) return null;
        Field field = findField(menu.getClass(), LizzClient.isMCPMapped ? "f_39086_" : "brewingStand");
        try {
            return (Container) field.get(menu);
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
    }

    private static Field findField(Class<?> clazz, String... fieldNames) {
        if (clazz == null || fieldNames == null || fieldNames.length == 0) {
            throw new IllegalArgumentException("Class and fieldNames must not be null or empty");
        }
        Exception last = null;
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (String name : fieldNames) {
                if (name == null) continue;
                try {
                    Field field = c.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (Exception e) {
                    last = e;
                }
            }
        }
        throw new ReflectionException(last);
    }

    public static void setMissTime(int missTime) {
        Field field = findField(ClientBase.mc.getClass(), "missTime", "f_91078_");
        try {
            field.setInt(ClientBase.mc, missTime);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public static String getMappedFieldName(Class<?> clazz, String fieldName) {
        return shit.lizz.asm.Bootstrap.remapField(clazz.getName().replace('.', '/'), fieldName);
    }

    public static String getMappedMethodName(Class<?> clazz, String methodName, String descriptor) {
        return shit.lizz.asm.Bootstrap.remapMethod(clazz.getName().replace('.', '/'), methodName, descriptor);
    }

    /**
     * Looks the field up by trying the supplied (mojmap) name first, then the
     * SRG-remapped name. Walks the superclass chain because the field may be
     * declared on an ancestor (e.g. {@code activeEffects} lives on
     * {@code LivingEntity}, not on {@code LocalPlayer}).
     */
    private static Field resolveField(Class<?> clazz, String name) throws NoSuchFieldException {
        NoSuchFieldException last = null;
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            String srg = shit.lizz.asm.Bootstrap.remapField(
                    c.getName().replace('.', '/'), name);
            try {
                return c.getDeclaredField(srg);
            } catch (NoSuchFieldException e) {
                last = e;
            }
            if (!srg.equals(name)) {
                try {
                    return c.getDeclaredField(name);
                } catch (NoSuchFieldException e) {
                    last = e;
                }
            }
        }
        throw last != null ? last : new NoSuchFieldException(name);
    }

    private static long getFieldOffset(Class<?> clazz, String name) {
        String key = clazz.getName() + "/" + name;
        long offset = offsetCache.getLong(key);
        if (offset != 0L) return offset;
        try {
            offset = unsafe.objectFieldOffset(resolveField(clazz, name));
            offsetCache.put(key, offset);
            return offset;
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private static ObjectLongPair<Object> getStaticFieldPair(Class<?> clazz, String name) {
        String key = clazz.getName() + "/" + name;
        ObjectLongPair<Object> pair = staticFieldCache.get(key);
        if (pair != null) return pair;
        try {
            Field field = resolveField(clazz, name);
            ObjectLongPair<Object> p = new ObjectLongImmutablePair<>(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field));
            staticFieldCache.put(key, p);
            return p;
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    public static Object getStaticField(Object object, String name, String className) {
        Class<?> clazz = loadClass(className);
        if (object == null) {
            ObjectLongPair<Object> pair = getStaticFieldPair(clazz, name);
            return unsafe.getObject(pair.first(), pair.secondLong());
        }
        // Instance-field path: also walk up the superclass chain.
        try {
            Field field = resolveField(object.getClass(), name);
            field.setAccessible(true);
            return field.get(object);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void setInstanceField(Object object, Object value, String name, String className) {
        Class<?> clazz = loadClass(className);
        unsafe.putObject(object, getFieldOffset(clazz, name), value);
    }

    public static void setStaticField(Object value, String name, String className) {
        Class<?> clazz = loadClass(className);
        ObjectLongPair<Object> pair = getStaticFieldPair(clazz, name);
        unsafe.putObject(pair.first(), pair.secondLong(), value);
    }

    public static void setFieldValue(Object object, Object value, String name) {
        Class<?> clazz = object.getClass();
        unsafe.putObject(object, getFieldOffset(clazz, name), value);
    }

    public static void setStaticFieldDirect(Object value, String name, String className) {
        setStaticField(value, name, className);
    }

    public static Class<?> loadClass(String name) {
        Class<?> clazz = classCache.get(name);
        if (clazz != null) return clazz;
        try {
            Class<?> c = Class.forName(name.replace("/", "."));
            classCache.put(name, c);
            return c;
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}
