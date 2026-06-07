package shit.lizz.asm;

import java.util.List;

@SuppressWarnings("rawtypes")
public final class InvocationImpl implements Invocation {
    private final Object instance;
    private final MethodWrapper wrapper;

    private InvocationImpl(Object instance, MethodWrapper wrapper) {
        this.instance = instance;
        this.wrapper = wrapper;
    }

    public static InvocationImpl create(Object instance, MethodWrapper wrapper) {
        return new InvocationImpl(instance, wrapper);
    }

    public static InvocationImpl create(MethodWrapper wrapper) {
        return new InvocationImpl(null, wrapper);
    }

    @Override
    public List<Object> args() {
        return wrapper.getMethodParams();
    }

    @Override
    public Object instance() {
        if (instance == null) {
            throw new IllegalStateException("static method invocation");
        }
        return instance;
    }

    @Override
    public Object call() throws Exception {
        try {
            return wrapper.call(instance);
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
