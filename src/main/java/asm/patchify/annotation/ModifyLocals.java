package asm.patchify.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ModifyLocals {
    String method();

    String desc();

    int[] indexes();

    Class<?>[] types();

    At at() default @At(At.Type.HEAD);
}
