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
package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.Headers;
import io.micronaut.http.MediaType;
import io.micronaut.http.codec.CodecException;
import jakarta.ws.rs.ext.MessageBodyReader;

import java.io.InputStream;

/**
 * The implementation of {@link MessageBodyReader} for {@link String}.
 * Duplicate reader to be chosen before internal string reader.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Prototype
@Internal
final class JaxRsOverrideStringMessageBodyReader implements io.micronaut.http.body.MessageBodyReader<String> {

    private final JaxRsMessageBodyReaders<String> readers;

    public JaxRsOverrideStringMessageBodyReader(JaxRsMessageBodyReaders<String> readers) {
        this.readers = readers;
    }

    @Override
    public boolean isReadable(@NonNull Argument<String> type, @Nullable MediaType mediaType) {
        return readers.isReadable(type, mediaType);
    }

    @Override
    public @Nullable String read(@NonNull Argument<String> type, @Nullable MediaType mediaType, @NonNull Headers httpHeaders, @NonNull ByteBuffer<?> byteBuffer) throws CodecException {
        return readers.read(type, mediaType, httpHeaders, byteBuffer);
    }

    @Override
    public @Nullable String read(@NonNull Argument<String> type, @Nullable MediaType mediaType, @NonNull Headers httpHeaders, @NonNull InputStream inputStream) throws CodecException {
        return readers.read(type, mediaType, httpHeaders, inputStream);
    }
}
