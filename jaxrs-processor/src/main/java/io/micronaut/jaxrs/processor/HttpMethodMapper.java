package io.micronaut.jaxrs.processor;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.annotation.*;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

import javax.annotation.Nonnull;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class HttpMethodMapper implements NamedAnnotationMapper {
    @Nonnull
    @Override
    public String getName() {
        return "javax.ws.rs.HttpMethod";
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {
        String name = annotation.stringValue().orElse(null);
        if (name != null) {
            name = name.toUpperCase(Locale.ENGLISH);
            HttpMethod httpMethod = HttpMethod.parse(name);
            AnnotationValueBuilder<?> builder = null;
            switch (httpMethod) {
                case GET:
                    builder = AnnotationValue.builder(Get.class);
                break;
                case POST:
                    builder = AnnotationValue.builder(Post.class);
                break;
                case PUT:
                    builder = AnnotationValue.builder(Put.class);
                break;
                case PATCH:
                    builder = AnnotationValue.builder(Patch.class);
                break;
                case DELETE:
                    builder = AnnotationValue.builder(Delete.class);
                break;
                case TRACE:
                    builder = AnnotationValue.builder(Trace.class);
                break;
                case OPTIONS:
                    builder = AnnotationValue.builder(Options.class);
                break;
                case HEAD:
                    builder = AnnotationValue.builder(Head.class);
                break;
                case CUSTOM:
                case CONNECT:
                default:
                    builder = AnnotationValue.builder(CustomHttpMethod.class);
                    builder.member("method", name);
            }

            return Collections.singletonList(
                   builder.build()
            );
        }
        return Collections.emptyList();
    }
}
