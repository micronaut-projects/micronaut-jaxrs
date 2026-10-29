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
package io.micronaut.jaxrs.client;

import io.micronaut.context.BeanRegistration;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.Headers;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMessage;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpMessage;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.jaxrs.common.JaxRsArgumentUtil;
import io.micronaut.jaxrs.common.ByteArrayByteBuffer;
import io.micronaut.jaxrs.common.HttpMessageEntityReader;
import io.micronaut.jaxrs.common.JaxRsInterceptedRead;
import io.micronaut.jaxrs.common.JaxRsInterceptedWrite;
import io.micronaut.jaxrs.common.JaxRsCurrentProviders;
import io.micronaut.jaxrs.common.JaxRsWriterInterceptorContextState;
import jakarta.ws.rs.ext.Providers;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.WriterInterceptor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
/**
 * The reading and the writing of the entities of a client configuration, through its readers,
 * writers and interceptors (JAX-RS 4.2, 6.4), else the body handlers of the HTTP client.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
final class JaxRsClientEntityIO {

    private final JaxRsClientProviders providers;
    // the body handlers of the HTTP client, which write the entities no JAX-RS writer writes
    private volatile @Nullable MessageBodyHandlerRegistry handlerRegistry;

    /**
     * @param providers       The providers of the configuration
     * @param handlerRegistry The body handlers of the HTTP client, {@code null} if not known yet
     */
    JaxRsClientEntityIO(JaxRsClientProviders providers, @Nullable MessageBodyHandlerRegistry handlerRegistry) {
        this.providers = providers;
        this.handlerRegistry = handlerRegistry;
    }

    /**
     * @return The body handlers of the HTTP client, {@code null} if not known yet
     */
    @Nullable MessageBodyHandlerRegistry handlerRegistry() {
        return handlerRegistry;
    }

    /**
     * @param handlerRegistry The body handlers of the HTTP client
     */
    void setHandlerRegistry(MessageBodyHandlerRegistry handlerRegistry) {
        this.handlerRegistry = handlerRegistry;
    }

    /**
     * @param properties The properties of the request the response is of, which the reader
     *                   interceptor contexts share
     * @return The reader of the entity of a response
     */
    public HttpMessageEntityReader createHttpMessageEntityReader(Map<String, Object> properties) {
        return new HttpMessageEntityReader() {

            @Override
            public <T> @Nullable T readEntity(HttpMessage<?> message, Argument<T> entityType) {
                Providers previous = JaxRsCurrentProviders.set(providers);
                try {
                    return readEntityOfConfiguration(message, entityType);
                } finally {
                    JaxRsCurrentProviders.restore(previous);
                }
            }

            private <T> @Nullable T readEntityOfConfiguration(HttpMessage<?> message, Argument<T> entityType) {
                ByteBuffer<?> byteBuffer = message.getBody(ByteBuffer.class)
                    .or(() -> message.getBody(byte[].class).map(ByteArrayByteBuffer::new))
                    .orElse(null);
                if (byteBuffer != null) {
                    List<BeanRegistration<ReaderInterceptor>> readerInterceptors = providers.getReaderInterceptors();
                    // an entity without a type is a stream of bytes (JAX-RS 4.2.1, step 1)
                    io.micronaut.http.MediaType mediaType = message.getContentType().orElse(MediaType.APPLICATION_OCTET_STREAM_TYPE);
                    HttpHeaders headers = message.getHeaders();
                    if (readerInterceptors.isEmpty()) {
                        io.micronaut.http.body.MessageBodyReader<T> reader = providers.findReader(entityType, mediaType);
                        if (reader != null) {
                            return reader.read(entityType, mediaType, headers, byteBuffer);
                        }
                    } else {
                        return new JaxRsInterceptedRead<T>(readerInterceptors, metadata -> true) {

                            @Override
                            protected @Nullable T readFromAfterInterception(Argument<Object> type, @Nullable MediaType mediaType, Headers httpHeaders, InputStream inputStream) {
                                io.micronaut.http.body.MessageBodyReader<Object> reader = providers.findReader(type, mediaType);
                                if (reader != null) {
                                    return (T) reader.read(type, mediaType, headers, inputStream);
                                }
                                throw new IllegalStateException("No reader found for type " + type.getType() + " and mediaType " + mediaType);
                            }

                        }.intercept(entityType, mediaType, headers, byteBuffer.toInputStream(), properties);
                    }
                }
                return super.readEntity(message, entityType);
            }
        };
    }

    <T> void writeBody(MutableHttpMessage<?> mutableHttpMessage, Argument<T> bodyArgument, @Nullable T body) {
        writeBody(mutableHttpMessage, bodyArgument, body, false, new LinkedHashMap<>());
    }

    /**
     * Write an entity: with the JAX-RS writers, else the ones of the HTTP client, through the
     * writer interceptors (JAX-RS 6.4). Without interceptors, an entity no JAX-RS writer writes
     * is left to the HTTP client, unless its bytes are required.
     *
     * @param mutableHttpMessage The message
     * @param bodyArgument       The type of the entity
     * @param body               The entity
     * @param bytes              Whether the entity must be written to bytes, e.g. to write them
     *                           through the entity stream a filter replaced
     * @param properties         The properties of the request, which the writer interceptor contexts share
     * @param <T>                The type of the entity
     */
    @SuppressWarnings("unchecked")
    <T> void writeBody(MutableHttpMessage<?> mutableHttpMessage, Argument<T> bodyArgument, @Nullable T body, boolean bytes,
                       Map<String, Object> properties) {
        if (body == null) {
            return;
        }
        Providers previous = JaxRsCurrentProviders.set(providers);
        try {
            writeBodyOfConfiguration(mutableHttpMessage, bodyArgument, body, bytes, properties);
        } finally {
            JaxRsCurrentProviders.restore(previous);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void writeBodyOfConfiguration(MutableHttpMessage<?> mutableHttpMessage, Argument<T> bodyArgument, T body, boolean bytes,
                                              Map<String, Object> properties) {
        if (body instanceof GenericEntity<?> genericEntity) {
            // the entity, written as its generic type
            writeBody(mutableHttpMessage, (Argument<Object>) JaxRsArgumentUtil.from(genericEntity), genericEntity.getEntity(), bytes, properties);
            return;
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        io.micronaut.http.MediaType mediaType = mutableHttpMessage.getContentType().orElse(MediaType.ALL_TYPE);

        final AtomicBoolean written = new AtomicBoolean(false);
        List<BeanRegistration<WriterInterceptor>> writerInterceptors = providers.getWriterInterceptors();
        if (writerInterceptors.isEmpty()) {
            io.micronaut.http.body.MessageBodyWriter<T> writer = providers.findWriter(bodyArgument, mediaType);
            if (writer == null && bytes) {
                writer = findClientWriter(bodyArgument, mediaType);
                mediaType = clientMediaType(mediaType);
            }
            if (writer != null) {
                writer.writeTo(bodyArgument, mediaType, body, mutableHttpMessage.getHeaders(), outputStream);
                written.set(true);
            }
        } else {
            new JaxRsInterceptedWrite<T, JaxRsWriterInterceptorContextState.ClassicState>(writerInterceptors, metadata -> true) {

                @Override
                protected void writeToAfterInterception(Argument<Object> argument, MediaType mediaType, JaxRsWriterInterceptorContextState.ClassicState state) {
                    io.micronaut.http.body.MessageBodyWriter<Object> writer = providers.findWriter(argument, mediaType);
                    MediaType writtenType = mediaType;
                    if (writer == null) {
                        writer = findClientWriter(argument, mediaType);
                        writtenType = clientMediaType(mediaType);
                    }
                    if (writer != null) {
                        writer.writeTo(argument, writtenType, state.getEntity(), mutableHttpMessage.getHeaders(), state.getOutputStream());
                        written.set(true);
                    }
                }

            }.intercept(bodyArgument, mediaType, new JaxRsWriterInterceptorContextState.ClassicState(mutableHttpMessage.getHeaders(), body, outputStream), properties);
        }

        if (written.get()) {
            mutableHttpMessage.body(outputStream.toByteArray());
        } else if (!providers.getWriterInterceptors().isEmpty()) {
            throw new IllegalStateException("Unknown entity type " + bodyArgument.getType());
        } else {
            mutableHttpMessage.body(body);
        }
    }

    /**
     * The writer of the HTTP client of an entity no JAX-RS writer writes.
     */
    private <T> io.micronaut.http.body.@Nullable MessageBodyWriter<T> findClientWriter(Argument<T> argument, MediaType mediaType) {
        MessageBodyHandlerRegistry registry = handlerRegistry;
        if (registry == null) {
            return null;
        }
        return registry.findWriter(argument, List.of(clientMediaType(mediaType))).orElse(null);
    }

    /**
     * The media type the HTTP client writes an entity with: JSON when the request has none.
     */
    private static MediaType clientMediaType(MediaType mediaType) {
        return mediaType.equals(MediaType.ALL_TYPE) ? MediaType.APPLICATION_JSON_TYPE : mediaType;
    }
}
