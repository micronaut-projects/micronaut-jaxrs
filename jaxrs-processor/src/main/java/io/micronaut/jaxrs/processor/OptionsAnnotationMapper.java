package io.micronaut.jaxrs.processor;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.http.annotation.Options;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

import javax.annotation.Nonnull;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.List;

/**
 * Maps the JAX-RS {@code OPTIONS} annotation to Micronaut's version.
 *
 * @author graemerocher
 * @since 1.0.0
 */
public class OptionsAnnotationMapper implements NamedAnnotationMapper {

    @Nonnull
    @Override
    public String getName() {
        return "javax.ws.rs.OPTIONS";
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {
        return Collections.singletonList(
                AnnotationValue.builder(Options.class).build()
        );
    }
}
