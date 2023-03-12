/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.jaxrs.processor;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.annotation.CustomHttpMethod;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Head;
import io.micronaut.http.annotation.Options;
import io.micronaut.http.annotation.Patch;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.Trace;
import io.micronaut.http.annotation.UriMapping;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Maps the JAX-RS {@code HttpMethod} annotation.
 *
 * @author graemerocher
 * @since 1.0
 */
@Internal
public class HttpMethodMapper implements NamedAnnotationMapper {
    @NonNull
    @Override
    public String getName() {
        return "jakarta.ws.rs.HttpMethod";
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {
        String name = annotation.stringValue().orElse(null);
        if (name != null) {
            name = name.toUpperCase(Locale.ENGLISH);
            HttpMethod httpMethod = HttpMethod.parse(name);
            AnnotationValueBuilder<?> builder = switch (httpMethod) {
                case GET -> AnnotationValue.builder(Get.class);
                case POST -> AnnotationValue.builder(Post.class);
                case PUT -> AnnotationValue.builder(Put.class);
                case PATCH -> AnnotationValue.builder(Patch.class);
                case DELETE -> AnnotationValue.builder(Delete.class);
                case TRACE -> AnnotationValue.builder(Trace.class);
                case OPTIONS -> AnnotationValue.builder(Options.class);
                case HEAD -> AnnotationValue.builder(Head.class);
                case CUSTOM, CONNECT -> AnnotationValue.builder(CustomHttpMethod.class).member("method", name);
            };
            return Collections.singletonList(
                builder.value(UriMapping.DEFAULT_URI).build()
            );
        }
        return Collections.emptyList();
    }
}
