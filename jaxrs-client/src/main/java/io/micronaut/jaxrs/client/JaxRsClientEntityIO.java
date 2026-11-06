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

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.context.BeanRegistration;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.Headers;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMessage;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpMessage;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.jaxrs.common.JaxRsArgumentUtil;
import io.micronaut.jaxrs.common.ByteArrayByteBuffer;
import io.micronaut.jaxrs.common.HttpMessageEntityReader;
import io.micronaut.jaxrs.common.JaxRsIOException;
import io.micronaut.jaxrs.common.JaxRsInterceptedRead;
import io.micronaut.jaxrs.common.JaxRsInterceptedWrite;
import io.micronaut.jaxrs.common.JaxRsCurrentProviders;
import io.micronaut.jaxrs.common.JaxRsWriterInterceptorContextState;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.ext.Providers;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.WriterInterceptor;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
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
                } catch (JaxRsIOException e) {
                    // the failure of a reader is a processing failure (JAX-RS, Response#readEntity)
                    throw new ProcessingException(e.getCause());
                } catch (ProcessingException | WebApplicationException e) {
                    throw e;
                } catch (RuntimeException e) {
                    // e.g. a reader rejecting malformed content
                    throw new ProcessingException(e);
                } finally {
                    JaxRsCurrentProviders.restore(previous);
                }
            }

            @Override
            public InputStream readEntityStream(HttpMessage<?> message, InputStream entityStream) {
                Providers previous = JaxRsCurrentProviders.set(providers);
                try {
                    MediaType mediaType = message.getContentType().orElse(MediaType.APPLICATION_OCTET_STREAM_TYPE);
                    HttpHeaders headers = message.getHeaders();
                    List<BeanRegistration<ReaderInterceptor>> readerInterceptors = providers.getReaderInterceptors();
                    if (readerInterceptors.isEmpty()) {
                        // the stream is read by the reader of streams, e.g. one of the application
                        InputStream read = (InputStream) readStream(Argument.of(InputStream.class), mediaType, headers, entityStream);
                        return read == null ? InputStream.nullInputStream() : read;
                    }
                    InputStream read = new ClientInterceptedRead<InputStream>(readerInterceptors) {

                        @Override
                        protected @Nullable InputStream readFromAfterInterception(Argument<Object> type, @Nullable MediaType mediaType, Headers httpHeaders, InputStream inputStream) {
                            return (InputStream) readStream(type, mediaType, headers, inputStream);
                        }

                    }.read(Argument.of(InputStream.class), mediaType, headers, entityStream, properties);
                    return read == null ? InputStream.nullInputStream() : read;
                } catch (JaxRsIOException e) {
                    throw new ProcessingException(e.getCause());
                } catch (ProcessingException | WebApplicationException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw new ProcessingException(e);
                } finally {
                    JaxRsCurrentProviders.restore(previous);
                }
            }

            @SuppressWarnings("unchecked")
            private @Nullable Object readStream(Argument<?> type, @Nullable MediaType mediaType, HttpHeaders headers, InputStream inputStream) {
                Argument<Object> argument = (Argument<Object>) type;
                io.micronaut.http.body.MessageBodyReader<Object> reader = providers.findReader(argument, mediaType);
                if (reader != null) {
                    return reader.read(argument, mediaType, headers, inputStream);
                }
                throw new IllegalStateException("No reader found for type " + type.getType() + " and mediaType " + mediaType);
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
                        return new ClientInterceptedRead<T>(readerInterceptors) {

                            @Override
                            protected @Nullable T readFromAfterInterception(Argument<Object> type, @Nullable MediaType mediaType, Headers httpHeaders, InputStream inputStream) {
                                io.micronaut.http.body.MessageBodyReader<Object> reader = providers.findReader(type, mediaType);
                                if (reader != null) {
                                    return (T) reader.read(type, mediaType, headers, inputStream);
                                }
                                throw new IllegalStateException("No reader found for type " + type.getType() + " and mediaType " + mediaType);
                            }

                        }.read(entityType, mediaType, headers, byteBuffer.toInputStream(), properties);
                    }
                }
                return super.readEntity(message, entityType);
            }
        };
    }

    /**
     * Write the entity of a response a request filter aborted with: it is not sent, the writer
     * interceptors, which intercept the outbound entities, do not run (JAX-RS 6.2).
     *
     * @param mutableHttpMessage The response
     * @param bodyArgument       The type of the entity
     * @param body               The entity
     * @param <T>                The type of the entity
     */
    <T> void writeAbortedBody(MutableHttpMessage<?> mutableHttpMessage, Argument<T> bodyArgument, @Nullable T body) {
        if (body == null) {
            return;
        }
        Providers previous = JaxRsCurrentProviders.set(providers);
        try {
            writeBodyOfConfiguration(mutableHttpMessage, bodyArgument, body, false, new LinkedHashMap<>(), List.of(), null);
        } finally {
            JaxRsCurrentProviders.restore(previous);
        }
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
     * @param upload             Sends the request once the entity is larger than the client
     *                           buffers, the rest of it as it is written, {@code null} to buffer it
     * @param <T>                The type of the entity
     */
    @SuppressWarnings("unchecked")
    <T> void writeBody(MutableHttpMessage<?> mutableHttpMessage, Argument<T> bodyArgument, @Nullable T body, boolean bytes,
                       Map<String, Object> properties, @Nullable JaxRsStreamedUpload upload) {
        if (body == null) {
            return;
        }
        Providers previous = JaxRsCurrentProviders.set(providers);
        try {
            writeBodyOfConfiguration(mutableHttpMessage, bodyArgument, body, bytes, properties, providers.getWriterInterceptors(), upload);
        } finally {
            JaxRsCurrentProviders.restore(previous);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void writeBodyOfConfiguration(MutableHttpMessage<?> mutableHttpMessage, Argument<T> bodyArgument, T body, boolean bytes,
                                              Map<String, Object> properties, List<BeanRegistration<WriterInterceptor>> writerInterceptors,
                                              @Nullable JaxRsStreamedUpload upload) {
        if (body instanceof GenericEntity<?> genericEntity) {
            // the entity, written as its raw type and its generic type (JAX-RS 4.2.2)
            Object entity = genericEntity.getEntity();
            if (entity != null) {
                writeBodyOfConfiguration(mutableHttpMessage, argument(genericEntity, AnnotationMetadata.EMPTY_METADATA), entity, bytes, properties,
                    writerInterceptors, upload);
            }
            return;
        }
        // in memory, unless the entity is larger than the client buffers: then it is sent as it is written
        var outputStream = new SpillingOutputStream(mutableHttpMessage, upload);
        io.micronaut.http.MediaType mediaType = mutableHttpMessage.getContentType().orElse(MediaType.ALL_TYPE);

        final AtomicBoolean written = new AtomicBoolean(false);
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
                    if (writer == null) {
                        // proceeding reached the writers: none writes the entity
                        throw new IllegalStateException("Unknown entity type " + argument.getType());
                    }
                    writer.writeTo(argument, writtenType, state.getEntity(), mutableHttpMessage.getHeaders(), state.getOutputStream());
                }

            }.intercept(bodyArgument, mediaType, new JaxRsWriterInterceptorContextState.ClassicState(mutableHttpMessage.getHeaders(), body, outputStream), properties);
        }

        if (written.get() || !writerInterceptors.isEmpty()) {
            // what the writer wrote, or the interceptors, which may write the entity without proceeding
            if (!outputStream.spilled()) {
                mutableHttpMessage.body(outputStream.toByteArray());
            }
        } else {
            mutableHttpMessage.body(body);
        }
    }

    /**
     * The argument of the entity of a generic entity: its raw type, the class of the entity, and
     * its generic type, as the writers are given them (JAX-RS 4.2.2, {@code GenericEntity#getRawType}).
     *
     * @param genericEntity The generic entity
     * @param annotations   The annotations of the entity
     * @return The argument
     */
    @SuppressWarnings("unchecked")
    static Argument<Object> argument(GenericEntity<?> genericEntity, AnnotationMetadata annotations) {
        return JaxRsArgumentUtil.of((Class<Object>) genericEntity.getRawType(), JaxRsArgumentUtil.from(genericEntity), annotations);
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

    /**
     * The stream an entity is written to: in memory, until it is larger than the client buffers
     * with an upload: then the request is sent, and the rest of the entity as it is written.
     */
    private static final class SpillingOutputStream extends OutputStream {
        private final MutableHttpMessage<?> message;
        private final @Nullable JaxRsStreamedUpload upload;
        private @Nullable ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private @Nullable OutputStream spilled;

        SpillingOutputStream(MutableHttpMessage<?> message, @Nullable JaxRsStreamedUpload upload) {
            this.message = message;
            this.upload = upload;
        }

        boolean spilled() {
            return spilled != null;
        }

        byte[] toByteArray() {
            ByteArrayOutputStream written = buffer;
            return written == null ? new byte[0] : written.toByteArray();
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            OutputStream out = spilled;
            if (out != null) {
                out.write(b, off, len);
                return;
            }
            ByteArrayOutputStream written = buffer;
            if (upload != null && written != null && message instanceof MutableHttpRequest<?> request
                && written.size() + (long) len > JaxRsStreamedUpload.THRESHOLD) {
                // the headers are sent as they are now
                out = upload.start(request, written.toByteArray());
                buffer = null;
                spilled = out;
                out.write(b, off, len);
                return;
            }
            if (written != null) {
                written.write(b, off, len);
            }
        }
    }

    /**
     * A read through the reader interceptors of the client: the streams they set, which a stream
     * entity reads of, are closed with that entity, e.g. when the response is closed.
     *
     * @param <T> The type of the entity
     */
    private abstract static class ClientInterceptedRead<T> extends JaxRsInterceptedRead<T> {

        private @Nullable Runnable closer;

        ClientInterceptedRead(List<BeanRegistration<ReaderInterceptor>> readerInterceptors) {
            super(readerInterceptors, metadata -> true);
        }

        @Override
        protected void closeAfterUse(Runnable closer) {
            this.closer = closer;
        }

        /**
         * Read through the interceptors.
         *
         * @param type        The type
         * @param mediaType   The media type
         * @param headers     The headers
         * @param inputStream The entity
         * @param properties  The properties the interceptor contexts share
         * @return The entity, a stream that also closes the streams of the interceptors
         */
        @SuppressWarnings("unchecked")
        final @Nullable T read(Argument<T> type, MediaType mediaType, Headers headers, InputStream inputStream, Map<String, Object> properties) {
            T value = intercept(type, mediaType, headers, inputStream, properties);
            Runnable close = closer;
            if (close != null && value instanceof InputStream stream) {
                return (T) new FilterInputStream(stream) {
                    private boolean closed;

                    @Override
                    public void close() throws IOException {
                        if (closed) {
                            return;
                        }
                        closed = true;
                        try {
                            super.close();
                        } finally {
                            close.run();
                        }
                    }
                };
            }
            if (close != null && type.getType() == Reader.class && value instanceof Reader reader) {
                // a reader of the streams the interceptors set closes them too, unless a subtype is
                // requested, which the wrapper is not
                return (T) new FilterReader(reader) {
                    private boolean closed;

                    @Override
                    public void close() throws IOException {
                        if (closed) {
                            return;
                        }
                        closed = true;
                        try {
                            super.close();
                        } finally {
                            close.run();
                        }
                    }
                };
            }
            return value;
        }
    }
}
