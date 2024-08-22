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
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.Headers;
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.codec.CodecException;
import jakarta.ws.rs.ext.ReaderInterceptor;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;

/**
 * The Micronaut body reader with JAX-RS interceptors.
 *
 * @param <T> The type
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Internal
public final class InterceptedMessageBodyReader<T> implements MessageBodyReader<T> {

    @Nullable
    private final Class<?> readerType;
    private final MessageBodyReader<T> reader;
    private final List<ReaderInterceptor> readerInterceptor;

    public InterceptedMessageBodyReader(@Nullable Class<?> readerType,
                                        MessageBodyReader<T> reader,
                                        List<ReaderInterceptor> readerInterceptor) {
        this.readerType = readerType;
        this.reader = reader;
        this.readerInterceptor = readerInterceptor;
    }

    public boolean isReadable(@NonNull Argument<T> type, @Nullable MediaType mediaType) {
        return (readerType == null || type.getType().isAssignableFrom(readerType)) && reader.isReadable(type, mediaType);
    }

    @Override
    public @Nullable T read(@NonNull Argument<T> type, @Nullable MediaType mediaType, @NonNull Headers httpHeaders, @NonNull InputStream inputStream) throws CodecException {
        try {
            Iterator<ReaderInterceptor> iterator = readerInterceptor.iterator();
            if (iterator.hasNext()) {
                JaxRsReaderInterceptorContext context = new JaxRsReaderInterceptorContext(iterator,
                    ctx -> {
                        jakarta.ws.rs.core.MediaType mediaType1 = ctx.getMediaType();
                        return reader.read(ctx.asArgument(), JaxRsUtils.convert(mediaType1), httpHeaders, ctx.getInputStream());
                    },
                    type,
                    JaxRsUtils.convert(mediaType),
                    new JaxRsMutableHeadersMultivaluedMap((MutableHeaders) httpHeaders),
                    inputStream
                );
                return (T) iterator.next().aroundReadFrom(context);
            }
            return reader.read(type, mediaType, httpHeaders, inputStream);
        } catch (IOException e) {
            throw new CodecException("Failed to read", e);
        }
    }

}
