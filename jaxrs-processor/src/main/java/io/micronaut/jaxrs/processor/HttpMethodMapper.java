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
import io.micronaut.http.HttpMethod;
import io.micronaut.http.annotation.*;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

import io.micronaut.core.annotation.NonNull;
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
public class HttpMethodMapper implements NamedAnnotationMapper {
    @NonNull
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
                   builder.value(UriMapping.DEFAULT_URI).build()
            );
        }
        return Collections.emptyList();
    }
}
