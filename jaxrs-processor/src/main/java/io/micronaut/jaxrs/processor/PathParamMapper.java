package io.micronaut.jaxrs.processor;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

import javax.annotation.Nonnull;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.List;

/**
 * Maps the JAX-RS {@code PathParam} annotation.
 *
 * @author graemerocher
 * @since 1.0
 */
public class PathParamMapper implements NamedAnnotationMapper {
    @Nonnull
    @Override
    public String getName() {
        return "javax.ws.rs.PathParam";
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {

        final AnnotationValueBuilder<PathVariable> builder = AnnotationValue.builder(PathVariable.class);
        annotation.stringValue().ifPresent(builder::value);
        return Collections.singletonList(
                builder.build()
        );
    }
}
