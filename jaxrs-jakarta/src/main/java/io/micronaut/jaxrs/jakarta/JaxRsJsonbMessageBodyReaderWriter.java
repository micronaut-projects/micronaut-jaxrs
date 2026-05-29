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
package io.micronaut.jaxrs.jakarta;

import io.micronaut.context.annotation.Prototype;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.jaxrs.common.JaxRsMessageBodyProvider;
import jakarta.activation.DataSource;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;
import jakarta.xml.bind.JAXBElement;
import org.jspecify.annotations.Nullable;

import javax.xml.transform.Source;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Locale;
import java.util.Map;

/**
 * Jakarta REST JSON-B entity provider used by the compliance aggregate.
 *
 * @param <T> The entity type
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@JaxRsMessageBodyProvider(
    readerType = Object.class,
    readerTypeVariable = true,
    writerType = Object.class,
    writerTypeVariable = true,
    consumes = { MediaType.APPLICATION_JSON, "application/*+json" },
    produces = { MediaType.APPLICATION_JSON, "application/*+json" }
)
@Consumes({ MediaType.APPLICATION_JSON, "application/*+json" })
@Produces({ MediaType.APPLICATION_JSON, "application/*+json" })
@Prototype
@Requires(classes = Jsonb.class)
@Internal
public final class JaxRsJsonbMessageBodyReaderWriter<T> implements MessageBodyReader<T>, MessageBodyWriter<T> {
    private final @Nullable Providers providers;
    private final @Nullable JsonbResolver jsonbResolver;

    /**
     * Constructor used by the server container.
     *
     * @param providers The Jakarta REST providers registry
     */
    @Inject
    public JaxRsJsonbMessageBodyReaderWriter(Providers providers) {
        this(providers, null);
    }

    /**
     * Constructor used by the client aggregate customizer.
     *
     * @param jsonbResolver The client-side resolver lookup
     */
    public JaxRsJsonbMessageBodyReaderWriter(JsonbResolver jsonbResolver) {
        this(null, jsonbResolver);
    }

    private JaxRsJsonbMessageBodyReaderWriter(@Nullable Providers providers,
                                              @Nullable JsonbResolver jsonbResolver) {
        this.providers = providers;
        this.jsonbResolver = jsonbResolver;
    }

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return isSupportedType(type) && isJson(mediaType);
    }

    @Override
    public T readFrom(Class<T> type,
                      Type genericType,
                      Annotation[] annotations,
                      MediaType mediaType,
                      MultivaluedMap<String, String> httpHeaders,
                      InputStream entityStream) throws IOException, WebApplicationException {
        try {
            return jsonb(type, mediaType).fromJson(entityStream, genericType);
        } catch (JsonbException e) {
            throw new ProcessingException("Cannot read JSON-B entity of type " + type.getName(), e);
        }
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return isSupportedType(type) && isJson(mediaType);
    }

    @Override
    public void writeTo(T value,
                        Class<?> type,
                        Type genericType,
                        Annotation[] annotations,
                        MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders,
                        OutputStream entityStream) throws IOException, WebApplicationException {
        try {
            jsonb(type, mediaType).toJson(value, genericType, entityStream);
        } catch (JsonbException e) {
            throw new ProcessingException("Cannot write JSON-B entity of type " + type.getName(), e);
        }
    }

    private Jsonb jsonb(Class<?> type, MediaType mediaType) {
        Jsonb resolved = null;
        if (jsonbResolver != null) {
            resolved = jsonbResolver.resolve(type, mediaType);
        }
        if (resolved == null && providers != null) {
            ContextResolver<Jsonb> resolver = providers.getContextResolver(Jsonb.class, mediaType);
            if (resolver != null) {
                resolved = resolver.getContext(type);
            }
        }
        return resolved == null ? DefaultJsonb.INSTANCE : resolved;
    }

    private static boolean isSupportedType(Class<?> type) {
        return type != String.class
            && type != byte[].class
            && !InputStream.class.isAssignableFrom(type)
            && !Reader.class.isAssignableFrom(type)
            && !StreamingOutput.class.isAssignableFrom(type)
            && !DataSource.class.isAssignableFrom(type)
            && !Source.class.isAssignableFrom(type)
            && !JAXBElement.class.isAssignableFrom(type)
            && !MultivaluedMap.class.isAssignableFrom(type)
            && !Map.class.isAssignableFrom(type);
    }

    private static boolean isJson(MediaType mediaType) {
        if (mediaType == null || mediaType.isWildcardType() || mediaType.isWildcardSubtype()) {
            return false;
        }
        if (!MediaType.APPLICATION_JSON_TYPE.getType().equalsIgnoreCase(mediaType.getType())) {
            return false;
        }
        String subtype = mediaType.getSubtype().toLowerCase(Locale.ROOT);
        return "json".equals(subtype) || subtype.endsWith("+json");
    }

    /**
     * Resolves JSON-B instances for client-side provider lookups.
     */
    @FunctionalInterface
    public interface JsonbResolver {
        /**
         * Resolves a JSON-B instance for the requested entity type and media type.
         *
         * @param type The entity type
         * @param mediaType The selected media type
         * @return The JSON-B instance, or {@code null} to use the default instance
         */
        @Nullable Jsonb resolve(Class<?> type, MediaType mediaType);
    }

    private static final class DefaultJsonb {
        private static final Jsonb INSTANCE = JsonbBuilder.create();
    }
}
