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

import io.micronaut.core.annotation.Internal;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.util.List;

/**
 * The {@link EntityPart.Builder}.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
public final class JaxRsEntityPartBuilder implements EntityPart.Builder {

    private final String name;
    private final MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
    private @Nullable MediaType mediaType;
    private @Nullable String fileName;
    private @Nullable Object content;
    private @Nullable GenericType<?> contentType;

    /**
     * @param name The name of the part
     */
    public JaxRsEntityPartBuilder(String name) {
        if (name == null) {
            throw new IllegalArgumentException("The name of the part is null");
        }
        this.name = name;
    }

    @Override
    public EntityPart.Builder mediaType(MediaType mediaType) throws IllegalArgumentException {
        this.mediaType = mediaType;
        return this;
    }

    @Override
    public EntityPart.Builder mediaType(String mediaTypeString) throws IllegalArgumentException {
        this.mediaType = mediaTypeString == null ? null : MediaType.valueOf(mediaTypeString);
        return this;
    }

    @Override
    public EntityPart.Builder header(String headerName, String... headerValues) throws IllegalArgumentException {
        if (headerName == null) {
            throw new IllegalArgumentException("The header name is null");
        }
        headers.put(headerName, headerValues == null ? null : List.of(headerValues));
        return this;
    }

    @Override
    public EntityPart.Builder headers(MultivaluedMap<String, String> newHeaders) throws IllegalArgumentException {
        if (newHeaders == null) {
            throw new IllegalArgumentException("The headers are null");
        }
        headers.clear();
        headers.putAll(newHeaders);
        return this;
    }

    @Override
    public EntityPart.Builder fileName(String fileName) throws IllegalArgumentException {
        this.fileName = fileName;
        return this;
    }

    @Override
    public EntityPart.Builder content(InputStream content) throws IllegalArgumentException {
        return content(content, InputStream.class);
    }

    @Override
    public <T> EntityPart.Builder content(T content, Class<? extends T> type) throws IllegalArgumentException {
        if (type == null) {
            throw new IllegalArgumentException("The type is null");
        }
        return content(content, new GenericType<>(type));
    }

    @Override
    public <T> EntityPart.Builder content(T content, GenericType<T> type) throws IllegalArgumentException {
        if (content == null || type == null) {
            throw new IllegalArgumentException("The content or its type is null");
        }
        this.content = content;
        this.contentType = type;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public EntityPart.Builder content(Object content) throws IllegalArgumentException {
        if (content == null) {
            throw new IllegalArgumentException("The content is null");
        }
        return content(content, (Class<Object>) content.getClass());
    }

    @Override
    public EntityPart build() throws IllegalStateException {
        if (content == null || contentType == null) {
            throw new IllegalStateException("The part " + name + " has no content");
        }
        // a file is binary, a field is text (EntityPart.getMediaType)
        MediaType type = mediaType != null ? mediaType
            : fileName != null ? MediaType.APPLICATION_OCTET_STREAM_TYPE : MediaType.TEXT_PLAIN_TYPE;
        return new JaxRsEntityPart(name, fileName, type, new MultivaluedHashMap<>(headers), content, contentType, null);
    }
}
