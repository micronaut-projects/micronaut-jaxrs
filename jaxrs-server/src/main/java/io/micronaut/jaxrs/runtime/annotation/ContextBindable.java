package io.micronaut.jaxrs.runtime.annotation;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.bind.annotation.Bindable;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Annotation to support JAX-RS {@code javax.ws.rs.core.Context}.
 *
 * @author graemerocher
 * @since 1.0
 */
@Documented
@Retention(RUNTIME)
@Target({ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Bindable
@Internal
public @interface ContextBindable {

}
