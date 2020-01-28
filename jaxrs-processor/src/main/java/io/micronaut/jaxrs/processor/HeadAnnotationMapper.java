package io.micronaut.jaxrs.processor;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.http.annotation.Head;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

import javax.annotation.Nonnull;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.List;

/**
 * Maps the JAX-RS {@code HEAD} annotation to Micronaut's version.
 *
 * @author graemerocher
 * @since 1.0.0
 */
public class HeadAnnotationMapper implements NamedAnnotationMapper {

    @Nonnull
    @Override
    public String getName() {
        return "javax.ws.rs.HEAD";
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {
        return Collections.singletonList(
                AnnotationValue.builder(Head.class).build()
        );
    }
}
