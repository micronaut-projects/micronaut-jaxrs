/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.jaxrs.providers;

import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import jakarta.activation.DataSource;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbException;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;
import org.jspecify.annotations.Nullable;

import javax.xml.transform.Source;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;

/**
 * The standard provider of JSON-B for the JSON media types (JAX-RS 4.2.4, 11.2.7), when a JSON-B
 * implementation is present. The {@link Jsonb} of a type is the one a
 * {@code ContextResolver<Jsonb>} of the application gives, else a default one. The types of the
 * other standard providers are left to them.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@Singleton
@Internal
@Requires(classes = Jsonb.class)
@Requires(condition = JsonbImplementationCondition.class)
@Consumes({MediaType.APPLICATION_JSON, "text/json", "application/*+json"})
@Produces({MediaType.APPLICATION_JSON, "text/json", "application/*+json"})
public final class JaxRsJsonbMessageBodyReaderWriter implements MessageBodyReader<Object>, MessageBodyWriter<Object> {

    private static final List<Class<?>> STANDARD_TYPES = List.of(
        String.class, byte[].class, char[].class, InputStream.class, Reader.class, File.class, DataSource.class,
        Source.class, StreamingOutput.class, MultivaluedMap.class, Form.class
    );

    // the providers of the client, which injects them
    @Context
    @Nullable Providers clientProviders;

    private final @Nullable BeanProvider<Providers> providers;
    private volatile @Nullable Jsonb defaultJsonb;

    @Inject
    JaxRsJsonbMessageBodyReaderWriter(BeanProvider<Providers> providers) {
        this.providers = providers;
    }

    /**
     * A provider for the client.
     */
    JaxRsJsonbMessageBodyReaderWriter() {
        this.providers = null;
    }

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return isJson(mediaType) && !isStandard(type);
    }

    @Override
    public Object readFrom(Class<Object> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                           MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
        byte[] entity = entityStream.readAllBytes();
        try {
            return jsonb(type, mediaType).fromJson(new ByteArrayInputStream(entity), genericType == null ? type : genericType);
        } catch (JsonbException e) {
            throw new BadRequestException(e);
        }
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return isJson(mediaType) && !isStandard(type);
    }

    @Override
    public void writeTo(Object value, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
        try {
            jsonb(type, mediaType).toJson(value, genericType == null ? type : genericType, entityStream);
        } catch (JsonbException e) {
            throw new InternalServerErrorException(e);
        }
    }

    private static boolean isJson(@Nullable MediaType mediaType) {
        if (mediaType == null) {
            return false;
        }
        String subtype = mediaType.getSubtype();
        return ("application".equals(mediaType.getType()) || "text".equals(mediaType.getType()))
            && ("json".equals(subtype) || subtype.endsWith("+json"));
    }

    private static boolean isStandard(Class<?> type) {
        if (type.isPrimitive()) {
            return true;
        }
        for (Class<?> standard : STANDARD_TYPES) {
            if (standard.isAssignableFrom(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The JSON-B of a type: the one a context resolver of the application gives, else the default
     * one.
     */
    private Jsonb jsonb(Class<?> type, @Nullable MediaType mediaType) {
        Providers providers = clientProviders != null ? clientProviders
            : this.providers != null && this.providers.isPresent() ? this.providers.get() : null;
        ContextResolver<Jsonb> resolver = providers == null ? null : providers.getContextResolver(Jsonb.class, mediaType);
        if (resolver != null) {
            Jsonb jsonb = resolver.getContext(type);
            if (jsonb != null) {
                return jsonb;
            }
        }
        Jsonb jsonb = defaultJsonb;
        if (jsonb == null) {
            synchronized (this) {
                jsonb = defaultJsonb;
                if (jsonb == null) {
                    jsonb = JsonbBuilder.create();
                    defaultJsonb = jsonb;
                }
            }
        }
        return jsonb;
    }
}
