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
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
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
        return Multipart.isEntityPartList(type, genericType);
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
        for (EntityPart part : parts) {
            StringBuilder head = new StringBuilder("--").append(boundary).append("\r\n");
            head.append(Multipart.CONTENT_DISPOSITION).append(": form-data; name=\"").append(Multipart.quoted(part.getName())).append('"');
            part.getFileName().ifPresent(fileName -> head.append("; filename=\"").append(Multipart.quoted(fileName)).append('"'));
            head.append("\r\n").append(HttpHeaders.CONTENT_TYPE).append(": ").append(part.getMediaType()).append("\r\n");
            part.getHeaders().forEach((name, values) -> {
                if (!name.equalsIgnoreCase(Multipart.CONTENT_DISPOSITION) && !name.equalsIgnoreCase(HttpHeaders.CONTENT_TYPE) && values != null) {
                    for (String value : values) {
                        head.append(name).append(": ").append(value).append("\r\n");
                    }
                }
            });
            head.append("\r\n");
            entityStream.write(head.toString().getBytes(StandardCharsets.UTF_8));
            if (part instanceof JaxRsEntityPart entityPart) {
                entityPart.write(entityStream, providers);
            } else {
                // a part of another implementation
                try (var content = part.getContent()) {
                    content.transferTo(entityStream);
                }
            }
            entityStream.write(new byte[] {'\r', '\n'});
        }
        entityStream.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
    }
}
