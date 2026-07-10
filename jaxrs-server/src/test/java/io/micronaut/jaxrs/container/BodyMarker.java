package io.micronaut.jaxrs.container;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface BodyMarker {
    String value() default "";
}
