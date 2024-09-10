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
import jakarta.ws.rs.ext.ReaderInterceptor;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;

/**
 * The reader remapped {@link MessageBodyReader}.
 *
 * @param <T> The type
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Internal
@Singleton
@EachBean(value = jakarta.ws.rs.ext.MessageBodyReader.class, remapGenerics = @EachBean.RemapGeneric(name = "T", type = MessageBodyReader.class))
public final class JaxRsMessageBodyReader<T> implements MessageBodyReader<T> {

    private final jakarta.ws.rs.ext.MessageBodyReader<T> delegate;
    private final List<ReaderInterceptor> readerInterceptor;

    public JaxRsMessageBodyReader(jakarta.ws.rs.ext.MessageBodyReader<T> delegate,
                                  List<ReaderInterceptor> readerInterceptor) {
        this.delegate = delegate;
        this.readerInterceptor = readerInterceptor;
        JaxRsUtils.sortByPriority(readerInterceptor);
    }

    public jakarta.ws.rs.ext.MessageBodyReader<T> getDelegate() {
        return delegate;
    }

    @Override
    public boolean isReadable(@NonNull Argument<T> type, @Nullable MediaType mediaType) {
        return delegate.isReadable(type.getType(), type.asType(), type.getAnnotationMetadata().synthesizeAll(), JaxRsUtils.convert(mediaType));
    }

    @Override
    public @Nullable T read(@NonNull Argument<T> type, @Nullable MediaType mediaType, @NonNull Headers httpHeaders, @NonNull InputStream inputStream) throws CodecException {
        try {
            Iterator<ReaderInterceptor> iterator = readerInterceptor.iterator();
            if (iterator.hasNext()) {
                JaxRsReaderInterceptorContext context = new JaxRsReaderInterceptorContext(iterator,
                    ctx -> delegate.readFrom(
                        (Class<T>) ctx.getType(),
                        ctx.getGenericType(),
                        ctx.getAnnotations(),
                        ctx.getMediaType(),
                        new JaxRsHeadersMultivaluedMap(httpHeaders),
                        ctx.getInputStream()
                    ),
                    type,
                    JaxRsUtils.convert(mediaType),
                    new JaxRsHeadersMultivaluedMap(httpHeaders),
                    inputStream
                );
                return (T) iterator.next().aroundReadFrom(context);
            }
            return delegate.readFrom(
                type.getType(),
                type.asType(),
                type.getAnnotationMetadata().synthesizeAll(),
                JaxRsUtils.convert(mediaType),
                new JaxRsHeadersMultivaluedMap(httpHeaders),
                inputStream
            );
        } catch (IOException e) {
            throw new CodecException("Failed to read", e);
        }
    }

}
