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
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.Providers;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] CLOSE = {'-', '-'};
    private static final byte[] HEADERS_END = {'\r', '\n', '\r', '\n'};

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
        byte[] entity = entityStream.readAllBytes();
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        // every delimiter but the first follows a line break
        byte[] nextDelimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        // the first delimiter starts the entity, or a line after a preamble (RFC 2046 section 5.1.1)
        int position;
        if (startsWith(entity, 0, delimiter) && isDelimiterEnd(entity, delimiter.length)) {
            position = delimiter.length;
        } else {
            position = delimiterIndex(entity, nextDelimiter, 0);
            if (position < 0) {
                throw new BadRequestException("The multipart entity has no part");
            }
            position += nextDelimiter.length;
        }
        List<EntityPart> parts = new ArrayList<>();
        while (!startsWith(entity, position, CLOSE)) {
            position = indexOf(entity, CRLF, position);
            if (position < 0) {
                throw new BadRequestException("The multipart entity is not terminated");
            }
            position += CRLF.length;
            int headersEnd = startsWith(entity, position, CRLF) ? position : indexOf(entity, HEADERS_END, position);
            int nextPart = delimiterIndex(entity, nextDelimiter, position);
            if (headersEnd > position && nextPart >= 0 && nextPart < headersEnd) {
                // the headers of the part must end before the next delimiter, else those of the next part are taken
                throw new BadRequestException("A part of the multipart entity has no end of its headers");
            }
            int end = headersEnd < 0 ? -1 : delimiterIndex(entity, nextDelimiter, headersEnd);
            if (end < 0) {
                throw new BadRequestException("The multipart entity is not terminated");
            }
            MultivaluedMap<String, String> headers = headers(new String(entity, position, headersEnd - position, StandardCharsets.UTF_8));
            int contentStart = headersEnd + (headersEnd == position ? CRLF.length : HEADERS_END.length);
            parts.add(part(headers, Arrays.copyOfRange(entity, contentStart, end)));
            position = end + nextDelimiter.length;
        }
        return parts;
    }

    private EntityPart part(MultivaluedMap<String, String> headers, byte[] content) {
        String disposition = headers.getFirst(Multipart.CONTENT_DISPOSITION);
        String name = disposition == null ? null : Multipart.parameter(disposition, "name");
        if (name == null) {
            throw new BadRequestException("A part of the multipart entity has no name");
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
        return JaxRsEntityPart.read(name, Multipart.parameter(disposition, "filename"), mediaType, headers, content, providers);
    }

    private static MultivaluedMap<String, String> headers(String block) {
        MultivaluedMap<String, String> headers = JaxRsEntityPart.headers();
        // the folded lines are unfolded (RFC 822 section 3.1.1)
        for (String line : block.replaceAll("\r\n[ \t]+", " ").split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.add(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    /**
     * The index of a delimiter: the boundary followed by the end of its line, after optional
     * transport padding, or by the two hyphens of the close delimiter (RFC 2046 section 5.1.1),
     * not a boundary prefix within the content of a part.
     */
    private static int delimiterIndex(byte[] entity, byte[] delimiter, int from) {
        int index = indexOf(entity, delimiter, from);
        while (index >= 0 && !isDelimiterEnd(entity, index + delimiter.length)) {
            index = indexOf(entity, delimiter, index + 1);
        }
        return index;
    }

    private static boolean isDelimiterEnd(byte[] entity, int position) {
        boolean close = startsWith(entity, position, CLOSE);
        if (close) {
            position += CLOSE.length;
        }
        while (position < entity.length && (entity[position] == ' ' || entity[position] == '\t')) {
            position++;
        }
        // the close delimiter may also end the entity, without an epilogue
        return startsWith(entity, position, CRLF) || (close && position == entity.length);
    }

    private static boolean startsWith(byte[] array, int position, byte[] prefix) {
        if (position + prefix.length > array.length) {
            return false;
        }
        return Arrays.equals(array, position, position + prefix.length, prefix, 0, prefix.length);
    }

    private static int indexOf(byte[] array, byte[] target, int from) {
        for (int i = from; i <= array.length - target.length; i++) {
            if (startsWith(array, i, target)) {
                return i;
            }
        }
        return -1;
    }
}
