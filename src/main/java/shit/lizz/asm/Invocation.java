package shit.lizz.asm;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Handle to the original {@code INVOKE} that was wrapped by an {@code @WrapInvoke} patch.
 *
 * @param <T> receiver type of the wrapped method (or {@link Object} for static targets)
 * @param <R> return type of the wrapped method
 */
public interface Invocation<T, R> extends Callable<R> {
    /**
     * Arguments captured at the wrapped call site, in their original order.
     */
    List<Object> args();

    /**
     * Receiver of the wrapped call. Throws for static targets.
     */
    T instance();
}
