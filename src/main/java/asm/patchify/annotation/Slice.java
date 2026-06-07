package asm.patchify.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE_USE)
public @interface Slice {
    At start() default @At(At.Type.HEAD);

    At end() default @At(At.Type.TAIL);

    int startIndex() default -1;

    int endIndex() default -1;
}
