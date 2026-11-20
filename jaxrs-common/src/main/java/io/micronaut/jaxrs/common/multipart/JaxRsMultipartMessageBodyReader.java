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
package io.micronaut.jaxrs.common.multipart;

import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.exceptions.ContentLengthExceededException;
import io.micronaut.http.form.MalformedMultipartException;
import io.micronaut.http.form.MultipartReader;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.Providers;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The reader of a {@code multipart} entity as a {@code List<EntityPart>} (JAX-RS 3.1 section 4.2.4).
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@Consumes("multipart/*")
@Prototype
// the @Context fields the client injects, without reflection
@Introspected(accessKind = Introspected.AccessKind.FIELD, visibility = Introspected.Visibility.ANY)
@Internal
public final class JaxRsMultipartMessageBodyReader implements MessageBodyReader<List<EntityPart>> {

    /**
     * The limits of the parts: of their number and of the size of their headers, which the
     * multipart reader of core has by default, as each part is held in memory. The size of a
     * part and of the entity are bounded by the size of the request or response the entity is
     * read from.
     */
    static final MultipartReader.Limits LIMITS = new MultipartReader.Limits(
        MultipartReader.Limits.DEFAULT.maxParts(), MultipartReader.Limits.DEFAULT.maxHeaderSize(), -1, -1);

    // injected by the client, or by the bean context of the server
    @Context
    @Nullable Providers providers;

    /**
     * @param providers The providers of the server
     */
    @Inject
    void providers(Optional<Providers> providers) {
        this.providers = providers.orElse(null);
    }

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return Multipart.isEntityPartList(type, genericType);
    }

    @Override
    public List<EntityPart> readFrom(Class<List<EntityPart>> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                                     MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
        String boundary = mediaType.getParameters().get(Multipart.BOUNDARY);
        if (boundary == null) {
            throw new BadRequestException("The multipart entity has no boundary");
        }
        List<EntityPart> parts = new ArrayList<>();
        try {
            MultipartReader reader;
            try {
                reader = MultipartReader.of(entityStream, boundary, LIMITS);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("The multipart entity has an invalid boundary", e);
            }
            // an entity of only the close delimiter has no part; one without a delimiter is malformed
            for (MultipartReader.Part part = reader.next(); part != null; part = reader.next()) {
                parts.add(part(part));
            }
        } catch (MalformedMultipartException e) {
            throw new BadRequestException(e.getMessage(), e);
        } catch (ContentLengthExceededException e) {
            throw new WebApplicationException(e.getMessage(), e, Response.Status.REQUEST_ENTITY_TOO_LARGE);
        }
        return parts;
    }

    private EntityPart part(MultipartReader.Part part) throws IOException {
        String name = part.name();
        if (name == null) {
            throw new BadRequestException("A part of the multipart entity has no name");
        }
        MultivaluedMap<String, String> headers = JaxRsEntityPart.headers();
        for (String headerName : part.headers().names()) {
            headers.addAll(headerName, part.headers().getAll(headerName));
        }
        String contentType = headers.getFirst(HttpHeaders.CONTENT_TYPE);
        MediaType mediaType;
        try {
            mediaType = contentType == null ? MediaType.TEXT_PLAIN_TYPE : MediaType.valueOf(contentType);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("A part of the multipart entity has an invalid Content-Type", e);
        }
        if (contentType == null) {
            // the default type (RFC 7578 section 4.4) is the one of the headers too
            headers.putSingle(HttpHeaders.CONTENT_TYPE, mediaType.toString());
        }
        // the body of a part is valid until the next part is read
        return JaxRsEntityPart.read(name, part.fileName(), mediaType, headers, part.body().readAllBytes(), providers);
    }
}
