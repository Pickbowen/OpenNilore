package shit.lizz.asm;

import java.util.HashMap;
import java.util.Map;

public final class Locals implements ILocals {
    private final Map<Integer, Object> locals = new HashMap<>();

    private Locals() {
    }

    public static Locals create() {
        return new Locals();
    }

    @Override
    public Object get(int index) {
        if (!locals.containsKey(index)) {
            throw new IllegalArgumentException("Index " + index + " is not defined in the local list");
        }
        return locals.get(index);
    }

    @Override
    public Locals set(int index, Object value) {
        locals.put(index, value);
        return this;
    }
}
