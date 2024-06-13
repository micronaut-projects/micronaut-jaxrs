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
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.codec.CodecException;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.OutputStream;

/**
 * The reader remapped {@link MessageBodyWriter}.
 *
 * @param <T> The type
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Internal
@Singleton
@EachBean(jakarta.ws.rs.ext.MessageBodyWriter.class)
public final class JaxRsMessageBodyWriter<T> implements MessageBodyWriter<T> {

    private final jakarta.ws.rs.ext.MessageBodyWriter<T> messageBodyWriter;

    JaxRsMessageBodyWriter(jakarta.ws.rs.ext.MessageBodyWriter<T> messageBodyWriter) {
        this.messageBodyWriter = messageBodyWriter;
    }

    @Override
    public boolean isWriteable(@NonNull Argument<T> type, @Nullable MediaType mediaType) {
        return messageBodyWriter.isWriteable(type.getType(), type.asType(), type.getAnnotationMetadata().synthesizeAll(), as(mediaType));
    }

    @Override
    public void writeTo(@NonNull Argument<T> type,
                        @NonNull MediaType mediaType,
                        T object,
                        @NonNull MutableHeaders outgoingHeaders,
                        @NonNull OutputStream outputStream) throws CodecException {
        try {
            messageBodyWriter.writeTo(object,
                type.getType(),
                type.asType(),
                type.getAnnotationMetadata().synthesizeAll(),
                as(mediaType),
                new JaxRsMutableHeadersMultivaluedMap(outgoingHeaders),
                outputStream
            );
        } catch (IOException e) {
            throw new CodecException("Cannot write to", e);
        }
    }

    private jakarta.ws.rs.core.MediaType as(MediaType mediaType) {
        return jakarta.ws.rs.core.MediaType.valueOf(mediaType.toString());
    }

}
