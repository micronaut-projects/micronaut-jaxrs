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
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.http.ByteBodyHttpResponse;
import io.micronaut.http.ByteBodyHttpResponseWrapper;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.body.ByteBodyFactory;
import io.micronaut.http.body.CloseableByteBody;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.body.ResponseBodyWriter;
import io.micronaut.http.codec.CodecException;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.WriterInterceptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Optional;

/**
 * The writer of {@link GenericEntity}.
 *
 * @param <T> The entity type
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Internal
@Singleton
final class JaxRsGenericEntityMessageBodyWriter<T> implements ResponseBodyWriter<GenericEntity<T>> {

    private final JaxRsContainerMessageBodyHandlerRegistry jaxRsMessageBodyHandlerRegistry;
    private final MessageBodyHandlerRegistry registry;
    private final List<BeanRegistration<WriterInterceptor>> writerInterceptorsRegistrations;
    private final NameBindingPredicate nameBindingPredicate;

    JaxRsGenericEntityMessageBodyWriter(JaxRsContainerMessageBodyHandlerRegistry jaxRsMessageBodyHandlerRegistry,
                                        MessageBodyHandlerRegistry registry,
                                        List<BeanRegistration<WriterInterceptor>> writerInterceptorsRegistrations,
                                        NameBindingPredicate nameBindingPredicate) {
        this.jaxRsMessageBodyHandlerRegistry = jaxRsMessageBodyHandlerRegistry;
        this.registry = registry;
        this.writerInterceptorsRegistrations = writerInterceptorsRegistrations;
        this.nameBindingPredicate = nameBindingPredicate;
    }

    @Override
    public boolean isBlocking() {
        return true;
    }

    @Override
    public void writeTo(@NonNull Argument<GenericEntity<T>> type,
                        @NonNull MediaType mediaType,
                        GenericEntity<T> genericEntity,
                        @NonNull MutableHeaders outgoingHeaders,
                        @NonNull OutputStream outputStream) throws CodecException {
        Argument<T> argument;
        final OutputStream originalOutputStream = outputStream;
        ByteArrayOutputStream delegateEntityStream = null;
        if (genericEntity instanceof JaxRsGenericEntity<T> jaxRsGenericEntity) {
            argument = jaxRsGenericEntity.asArgument();
            delegateEntityStream = jaxRsGenericEntity.getDelegateEntityStream();
            OutputStream customEntityStream = jaxRsGenericEntity.getCustomEntityStream();
            if (customEntityStream != null) {
                outputStream = customEntityStream;
            }
        } else {
            argument = JaxRsArgumentUtil.from(genericEntity);
        }
        T entity = genericEntity.getEntity();

        if (writerInterceptorsRegistrations.isEmpty()) {
            write(argument, mediaType, entity, outgoingHeaders, outputStream);
        } else {
            new JaxRsInterceptedWrite<T, JaxRsWriterInterceptorContextState.ClassicState>(writerInterceptorsRegistrations, nameBindingPredicate) {

                @Override
                protected void writeToAfterInterception(Argument<Object> argument,
                                                        MediaType mediaType,
                                                        JaxRsWriterInterceptorContextState.ClassicState state) {
                    write(argument, mediaType, state.getEntity(), outgoingHeaders, state.getOutputStream());
                }

            }.intercept(argument, mediaType, new JaxRsWriterInterceptorContextState.ClassicState(outgoingHeaders, entity, outputStream));
        }

        if (delegateEntityStream != null) {
            try {
                originalOutputStream.write(delegateEntityStream.toByteArray());
            } catch (IOException e) {
                throw new JaxRsIOException(e);
            }
        }
    }

    private <K> void write(Argument<K> argument, MediaType mediaType, K entity, MutableHeaders outgoingHeaders, OutputStream outputStream) {
        List<MediaType> mediaTypes = List.of(mediaType);
        // JaxRs writers
        Optional<MessageBodyWriter<K>> writer = jaxRsMessageBodyHandlerRegistry.findWriter(argument, mediaTypes);
        if (writer.isEmpty()) {
            // Micronaut HTTP writers
            writer = registry.findWriter(argument, mediaTypes);
        }
        if (writer.isEmpty()) {
            Optional<MessageBodyWriter<String>> stringWriter = registry.findWriter(Argument.STRING, mediaTypes);
            if (stringWriter.isPresent()) {
                stringWriter.get().writeTo(Argument.STRING, mediaType, entity.toString(), outgoingHeaders, outputStream);
            } else {
                throw new CodecException("Could not find MessageBodyWriter for media type " + mediaType + " for argument " + argument);
            }
        } else {
            writer.get().createSpecific(argument).writeTo(argument, mediaType, entity, outgoingHeaders, outputStream);
        }
    }

    @Override
    public @NonNull ByteBodyHttpResponse<?> write(@NonNull ByteBodyFactory bodyFactory, @NonNull HttpRequest<?> request, @NonNull MutableHttpResponse<GenericEntity<T>> httpResponse, @NonNull Argument<GenericEntity<T>> type, @NonNull MediaType mediaType, GenericEntity<T> genericEntity) throws CodecException {
        var s = new ByteBodyState(bodyFactory, request, httpResponse, mediaType, genericEntity) {
            // this implementation, instead of using the body field, tries to keep the whole
            // ByteBodyHttpResponse for as long as possible.

            ByteBodyHttpResponse<?> innerResponse;

            @Override
            void finishIntercepted() {
                if (innerResponse != null) {
                    body = innerResponse.byteBody().move();
                    innerResponse.close();
                }
            }

            @Override
            <U> void writeInner0(ResponseBodyWriter<U> rbw, Argument<U> argument, U entity) {
                if (outputIntercepted) {
                    super.writeInner0(rbw, argument, entity);
                } else {
                    innerResponse = rbw.write(bodyFactory, request, (MutableHttpResponse<U>) httpResponse, argument, mediaType, entity);
                }
            }
        };
        s.run();
        if (s.outputIntercepted) {
            return ByteBodyHttpResponseWrapper.wrap(httpResponse, s.body);
        } else {
            return s.innerResponse;
        }
    }

    @Override
    public @NonNull CloseableByteBody writePiece(@NonNull ByteBodyFactory bodyFactory, @NonNull HttpRequest<?> request, @NonNull HttpResponse<?> response, @NonNull Argument<GenericEntity<T>> type, @NonNull MediaType mediaType, GenericEntity<T> genericEntity) throws CodecException {
        var s = new ByteBodyState(bodyFactory, request, response, mediaType, genericEntity);
        s.run();
        return s.body;
    }

    /**
     * {@link JaxRsWriterInterceptorContextState} implementation that handles
     * {@link #getOutputStream()} lazily to avoid unnecessary buffering. Note that there is an
     * anonymous subclass in
     * {@link #write(ByteBodyFactory, HttpRequest, MutableHttpResponse, Argument, MediaType, GenericEntity)}.
     */
    non-sealed class ByteBodyState implements JaxRsWriterInterceptorContextState {
        // write method parameters
        final ByteBodyFactory bodyFactory;
        final HttpRequest<?> request;
        final HttpResponse<?> response;
        MediaType mediaType;

        /**
         * Argument contained in the GenericEntity.
         */
        Argument<T> argument;

        /**
         * JAX-RS view of the response headers.
         */
        final MultivaluedMap<String, Object> headers;
        T entity;

        /**
         * When {@code true}, {@link #getOutputStream()} or {@link #setOutputStream(OutputStream)}
         * has been called, so we will have to transfer the downstream
         * {@link io.micronaut.http.body.ByteBody} through an {@link OutputStream}.
         */
        boolean outputIntercepted = false;
        // TODO: we can use https://github.com/micronaut-projects/micronaut-core/pull/11934 in core 4.10.x
        /**
         * Destination buffer that will be turned into the final
         * {@link io.micronaut.http.body.ByteBody}.
         */
        ByteArrayOutputStream bufferStream;
        /**
         * Current stream for {@link #getOutputStream()}. Sometimes it's {@link #bufferStream},
         * sometimes it's a wrapper around it, etc.
         */
        OutputStream outputStream;

        /**
         * {@link CloseableByteBody} from the downstream {@link ResponseBodyWriter}. At the end of
         * {@link #run()}, this is replaced by the data from {@link #bufferStream} if applicable.
         */
        CloseableByteBody body;

        ByteBodyState(ByteBodyFactory bodyFactory, HttpRequest<?> request, HttpResponse<?> response, MediaType mediaType, GenericEntity<T> genericEntity) {
            this.bodyFactory = bodyFactory;
            this.request = request;
            this.response = response;
            this.headers = new JaxRsObjectHeadersMultivaluedMap(response.getHeaders());
            this.mediaType = mediaType;
            if (genericEntity instanceof JaxRsGenericEntity<T> jaxRsGenericEntity) {
                argument = jaxRsGenericEntity.asArgument();
                ByteArrayOutputStream delegateEntityStream = jaxRsGenericEntity.getDelegateEntityStream();
                if (delegateEntityStream != null) {
                    outputIntercepted = true;
                    bufferStream = delegateEntityStream;
                    outputStream = delegateEntityStream;
                }
                OutputStream customEntityStream = jaxRsGenericEntity.getCustomEntityStream();
                if (customEntityStream != null) {
                    setOutputStream(customEntityStream);
                }
            } else {
                argument = JaxRsArgumentUtil.from(genericEntity);
            }
            this.entity = genericEntity.getEntity();
        }

        @Override
        public Object getEntity() {
            return entity;
        }

        @Override
        public void setEntity(Object entity) {
            this.entity = (T) entity;
        }

        @Override
        public OutputStream getOutputStream() {
            if (!outputIntercepted) {
                bufferStream = new ByteArrayOutputStream();
                outputStream = bufferStream;
                outputIntercepted = true;
            }
            return outputStream;
        }

        @Override
        public void setOutputStream(OutputStream outputStream) {
            if (!outputIntercepted) {
                // this will probably remain empty, but that's what the caller wants if they didn't
                // call getOutputStream first
                bufferStream = new ByteArrayOutputStream();
                outputIntercepted = true;
            }
            this.outputStream = outputStream;
        }

        @Override
        public MultivaluedMap<String, Object> getHeaders() {
            return headers;
        }

        final void run() {
            if (writerInterceptorsRegistrations.isEmpty()) {
                writeInner();
            } else {
                try {
                    new JaxRsInterceptedWrite<T, ByteBodyState>(writerInterceptorsRegistrations, nameBindingPredicate) {

                        @Override
                        protected void writeToAfterInterception(Argument<Object> argument,
                                                                MediaType mediaType,
                                                                ByteBodyState state) {
                            ByteBodyState.this.argument = (Argument<T>) argument;
                            ByteBodyState.this.mediaType = mediaType;
                            writeInner();
                        }
                    }.intercept(argument, mediaType, this);
                } catch (CodecException e) {
                    throw e;
                } catch (Exception e) {
                    throw new CodecException("Failed to run JAX-RS WriterInterceptor", e);
                }
            }
            if (outputIntercepted) {
                finishIntercepted();
                try (InputStream is = body.toInputStream()) {
                    is.transferTo(outputStream);
                } catch (IOException e) {
                    throw new CodecException("Failed to buffer wrapped body", e);
                }
                body = bodyFactory.adapt(bufferStream.toByteArray());
            }
        }

        void finishIntercepted() {
        }

        final void writeInner() {
            List<MediaType> mediaTypes = List.of(mediaType);
            // JaxRs writers
            Optional<MessageBodyWriter<T>> writer = jaxRsMessageBodyHandlerRegistry.findWriter(this.argument, mediaTypes);
            if (writer.isEmpty()) {
                // Micronaut HTTP writers
                writer = registry.findWriter(this.argument, mediaTypes);
            }
            if (writer.isEmpty()) {
                Optional<MessageBodyWriter<String>> stringWriter = registry.findWriter(Argument.STRING, mediaTypes);
                if (stringWriter.isPresent()) {
                    writeInner0(ResponseBodyWriter.wrap(stringWriter.get()), Argument.STRING, this.entity.toString());
                } else {
                    throw new CodecException("Could not find MessageBodyWriter for media type " + mediaType + " for argument " + this.argument);
                }
            } else {
                writeInner0(ResponseBodyWriter.wrap(writer.get().createSpecific(argument)), argument, entity);
            }
        }

        <U> void writeInner0(ResponseBodyWriter<U> rbw, Argument<U> argument, U entity) {
            body = rbw.writePiece(bodyFactory, request, response, argument, mediaType, entity);
        }
    }
}
