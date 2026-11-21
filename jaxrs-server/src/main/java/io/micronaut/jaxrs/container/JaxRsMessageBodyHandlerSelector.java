/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.jaxrs.container;

import io.micronaut.context.BeanProvider;
import io.micronaut.context.BeanRegistration;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.MessageBodyHandlerSelector;
import io.micronaut.http.body.MessageBodyReader;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Selects the JAX-RS readers of the application before the Micronaut readers (JAX-RS 4.2.1): the
 * reader of a lookup is {@link JaxRsMessageBodyReaders}, which reads through the reader
 * interceptors with the JAX-RS reader it selects for each value, when a JAX-RS reader of the
 * application reads the type.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Singleton
@Internal
final class JaxRsMessageBodyHandlerSelector implements MessageBodyHandlerSelector {

    // looked up when a reader is selected: the readers depend on the registry this selector is part of
    private final BeanProvider<JaxRsApplications> applications;

    JaxRsMessageBodyHandlerSelector(BeanProvider<JaxRsApplications> applications) {
        this.applications = applications;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> @Nullable MessageBodyReader<T> selectReader(Argument<T> type,
                                                           List<MediaType> mediaTypes,
                                                           List<BeanRegistration<MessageBodyReader<T>>> candidates) {
        if (type.getAnnotationMetadata().hasAnnotation(JaxRsRouteSupport.RAW_ENTITY)) {
            // the bytes of the entity of a resource method, which it reads with the JAX-RS readers
            return null;
        }
        // the readers of the application of the current request
        JaxRsMessageBodyReaders<T> jaxRsReaders = (JaxRsMessageBodyReaders<T>) applications.get().current().readers();
        if (mediaTypes.isEmpty()) {
            return jaxRsReaders.isReadable(type, null) ? jaxRsReaders : null;
        }
        for (MediaType mediaType : mediaTypes) {
            if (jaxRsReaders.isReadable(type, mediaType)) {
                return jaxRsReaders;
            }
        }
        return null;
    }
}
