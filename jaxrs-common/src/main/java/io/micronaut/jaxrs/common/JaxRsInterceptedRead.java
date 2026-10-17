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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.Headers;
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.http.codec.CodecException;
import io.micronaut.http.simple.SimpleHttpHeaders;
import jakarta.ws.rs.ext.ReaderInterceptor;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JAX-RS body read interceptor.
 *
 * @param <T> The type
 * @author Denis Stepanov
 * @since 4.9.0
 */
@Internal
public abstract class JaxRsInterceptedRead<T> {

    private final List<BeanRegistration<ReaderInterceptor>> readerInterceptorsRegistrations;
    private final NameBindingPredicate nameBindingPredicate;

    public JaxRsInterceptedRead(List<BeanRegistration<ReaderInterceptor>> readerInterceptorsRegistrations,
                                NameBindingPredicate nameBindingPredicate) {
        this.nameBindingPredicate = nameBindingPredicate;
        if (readerInterceptorsRegistrations.isEmpty()) {
            throw new IllegalStateException("No ReaderInterceptor found");
        }
        this.readerInterceptorsRegistrations = new ArrayList<>(readerInterceptorsRegistrations);
        JaxRsUtils.sortRegistrationsByPriority(this.readerInterceptorsRegistrations);
    }

    public JaxRsInterceptedRead(List<ReaderInterceptor> readerInterceptor) {
        this(readerInterceptor.stream()
            .map(JaxRsRouteInterceptors::registration)
            .toList(), annotationMetadata -> true);
    }

    public final @Nullable T intercept(@NonNull Argument<T> type,
                             @Nullable MediaType mediaType,
                             @NonNull Headers httpHeaders,
                             @NonNull InputStream inputStream) throws CodecException {
        return intercept(type, mediaType, httpHeaders, inputStream, new LinkedHashMap<>());
    }

    /**
     * Read through the interceptors.
     *
     * @param type        The type
     * @param mediaType   The media type
     * @param httpHeaders The headers
     * @param inputStream The entity
     * @param properties  The properties of the request or response the entity is read of, which
     *                    the interceptor contexts share with its filter contexts
     * @return The value
     * @throws CodecException If the entity cannot be read
     */
    public final @Nullable T intercept(@NonNull Argument<T> type,
                             @Nullable MediaType mediaType,
                             @NonNull Headers httpHeaders,
                             @NonNull InputStream inputStream,
                             @NonNull Map<String, Object> properties) throws CodecException {
        try {
            List<ReaderInterceptor> readerInterceptors = readerInterceptorsRegistrations.stream()
                .filter(br -> nameBindingPredicate.test(br.getBeanDefinition()))
                .map(BeanRegistration::getBean)
                .toList();
            Iterator<ReaderInterceptor> iterator = readerInterceptors.iterator();
            if (iterator.hasNext()) {
                // the interceptors can change the headers the reader sees, e.g. immutable servlet request headers
                MutableHeaders headers = httpHeaders instanceof MutableHeaders mutableHeaders
                    ? mutableHeaders
                    : copy(httpHeaders);
                JaxRsReaderInterceptorContext context = new JaxRsReaderInterceptorContext(iterator,
                    ctx -> readFromAfterInterception(
                        (Argument<Object>) ctx.asArgument(),
                        JaxRsUtils.convert(ctx.getMediaType()),
                        headers,
                        ctx.getInputStream()
                    ),
                    type,
                    JaxRsUtils.convert(mediaType),
                    new JaxRsMutableHeadersMultivaluedMap(headers),
                    inputStream,
                    properties
                );
                Object value = iterator.next().aroundReadFrom(context);
                if (value == null) {
                    // e.g. a reader of an empty entity
                    return null;
                }
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

    private static MutableHeaders copy(Headers headers) {
        SimpleHttpHeaders copy = new SimpleHttpHeaders();
        for (String name : headers.names()) {
            for (String value : headers.getAll(name)) {
                copy.add(name, value);
            }
        }
        return copy;
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
    protected abstract @Nullable T readFromAfterInterception(@NonNull Argument<Object> type,
                                                   @Nullable MediaType mediaType,
                                                   @NonNull Headers httpHeaders,
                                                   @NonNull InputStream inputStream);

}
