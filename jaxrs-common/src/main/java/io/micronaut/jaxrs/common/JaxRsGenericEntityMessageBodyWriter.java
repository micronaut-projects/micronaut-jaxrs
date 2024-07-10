/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.jaxrs.common;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.codec.CodecException;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.GenericEntity;

import java.io.OutputStream;
import java.util.List;

/**
 * The writer of {@link GenericEntity}.
 *
 * @param <T> The entity type
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Internal
@Singleton
final class JaxRsGenericEntityMessageBodyWriter<T> implements MessageBodyWriter<GenericEntity<T>> {

    private final MessageBodyHandlerRegistry registry;

    JaxRsGenericEntityMessageBodyWriter(MessageBodyHandlerRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void writeTo(@NonNull Argument<GenericEntity<T>> type,
                        @NonNull MediaType mediaType,
                        GenericEntity<T> genericEntity,
                        @NonNull MutableHeaders outgoingHeaders,
                        @NonNull OutputStream outputStream) throws CodecException {
        Argument<T> argument;
        if (genericEntity instanceof JaxRsGenericEntity<T> jaxRsGenericEntity) {
            argument = jaxRsGenericEntity.asArgument();
        } else {
            argument = ArgumentUtil.from(genericEntity);
        }
        T entity = genericEntity.getEntity();
        registry.getWriter(argument, List.of(mediaType))
            .writeTo(argument, mediaType, entity, outgoingHeaders, outputStream);
    }

}
