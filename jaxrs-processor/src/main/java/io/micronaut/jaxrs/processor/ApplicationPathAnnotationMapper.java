package io.micronaut.jaxrs.processor;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.http.annotation.UriMapping;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

import javax.annotation.Nonnull;
import javax.inject.Singleton;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;

/**
 * Maps the JAX-RS {@code ApplicationPath} annotation turning the application class into a singleton.
 *
 * @author graemerocher
 * @since 1.0.0
 */
public class ApplicationPathAnnotationMapper implements NamedAnnotationMapper {
    @Nonnull
    @Override
    public String getName() {
        return "javax.ws.rs.ApplicationPath";
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {
        final AnnotationValueBuilder<UriMapping> builder = AnnotationValue.builder(UriMapping.class);
        final String path = annotation.stringValue().orElse("/");
        builder.value(path);
        return Arrays.asList(
                AnnotationValue.builder(Singleton.class).build(),
                builder.build()
        );
    }
}
