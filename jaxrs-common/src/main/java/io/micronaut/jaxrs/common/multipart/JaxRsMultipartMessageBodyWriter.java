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
import io.micronaut.jaxrs.common.JaxRsHeaderUtil;
import jakarta.inject.Inject;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;

/**
 * The writer of a {@code List<EntityPart>} as a {@code multipart} entity (JAX-RS 3.1 section 4.2.4).
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@Produces("multipart/*")
@Prototype
// the @Context fields the client injects, without reflection
@Introspected(accessKind = Introspected.AccessKind.FIELD, visibility = Introspected.Visibility.ANY)
@Internal
public final class JaxRsMultipartMessageBodyWriter implements MessageBodyWriter<List<EntityPart>> {

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
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        // the class of the entity is a list, e.g. of List.of, its generic type a List<EntityPart>
        return List.class.isAssignableFrom(type) && Multipart.isEntityPartList(List.class, genericType);
    }

    @Override
    public void writeTo(List<EntityPart> parts, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
        String boundary = mediaType.getParameters().get(Multipart.BOUNDARY);
        if (boundary == null) {
            boundary = "Boundary_" + UUID.randomUUID().toString().replace("-", "");
            Map<String, String> parameters = new HashMap<>(mediaType.getParameters());
            parameters.put(Multipart.BOUNDARY, boundary);
            MediaType multipart = mediaType.isWildcardType() || mediaType.isWildcardSubtype()
                ? MediaType.MULTIPART_FORM_DATA_TYPE : mediaType;
            httpHeaders.putSingle(HttpHeaders.CONTENT_TYPE, new MediaType(multipart.getType(), multipart.getSubtype(), parameters));
        }
        int written = 0;
        try {
            for (EntityPart part : parts) {
                String delimiter = "--" + boundary + "\r\n";
                if (part instanceof JaxRsEntityPart entityPart) {
                    // the headers as the writer of the content leaves them (MessageBodyWriter#writeTo)
                    entityPart.write(entityStream, providers, headers -> writeHead(entityStream, delimiter, part, headers));
                } else {
                    // a part of another implementation
                    writeHead(entityStream, delimiter, part, new MultivaluedHashMap<>(part.getHeaders()));
                    try (var content = part.getContent()) {
                        content.transferTo(entityStream);
                    }
                }
                entityStream.write(new byte[] {'\r', '\n'});
                written++;
            }
        } catch (IOException | RuntimeException e) {
            // the streams of the parts that are not written are owned by the writer too
            closeContents(parts.subList(written, parts.size()), e);
            throw e;
        }
        entityStream.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void closeContents(List<EntityPart> parts, Exception failure) {
        for (EntityPart part : parts) {
            try {
                if (part instanceof JaxRsEntityPart entityPart) {
                    entityPart.closeContent();
                } else {
                    // a part of another implementation: the stream it sends
                    InputStream content = part.getContent();
                    if (content != null) {
                        content.close();
                    }
                }
            } catch (IOException | RuntimeException e) {
                failure.addSuppressed(e);
            }
        }
    }

    private static void writeHead(OutputStream entityStream, String delimiter, EntityPart part,
                                  MultivaluedMap<String, Object> headers) throws IOException {
        StringBuilder head = new StringBuilder(delimiter);
        head.append(Multipart.CONTENT_DISPOSITION).append(": form-data; name=\"").append(Multipart.quoted(part.getName())).append('"');
        part.getFileName().ifPresent(fileName -> head.append("; filename=\"").append(Multipart.quoted(fileName)).append('"'));
        Object contentType = part.getMediaType();
        for (Map.Entry<String, List<Object>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(HttpHeaders.CONTENT_TYPE) && entry.getValue() != null && !entry.getValue().isEmpty()) {
                contentType = entry.getValue().get(0);
            }
        }
        head.append("\r\n").append(HttpHeaders.CONTENT_TYPE).append(": ").append(headerValue(contentType)).append("\r\n");
        headers.forEach((name, values) -> {
            if (!name.equalsIgnoreCase(Multipart.CONTENT_DISPOSITION) && !name.equalsIgnoreCase(HttpHeaders.CONTENT_TYPE) && values != null) {
                checkHeaderName(name);
                for (Object value : values) {
                    head.append(name).append(": ").append(headerValue(value)).append("\r\n");
                }
            }
        });
        head.append("\r\n");
        entityStream.write(head.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void checkHeaderName(String name) {
        // a token (RFC 9110 5.1)
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Empty part header name");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c <= ' ' || c >= 0x7F || "\"(),/:;<=>?@[\\]{}".indexOf(c) >= 0) {
                throw new IllegalArgumentException("Invalid part header name: " + name);
            }
        }
    }

    private static String headerValue(Object value) {
        String string = JaxRsHeaderUtil.headerToString(value);
        if (string != null && (string.indexOf('\r') >= 0 || string.indexOf('\n') >= 0)) {
            // a line break would end the header, starting another
            throw new IllegalArgumentException("Part header value contains a line break");
        }
        return string;
    }
}
