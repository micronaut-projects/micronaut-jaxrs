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
package io.micronaut.jaxrs.common;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.multipart.CompletedPart;
import io.micronaut.http.multipart.FormFieldMetadata;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Default {@link EntityPart} implementation.
 */
@Internal
public final class JaxRsEntityPart implements EntityPart {

    private final String name;
    private final @Nullable String fileName;
    private final MediaType mediaType;
    private final MultivaluedMap<String, String> headers;
    private final byte[] content;
    private boolean consumed;

    private JaxRsEntityPart(String name,
                            @Nullable String fileName,
                            MediaType mediaType,
                            MultivaluedMap<String, String> headers,
                            byte[] content) {
        this.name = name;
        this.fileName = fileName;
        this.mediaType = mediaType;
        this.headers = headers;
        this.content = content;
    }

    /**
     * @param name The part name
     * @return A new builder
     */
    public static Builder withName(String name) {
        return new Builder(name);
    }

    /**
     * @param fileName The part file name
     * @return A new builder
     */
    public static Builder withFileName(String fileName) {
        return new Builder(null).fileName(fileName);
    }

    /**
     * Creates a part parsed from a multipart body.
     *
     * @param name The part name
     * @param fileName The file name
     * @param mediaType The media type
     * @param headers The headers
     * @param content The content
     * @return The entity part
     */
    public static EntityPart parsed(String name,
                                    @Nullable String fileName,
                                    MediaType mediaType,
                                    MultivaluedMap<String, String> headers,
                                    byte[] content) {
        return new JaxRsEntityPart(name, fileName, mediaType, new MultivaluedHashMap<>(headers), content.clone());
    }

    /**
     * Creates a JAX-RS entity part from a completed Micronaut multipart part.
     *
     * @param part The completed Micronaut part
     * @return The JAX-RS entity part
     * @throws IOException If the part content cannot be read
     */
    public static EntityPart from(CompletedPart part) throws IOException {
        FormFieldMetadata metadata = part.getMetadata();
        String partName = metadata.name();
        if (partName == null || partName.isBlank()) {
            throw new BadRequestException("Multipart entity part name is required");
        }
        String partFileName = metadata.fileName();
        MediaType partMediaType = metadata.mediaType() == null ? MediaType.TEXT_PLAIN_TYPE : JaxRsUtils.convert(metadata.mediaType());
        MultivaluedMap<String, String> partHeaders = new MultivaluedHashMap<>();
        partHeaders.putSingle(JaxRsMultipart.CONTENT_DISPOSITION, contentDisposition(partName, partFileName));
        partHeaders.putSingle(JaxRsMultipart.CONTENT_TYPE, partMediaType.toString());
        return new JaxRsEntityPart(partName, partFileName, partMediaType, partHeaders, part.getBytes());
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Optional<String> getFileName() {
        return Optional.ofNullable(fileName);
    }

    @Override
    public synchronized InputStream getContent() {
        return new ByteArrayInputStream(consume());
    }

    @Override
    public synchronized <T> T getContent(Class<T> type) throws IOException, WebApplicationException {
        JaxRsUtils.requireNonNull("type", type);
        byte[] bytes = consume();
        if (type == byte[].class) {
            return type.cast(bytes);
        }
        if (type == String.class) {
            return type.cast(new String(bytes, charset()));
        }
        if (type.isAssignableFrom(ByteArrayInputStream.class)) {
            return type.cast(new ByteArrayInputStream(bytes));
        }
        throw new IllegalArgumentException("Unsupported multipart entity part content type: " + type.getName());
    }

    @Override
    public <T> T getContent(GenericType<T> type) throws IOException, WebApplicationException {
        JaxRsUtils.requireNonNull("type", type);
        return getContent((Class<T>) type.getRawType());
    }

    @Override
    public MultivaluedMap<String, String> getHeaders() {
        return headers;
    }

    @Override
    public MediaType getMediaType() {
        return mediaType;
    }

    private byte[] consume() {
        if (consumed) {
            throw new IllegalStateException("Entity part content has already been consumed");
        }
        consumed = true;
        return content.clone();
    }

    private Charset charset() {
        String charset = mediaType.getParameters().get(MediaType.CHARSET_PARAMETER);
        return charset == null ? StandardCharsets.UTF_8 : Charset.forName(charset);
    }

    private static String contentDisposition(String name, @Nullable String fileName) {
        String value = "form-data; name=\"" + escape(name) + "\"";
        if (fileName != null) {
            value += "; filename=\"" + escape(fileName) + "\"";
        }
        return value;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Default {@link EntityPart.Builder} implementation.
     */
    public static final class Builder implements EntityPart.Builder {
        private @Nullable String name;
        private @Nullable String fileName;
        private @Nullable MediaType mediaType;
        private final MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        private @Nullable Object content;

        private Builder(@Nullable String name) {
            this.name = name;
        }

        @Override
        public EntityPart.Builder mediaType(MediaType mediaType) throws IllegalArgumentException {
            this.mediaType = JaxRsUtils.requireNonNull("mediaType", mediaType);
            return this;
        }

        @Override
        public EntityPart.Builder mediaType(String mediaType) throws IllegalArgumentException {
            return mediaType(MediaType.valueOf(JaxRsUtils.requireNonNull("mediaType", mediaType)));
        }

        @Override
        public EntityPart.Builder header(String name, String... values) throws IllegalArgumentException {
            JaxRsUtils.requireNonNull("name", name);
            if (values != null) {
                headers.addAll(name, Arrays.asList(values));
            }
            return this;
        }

        @Override
        public EntityPart.Builder headers(MultivaluedMap<String, String> headers) throws IllegalArgumentException {
            JaxRsUtils.requireNonNull("headers", headers);
            this.headers.clear();
            this.headers.putAll(headers);
            return this;
        }

        @Override
        public Builder fileName(String fileName) throws IllegalArgumentException {
            this.fileName = fileName;
            return this;
        }

        @Override
        public EntityPart.Builder content(InputStream content) throws IllegalArgumentException {
            this.content = JaxRsUtils.requireNonNull("content", content);
            return this;
        }

        @Override
        public <T> EntityPart.Builder content(T content, Class<? extends T> type) throws IllegalArgumentException {
            JaxRsUtils.requireNonNull("type", type);
            this.content = JaxRsUtils.requireNonNull("content", content);
            return this;
        }

        @Override
        public <T> EntityPart.Builder content(T content, GenericType<T> type) throws IllegalArgumentException {
            JaxRsUtils.requireNonNull("type", type);
            this.content = JaxRsUtils.requireNonNull("content", content);
            return this;
        }

        @Override
        public EntityPart build() throws IllegalStateException, IOException, WebApplicationException {
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("Multipart entity part name is required");
            }
            if (content == null) {
                throw new IllegalStateException("Multipart entity part content is required");
            }
            MediaType partMediaType = mediaType == null ? MediaType.TEXT_PLAIN_TYPE : mediaType;
            MultivaluedMap<String, String> partHeaders = new MultivaluedHashMap<>(headers);
            partHeaders.putSingle(JaxRsMultipart.CONTENT_DISPOSITION, contentDisposition(name, fileName));
            partHeaders.putSingle(JaxRsMultipart.CONTENT_TYPE, partMediaType.toString());
            return new JaxRsEntityPart(name, fileName, partMediaType, partHeaders, contentBytes(content));
        }

        private static byte[] contentBytes(Object content) throws IOException {
            if (content instanceof byte[] bytes) {
                return bytes.clone();
            }
            if (content instanceof String string) {
                return string.getBytes(StandardCharsets.UTF_8);
            }
            if (content instanceof InputStream inputStream) {
                return inputStream.readAllBytes();
            }
            return content.toString().getBytes(StandardCharsets.UTF_8);
        }
    }
}
