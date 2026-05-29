/*
 * Copyright 2017-2025 original authors
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

import io.micronaut.context.BeanRegistration;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.Headers;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.codec.CodecException;
import io.micronaut.jaxrs.common.JaxRsInterceptedRead;
import io.micronaut.jaxrs.common.JaxRsContainerMessageBodyHandlerRegistry;
import io.micronaut.jaxrs.common.JaxRsUtils;
import io.micronaut.jaxrs.common.NameBindingPredicate;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ext.ReaderInterceptor;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Uses JAX-RS reads to red the controller body.
 *
 * @param <T> The body type
 * @author Denis Stepanov
 * @since 4.9
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Singleton
final class JaxRsMessageBodyReaders<T> implements MessageBodyReader<T> {
    private static final MediaType DEFAULT_ENTITY_MEDIA_TYPE = MediaType.APPLICATION_OCTET_STREAM_TYPE;

    private final JaxRsContainerMessageBodyHandlerRegistry registry;
    private final List<BeanRegistration<ReaderInterceptor>> readerInterceptorsRegsRegistrations;
    private final NameBindingPredicate nameBindingPredicate;
    private final JaxRsDynamicFeatureRegistry dynamicFeatureRegistry;

    public JaxRsMessageBodyReaders(JaxRsContainerMessageBodyHandlerRegistry registry,
                                   List<BeanRegistration<ReaderInterceptor>> readerInterceptorsRegsRegistrations,
                                   NameBindingPredicate nameBindingPredicate,
                                   JaxRsDynamicFeatureRegistry dynamicFeatureRegistry) {
        this.registry = registry;
        this.readerInterceptorsRegsRegistrations = readerInterceptorsRegsRegistrations;
        this.nameBindingPredicate = nameBindingPredicate;
        this.dynamicFeatureRegistry = dynamicFeatureRegistry;
    }

    @Override
    public boolean isReadable(@NonNull Argument<T> type, @Nullable MediaType mediaType) {
        return hasReaderInterceptors() || registry.findReader(type, getMediaTypes(mediaType)).isPresent();
    }

    boolean hasReaderInterceptors() {
        return !readerInterceptorsRegsRegistrations.isEmpty() || dynamicFeatureRegistry.hasReaderInterceptors();
    }

    private List<MediaType> getMediaTypes(MediaType mediaType) {
        return List.of(effectiveMediaType(mediaType));
    }

    private static MediaType effectiveMediaType(@Nullable MediaType mediaType) {
        return mediaType == null ? DEFAULT_ENTITY_MEDIA_TYPE : mediaType;
    }

    @Override
    public @Nullable T read(@NonNull Argument<T> type, @Nullable MediaType mediaType, @NonNull Headers httpHeaders, @NonNull ByteBuffer<?> byteBuffer) throws CodecException {
        List<ReaderInterceptor> dynamicReaderInterceptors = dynamicFeatureRegistry.currentComponents().readerInterceptors();
        if (readerInterceptorsRegsRegistrations.isEmpty() && dynamicReaderInterceptors.isEmpty()) {
            MediaType effectiveMediaType = effectiveMediaType(mediaType);
            Optional<MessageBodyReader<T>> reader = registry.findReader(type, List.of(effectiveMediaType));
            if (reader.isPresent()) {
                return reader
                    .get().read(type, effectiveMediaType, httpHeaders, byteBuffer);
            }
            throw new CodecException("No reader registered for " + type + " with media type " + mediaType);
        }
        if (dynamicReaderInterceptors.isEmpty()) {
            return intercept(type, mediaType, httpHeaders, byteBuffer.toInputStream());
        }
        return intercept(type, mediaType, httpHeaders, byteBuffer.toInputStream(), dynamicReaderInterceptors);
    }

    @Override
    public @Nullable T read(@NonNull Argument<T> type, @Nullable MediaType mediaType, @NonNull Headers httpHeaders, @NonNull InputStream inputStream) throws CodecException {
        List<ReaderInterceptor> dynamicReaderInterceptors = dynamicFeatureRegistry.currentComponents().readerInterceptors();
        if (readerInterceptorsRegsRegistrations.isEmpty() && dynamicReaderInterceptors.isEmpty()) {
            MediaType effectiveMediaType = effectiveMediaType(mediaType);
            Optional<MessageBodyReader<T>> reader = registry.findReader(type, List.of(effectiveMediaType));
            if (reader.isPresent()) {
                return reader.get().read(type, effectiveMediaType, httpHeaders, inputStream);
            }
            throw new CodecException("No reader found for " + mediaType + " and type " + type);
        }
        if (dynamicReaderInterceptors.isEmpty()) {
            return intercept(type, mediaType, httpHeaders, inputStream);
        }
        return intercept(type, mediaType, httpHeaders, inputStream, dynamicReaderInterceptors);
    }

    private T intercept(Argument<T> type,
                        MediaType mediaType,
                        Headers httpHeaders,
                        InputStream inputStream) {
        return new JaxRsInterceptedRead<T>(readerInterceptorsRegsRegistrations, nameBindingPredicate) {

            @Override
            protected T readFromAfterInterception(Argument<Object> type, MediaType mediaType, Headers httpHeaders, InputStream inputStream) {
                MediaType effectiveMediaType = effectiveMediaType(mediaType);
                Optional<MessageBodyReader<Object>> reader = registry.findReader(type, List.of(effectiveMediaType));
                if (reader.isPresent()) {
                    return (T) reader.get().read(type, effectiveMediaType, httpHeaders, inputStream);
                }
                throw new CodecException("No reader found for " + mediaType + " and type " + type);
            }

        }.intercept(type, mediaType, httpHeaders, inputStream);
    }

    private T intercept(Argument<T> type,
                        MediaType mediaType,
                        Headers httpHeaders,
                        InputStream inputStream,
                        List<ReaderInterceptor> dynamicReaderInterceptors) {
        List<ReaderInterceptor> interceptors = new ArrayList<>(readerInterceptorsRegsRegistrations.stream()
            .filter(registration -> nameBindingPredicate.test(registration.getBeanDefinition()))
            .map(BeanRegistration::getBean)
            .toList());
        interceptors.addAll(dynamicReaderInterceptors);
        JaxRsUtils.sortByPriority(interceptors);
        return new JaxRsInterceptedRead<T>(interceptors) {

            @Override
            protected T readFromAfterInterception(Argument<Object> type, MediaType mediaType, Headers httpHeaders, InputStream inputStream) {
                MediaType effectiveMediaType = effectiveMediaType(mediaType);
                Optional<MessageBodyReader<Object>> reader = registry.findReader(type, List.of(effectiveMediaType));
                if (reader.isPresent()) {
                    return (T) reader.get().read(type, effectiveMediaType, httpHeaders, inputStream);
                }
                throw new CodecException("No reader found for " + mediaType + " and type " + type);
            }

        }.intercept(type, mediaType, httpHeaders, inputStream);
    }
}
