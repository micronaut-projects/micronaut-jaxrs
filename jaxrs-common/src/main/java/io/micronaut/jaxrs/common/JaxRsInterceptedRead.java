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

import io.micronaut.context.BeanRegistration;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.Headers;
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.http.codec.CodecException;
import jakarta.ws.rs.ext.ReaderInterceptor;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The JAX-RS body read interceptor.
 *
 * @param <T> The type
 * @author Denis Stepanov
 * @since 4.9.0
 */
@Internal
public abstract class JaxRsInterceptedRead<T> {

    @Nullable
    private final List<BeanRegistration<ReaderInterceptor>> readerInterceptorsRegistrations;
    private final NameBindingPredicate nameBindingPredicate;

    public JaxRsInterceptedRead(List<BeanRegistration<ReaderInterceptor>> readerInterceptorsRegistrations,
                                NameBindingPredicate nameBindingPredicate) {
        this.nameBindingPredicate = nameBindingPredicate;
        if (readerInterceptorsRegistrations.isEmpty()) {
            throw new IllegalStateException("No ReaderInterceptor found");
        }
        this.readerInterceptorsRegistrations = new ArrayList<>(readerInterceptorsRegistrations);
        JaxRsUtils.sortByPriority(this.readerInterceptorsRegistrations);
    }

    public JaxRsInterceptedRead(List<ReaderInterceptor> readerInterceptor) {
        this(readerInterceptor.stream()
            .map(r -> new BeanRegistration<>(null, null, r))
            .toList(), annotationMetadata -> true);
    }

    public final T intercept(@NonNull Argument<T> type,
                             @Nullable MediaType mediaType,
                             @NonNull Headers httpHeaders,
                             @NonNull InputStream inputStream) throws CodecException {
        try {
            List<ReaderInterceptor> readerInterceptors = readerInterceptorsRegistrations.stream()
                .filter(br -> nameBindingPredicate.test(br.getBeanDefinition()))
                .map(BeanRegistration::getBean)
                .toList();
            Iterator<ReaderInterceptor> iterator = readerInterceptors.iterator();
            if (iterator.hasNext()) {
                JaxRsReaderInterceptorContext context = new JaxRsReaderInterceptorContext(iterator,
                    ctx -> readFromAfterInterception(
                        (Argument<Object>) ctx.asArgument(),
                        JaxRsUtils.convert(ctx.getMediaType()),
                        httpHeaders,
                        ctx.getInputStream()
                    ),
                    type,
                    JaxRsUtils.convert(mediaType),
                    new JaxRsMutableHeadersMultivaluedMap((MutableHeaders) httpHeaders),
                    inputStream
                );
                Object value = iterator.next().aroundReadFrom(context);
                if (!type.isInstance(value)) {
                    return ConversionService.SHARED.convertRequired(value, type);
                }
                return (T) value;
            } else {
                return readFromAfterInterception((Argument<Object>) type, mediaType, httpHeaders, inputStream);
            }
        } catch (IOException e) {
            throw new JaxRsIOException("Failed to read", e);
        }
    }

    /**
     * Read the value after the interception.
     * Some of the value might have been changed.
     *
     * @param type        The argument
     * @param mediaType   The media type
     * @param httpHeaders The headers
     * @param inputStream The input stream
     * @return The entity
     */
    protected abstract T readFromAfterInterception(@NonNull Argument<Object> type,
                                                   @Nullable MediaType mediaType,
                                                   @NonNull Headers httpHeaders,
                                                   @NonNull InputStream inputStream);

}
