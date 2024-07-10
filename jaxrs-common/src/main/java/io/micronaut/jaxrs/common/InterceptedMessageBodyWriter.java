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
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.codec.CodecException;
import jakarta.ws.rs.ext.WriterInterceptor;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.List;

/**
 * The Micronaut body writer with JAX-RS interceptors.
 *
 * @param <T> The type
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Internal
public final class InterceptedMessageBodyWriter<T> implements MessageBodyWriter<T> {

    @Nullable
    private final Class<?> writerType;
    private final MessageBodyWriter<T> delegate;
    private final List<WriterInterceptor> writerInterceptor;

    public InterceptedMessageBodyWriter(@Nullable Class<?> writerType, MessageBodyWriter<T> delegate, List<WriterInterceptor> writerInterceptor) {
        this.writerType = writerType;
        this.delegate = delegate;
        this.writerInterceptor = writerInterceptor;
    }

    @Override
    public boolean isWriteable(@NonNull Argument<T> type, @Nullable MediaType mediaType) {
        return (writerType == null || writerType.isAssignableFrom(type.getType())) && delegate.isWriteable(type, mediaType);
    }

    @Override
    public void writeTo(@NonNull Argument<T> type,
                        @NonNull MediaType mediaType,
                        T object,
                        @NonNull MutableHeaders outgoingHeaders,
                        @NonNull OutputStream outputStream) throws CodecException {
        try {
            Iterator<WriterInterceptor> iterator = writerInterceptor.iterator();
            JaxRsMutableObjectHeadersMultivaluedMap httpHeaders = new JaxRsMutableObjectHeadersMultivaluedMap(outgoingHeaders);
            if (iterator.hasNext()) {
                JaxRsWriterInterceptorContext context = new JaxRsWriterInterceptorContext(iterator,
                    ctx -> {
                        jakarta.ws.rs.core.MediaType mediaType1 = ctx.getMediaType();
                        delegate.writeTo(
                            ctx.asArgument(),
                            JaxRsUtils.convert(mediaType1),
                            (T) ctx.getEntity(),
                            outgoingHeaders,
                            ctx.getOutputStream()
                        );
                    },
                    type,
                    JaxRsUtils.convert(mediaType),
                    httpHeaders,
                    object,
                    outputStream
                );
                iterator.next().aroundWriteTo(context);
                return;
            }
            delegate.writeTo(type, mediaType, object, outgoingHeaders, outputStream);
        } catch (IOException e) {
            throw new CodecException("Cannot write to", e);
        }
    }

}
