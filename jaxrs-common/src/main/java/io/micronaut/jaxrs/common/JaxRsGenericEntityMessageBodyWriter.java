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
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.codec.CodecException;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.ext.WriterInterceptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
final class JaxRsGenericEntityMessageBodyWriter<T> implements MessageBodyWriter<GenericEntity<T>> {

    private final JaxRsMessageBodyHandlerRegistry jaxRsMessageBodyHandlerRegistry;
    private final MessageBodyHandlerRegistry registry;
    private final List<BeanRegistration<WriterInterceptor>> writerInterceptorsRegistrations;
    private final NameBindingPredicate nameBindingPredicate;

    JaxRsGenericEntityMessageBodyWriter(JaxRsMessageBodyHandlerRegistry jaxRsMessageBodyHandlerRegistry,
                                        MessageBodyHandlerRegistry registry,
                                        List<BeanRegistration<WriterInterceptor>> writerInterceptorsRegistrations,
                                        NameBindingPredicate nameBindingPredicate) {
        this.jaxRsMessageBodyHandlerRegistry = jaxRsMessageBodyHandlerRegistry;
        this.registry = registry;
        this.writerInterceptorsRegistrations = writerInterceptorsRegistrations;
        this.nameBindingPredicate = nameBindingPredicate;
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
            new JaxRsInterceptedWrite<T>(writerInterceptorsRegistrations, nameBindingPredicate) {

                @Override
                protected void writeToAfterInterception(Argument<Object> argument,
                                                        MediaType mediaType,
                                                        Object entity,
                                                        MutableHeaders outgoingHeaders,
                                                        OutputStream outputStream) {
                    write(argument, mediaType, entity, outgoingHeaders, outputStream);
                }

            }.intercept(argument, mediaType, entity, outgoingHeaders, outputStream);
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

}
