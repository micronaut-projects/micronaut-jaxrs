package io.micronaut.jaxrs.processor;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

import javax.annotation.Nonnull;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.List;

/**
 * Maps the JAX-RS {@code Consumes} annotation.
 *
 * @author graemerocher
 * @since 1.0
 */
public class ConsumesMapper implements NamedAnnotationMapper {
    @Nonnull
    @Override
    public String getName() {
        return "javax.ws.rs.ConsumesMapper";
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {

        final AnnotationValueBuilder<Consumes> builder = AnnotationValue.builder(Consumes.class);
        annotation.stringValue().ifPresent(builder::value);
        return Collections.singletonList(
                builder.build()
        );
    }
}
