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
package io.micronaut.jaxrs.runtime.core;

import io.micronaut.context.annotation.EachBean;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.Headers;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.codec.CodecException;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;

/**
 * The reader remapped {@link MessageBodyReader}.
 *
 * @param <T> The type
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Internal
@Singleton
@EachBean(jakarta.ws.rs.ext.MessageBodyReader.class)
public final class JaxRsMessageBodyReader<T> implements MessageBodyReader<T> {

    private final jakarta.ws.rs.ext.MessageBodyReader<T> messageBodyReader;

    JaxRsMessageBodyReader(jakarta.ws.rs.ext.MessageBodyReader<T> messageBodyReader) {
        this.messageBodyReader = messageBodyReader;
    }

    @Override
    public boolean isReadable(@NonNull Argument<T> type, @Nullable MediaType mediaType) {
        return messageBodyReader.isReadable(type.getType(), type.asType(), type.getAnnotationMetadata().synthesizeAll(), as(mediaType));
    }

    private jakarta.ws.rs.core.MediaType as(MediaType mediaType) {
        return jakarta.ws.rs.core.MediaType.valueOf(mediaType.toString());
    }

    @Override
    public @Nullable T read(@NonNull Argument<T> type, @Nullable MediaType mediaType, @NonNull Headers httpHeaders, @NonNull InputStream inputStream) throws CodecException {
        try {
            return messageBodyReader.readFrom(
                type.getType(),
                type.asType(),
                type.getAnnotationMetadata().synthesizeAll(),
                as(mediaType),
                new JaxRsHeadersMultivaluedMap(httpHeaders),
                inputStream
            );
        } catch (IOException e) {
            throw new CodecException("Failed to read", e);
        }
    }
}
