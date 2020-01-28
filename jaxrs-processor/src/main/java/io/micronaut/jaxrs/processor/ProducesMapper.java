package io.micronaut.jaxrs.processor;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.http.annotation.Produces;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

import javax.annotation.Nonnull;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.List;

/**
 * Maps the JAX-RS {@code Produces} annotation.
 *
 * @author graemerocher
 * @since 1.0
 */
public class ProducesMapper implements NamedAnnotationMapper {
    @Nonnull
    @Override
    public String getName() {
        return "javax.ws.rs.Produces";
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {

        final AnnotationValueBuilder<Produces> builder = AnnotationValue.builder(Produces.class);
        annotation.stringValue().ifPresent(builder::value);
        return Collections.singletonList(
                builder.build()
        );
    }
}
