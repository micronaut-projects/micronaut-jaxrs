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
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpMessage;
import jakarta.ws.rs.ProcessingException;

import java.util.Optional;

/**
 * En entity reader.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
public class HttpMessageEntityReader {

    public static final HttpMessageEntityReader DEFAULT = new HttpMessageEntityReader();

    /**
     * Read the entity.
     *
     * @param message    The message
     * @param entityType the entity type
     * @param <T>        The entity type
     * @return The entity value
     */
    public <T> T readEntity(HttpMessage<?> message, Argument<T> entityType) {
        T result = message.getBody(entityType).orElse(null);
        if (result == null) {
            Optional<String> body = message.getBody(String.class);
            if (body.isEmpty()) {
                return null;
            }
            return body
                .flatMap(str -> ConversionService.SHARED.convert(str, entityType))
                .orElseThrow(() -> new ProcessingException("Cannot read an entity of type " + entityType));
        }
        return result;
    }

    /**
     * Read the entity.
     *
     * @param byteBuffer The buffer
     * @param entityType the entity type
     * @param <T>        The entity type
     * @return The entity value
     */
    public <T> T readEntity(ByteBuffer<?> byteBuffer, Argument<T> entityType) {
        return ConversionService.SHARED.convert(byteBuffer.toByteArray(), entityType).orElse(null);
    }

}
