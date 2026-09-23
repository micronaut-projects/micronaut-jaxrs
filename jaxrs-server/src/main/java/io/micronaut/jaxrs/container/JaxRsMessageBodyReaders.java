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

import io.micronaut.context.BeanProvider;
import io.micronaut.context.BeanRegistration;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.Headers;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.codec.CodecException;
import io.micronaut.jaxrs.common.JaxRsInterceptedRead;
import io.micronaut.jaxrs.common.JaxRsContainerMessageBodyHandlerRegistry;
import io.micronaut.jaxrs.common.JaxRsRouteInterceptors;
import io.micronaut.jaxrs.common.NameBindingPredicate;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.ext.ReaderInterceptor;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;
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

    private final JaxRsContainerMessageBodyHandlerRegistry registry;
    private final List<BeanRegistration<ReaderInterceptor>> readerInterceptorsRegsRegistrations;
    private final NameBindingPredicate nameBindingPredicate;
    private final JaxRsFeatures features;
    private final BeanProvider<MessageBodyHandlerRegistry> micronautReaders;

    public JaxRsMessageBodyReaders(JaxRsContainerMessageBodyHandlerRegistry registry,
                                   List<BeanRegistration<ReaderInterceptor>> readerInterceptorsRegsRegistrations,
                                   NameBindingPredicate nameBindingPredicate,
                                   JaxRsFeatures features,
                                   BeanProvider<MessageBodyHandlerRegistry> micronautReaders) {
        this.features = features;
        this.micronautReaders = micronautReaders;
        this.registry = registry;
        this.readerInterceptorsRegsRegistrations = readerInterceptorsRegsRegistrations;
        this.nameBindingPredicate = nameBindingPredicate;
    }

    @Override
    public boolean isReadable(@NonNull Argument<T> type, @Nullable MediaType mediaType) {
        // whether a reader reads this argument, with its annotations: the reader is selected again
        // with the argument of each value when it is read
        return registry.findReader(type, getMediaTypes(mediaType)).isPresent();
    }

    private List<MediaType> getMediaTypes(@Nullable MediaType mediaType) {
        return List.of(mediaType(mediaType));
    }

    /**
     * An entity without a content type is read as {@code application/octet-stream} (JAX-RS 4.2.1).
     */
    private static MediaType mediaType(@Nullable MediaType mediaType) {
        return mediaType == null ? MediaType.APPLICATION_OCTET_STREAM_TYPE : mediaType;
    }

    /**
     * Read the entity of a resource method (JAX-RS 4.2.1): with the JAX-RS reader of the
     * application that reads it, else with a Micronaut reader, through the reader interceptors.
     *
     * @param type        The type of the entity, with the annotations of its parameter
     * @param contentType The content type of the request, {@code null} for none
     * @param httpHeaders The headers of the request
     * @param entity      The entity
     * @return The value
     * @throws jakarta.ws.rs.NotSupportedException If no reader reads the entity as its type
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public @Nullable Object readEntity(Argument<?> type, @Nullable MediaType contentType, Headers httpHeaders, InputStream entity) {
        MediaType mediaType = mediaType(contentType);
        List<BeanRegistration<ReaderInterceptor>> interceptors = JaxRsRouteInterceptors.merge(readerInterceptorsRegsRegistrations, features.readerInterceptors());
        if (interceptors.isEmpty()) {
            return entityReader((Argument) type, mediaType).read((Argument) type, mediaType, httpHeaders, entity);
        }
        return new JaxRsInterceptedRead<Object>(interceptors, JaxRsRouteInterceptors.predicate(nameBindingPredicate)) {

            @Override
            protected @Nullable Object readFromAfterInterception(Argument<Object> type, @Nullable MediaType mediaType, Headers httpHeaders, InputStream inputStream) {
                // the interceptors can change the type and the media type
                return entityReader(type, Objects.requireNonNull(mediaType)).read(type, mediaType, httpHeaders, inputStream);
            }

        }.intercept((Argument) type, mediaType, httpHeaders, entity);
    }

    private MessageBodyReader<Object> entityReader(Argument<Object> type, MediaType mediaType) {
        List<MediaType> mediaTypes = List.of(mediaType);
        // a JAX-RS reader of the application that reads the value
        Optional<MessageBodyReader<Object>> reader = registry.findReader(type, mediaTypes);
        if (reader.isEmpty()) {
            // the standard types and the types of the Micronaut readers, like JSON
            reader = micronautReaders.get().findReader(type, mediaTypes);
        }
        return reader.orElseThrow(NotSupportedException::new);
    }

    @Override
    public @Nullable T read(@NonNull Argument<T> type, @Nullable MediaType contentType, @NonNull Headers httpHeaders, @NonNull ByteBuffer<?> byteBuffer) throws CodecException {
        MediaType mediaType = mediaType(contentType);
        List<BeanRegistration<ReaderInterceptor>> interceptors = JaxRsRouteInterceptors.merge(readerInterceptorsRegsRegistrations, features.readerInterceptors());
        if (interceptors.isEmpty()) {
            Optional<MessageBodyReader<T>> reader = registry.findSelectingReader(type, getMediaTypes(mediaType));
            if (reader.isPresent()) {
                return reader
                    .get().read(type, mediaType, httpHeaders, byteBuffer);
            }
            throw new CodecException("No reader registered for " + type + " with media type " + mediaType);
        }
        return new JaxRsInterceptedRead<T>(interceptors, JaxRsRouteInterceptors.predicate(nameBindingPredicate)) {

            @Override
            protected @Nullable T readFromAfterInterception(Argument<Object> type, @Nullable MediaType mediaType, Headers httpHeaders, InputStream inputStream) {
                Optional<MessageBodyReader<Object>> reader = registry.findSelectingReader(type, getMediaTypes(mediaType));
                if (reader.isPresent()) {
                    return (T) reader.get().read(type, mediaType, httpHeaders, inputStream);
                }
                throw new CodecException("No reader found for " + mediaType + " and type " + type);
            }

        }.intercept(type, mediaType, httpHeaders, byteBuffer.toInputStream());
    }

    @Override
    public @Nullable T read(@NonNull Argument<T> type, @Nullable MediaType contentType, @NonNull Headers httpHeaders, @NonNull InputStream inputStream) throws CodecException {
        MediaType mediaType = mediaType(contentType);
        List<BeanRegistration<ReaderInterceptor>> interceptors = JaxRsRouteInterceptors.merge(readerInterceptorsRegsRegistrations, features.readerInterceptors());
        if (interceptors.isEmpty()) {
            Optional<MessageBodyReader<T>> reader = registry.findSelectingReader(type, getMediaTypes(mediaType));
            if (reader.isPresent()) {
                return reader.get().read(type, mediaType, httpHeaders, inputStream);
            }
            throw new CodecException("No reader found for " + mediaType + " and type " + type);
        }
        return new JaxRsInterceptedRead<T>(interceptors, JaxRsRouteInterceptors.predicate(nameBindingPredicate)) {

            @Override
            protected @Nullable T readFromAfterInterception(Argument<Object> type, @Nullable MediaType mediaType, Headers httpHeaders, InputStream inputStream) {
                Optional<MessageBodyReader<Object>> reader = registry.findSelectingReader(type, getMediaTypes(mediaType));
                if (reader.isPresent()) {
                    return (T) reader.get().read(type, mediaType, httpHeaders, inputStream);
                }
                throw new CodecException("No reader found for " + mediaType + " and type " + type);
            }

        }.intercept(type, mediaType, httpHeaders, inputStream);
    }
}
