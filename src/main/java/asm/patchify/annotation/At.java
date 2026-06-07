package asm.patchify.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE_USE)
public @interface At {
    Type value() default Type.HEAD;

    String method() default "";

    String remapped() default "";

    String desc() default "";

    enum Type {
        BEFORE_INVOKE,
        AFTER_INVOKE,
        HEAD,
        TAIL
    }
}
