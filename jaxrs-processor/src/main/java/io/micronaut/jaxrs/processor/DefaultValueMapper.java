package io.micronaut.jaxrs.processor;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

import javax.annotation.Nonnull;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.List;

/**
 * Maps the JAX-RS {@code DefaultValue} annotation.
 *
 * @author graemerocher
 * @since 1.0
 */
public class DefaultValueMapper implements NamedAnnotationMapper {
    @Nonnull
    @Override
    public String getName() {
        return "javax.ws.rs.DefaultValue";
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {
        final AnnotationValueBuilder<Bindable> builder = AnnotationValue.builder(Bindable.class);
        annotation.stringValue().ifPresent(s -> builder.member("defaultValue", s));
        return Collections.singletonList(builder.build());
    }
}
