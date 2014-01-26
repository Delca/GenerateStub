package com.test.autostub;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface GenerateStub {

    // By default, the annotation processor implements a stub for the annotated class
    String[] toStub() default {};
    // By default, the prefix used is "Stub"
    String prefix() default "Stub";
    // By default, no suffix is used
    String suffix() default "";

}
