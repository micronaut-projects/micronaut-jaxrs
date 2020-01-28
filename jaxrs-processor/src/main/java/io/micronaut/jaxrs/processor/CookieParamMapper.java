package io.micronaut.jaxrs.processor;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.http.annotation.CookieValue;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

import javax.annotation.Nonnull;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.List;

/**
 * Maps the JAX-RS {@code CookieParam} annotation.
 *
 * @author graemerocher
 * @since 1.0
 */
public class CookieParamMapper implements NamedAnnotationMapper {
    @Nonnull
    @Override
    public String getName() {
        return "javax.ws.rs.CookieParam";
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {

        final AnnotationValueBuilder<CookieValue> builder = AnnotationValue.builder(CookieValue.class);
        annotation.stringValue().ifPresent(builder::value);
        return Collections.singletonList(
                builder.build()
        );
    }
}
