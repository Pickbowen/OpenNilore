package shit.nilore.asm;

/**
 * Mutable view of the local-variable slots seen by a {@code @ModifyLocals} handler.
 */
public interface ILocals {
    Object get(int index);

    ILocals set(int index, Object value);
}
