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
package io.micronaut.jaxrs.client;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.io.buffer.ByteBufferFactory;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.codec.CodecException;
import jakarta.ws.rs.client.Entity;

import java.io.OutputStream;
import java.util.List;

/**
 * The JAX-RS {@link MessageBodyWriter}.
 *
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Internal
final class JaxRsEntityMessageBodyWriter implements MessageBodyWriter<Object> {

    private final MessageBodyHandlerRegistry registry;
    private final List<MediaType> mediaTypes;

    JaxRsEntityMessageBodyWriter(MessageBodyHandlerRegistry registry, List<MediaType> mediaTypes) {
        this.registry = registry;
        this.mediaTypes = mediaTypes;
    }

    @Override
    public void writeTo(@NonNull Argument<Object> type, @NonNull MediaType mediaType, Object object, @NonNull MutableHeaders outgoingHeaders, @NonNull OutputStream outputStream) throws CodecException {
        if (object instanceof Entity<?> entity) {
            object = entity.getEntity();
        }
        registry.getWriter(Argument.ofInstance(object), mediaTypes)
            .writeTo(type, mediaType, object, outgoingHeaders, outputStream);
    }

    @Override
    public @NonNull ByteBuffer<?> writeTo(@NonNull Argument<Object> type, @NonNull MediaType mediaType, Object object, @NonNull MutableHeaders outgoingHeaders, @NonNull ByteBufferFactory<?, ?> bufferFactory) throws CodecException {
        if (object instanceof Entity<?> entity) {
            object = entity.getEntity();
        }
        return registry.getWriter(Argument.ofInstance(object), mediaTypes)
            .writeTo(type, mediaType, object, outgoingHeaders, bufferFactory);
    }
}
