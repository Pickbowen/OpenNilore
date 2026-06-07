package asm.patchify.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Inject {
    String method();

    String desc();

    At at() default @At(At.Type.HEAD);

    Slice slice() default @Slice(start = @At(At.Type.HEAD), end = @At(At.Type.TAIL));
}
