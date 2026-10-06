package io.casehub.yaml.plugin.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Plugin {
    String value();
    String description() default "";

    Portability portability() default Portability.JAVA;


    String capability() default "steps";
}
