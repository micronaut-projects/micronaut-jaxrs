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
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * An {@link EntityPart}: built with its content, or read from a multipart entity with its bytes.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
public final class JaxRsEntityPart implements EntityPart {

    private static final Annotation[] NO_ANNOTATIONS = new Annotation[0];

    private final String name;
    private final @Nullable String fileName;
    private final MediaType mediaType;
    private final MultivaluedMap<String, String> headers;
    // an InputStream, the bytes of a part that was read, or an object of the content type
    private final Object content;
    // the type of the content of a built part, null for a part that was read
    private final @Nullable GenericType<?> contentType;
    private final @Nullable Providers providers;
    private boolean consumed;

    JaxRsEntityPart(String name,
                    @Nullable String fileName,
                    MediaType mediaType,
                    MultivaluedMap<String, String> headers,
                    Object content,
                    @Nullable GenericType<?> contentType,
                    @Nullable Providers providers) {
        this.name = name;
        this.fileName = fileName;
        this.mediaType = mediaType;
        this.headers = headers;
        this.content = content;
        this.contentType = contentType;
        this.providers = providers;
    }

    /**
     * A part read from a multipart entity.
     *
     * @param name      The name
     * @param fileName  The file name
     * @param mediaType The media type
     * @param headers   The headers
     * @param content   The content
     * @param providers The providers that read the content
     * @return The part
     */
    public static JaxRsEntityPart read(String name,
                                       @Nullable String fileName,
                                       MediaType mediaType,
                                       MultivaluedMap<String, String> headers,
                                       byte[] content,
                                       @Nullable Providers providers) {
        return new JaxRsEntityPart(name, fileName, mediaType, headers, content, null, providers);
    }

    /**
     * A part of a form field.
     *
     * @param name      The name
     * @param fileName  The file name
     * @param mediaType The media type
     * @param content   The content
     * @return The part
     */
    public static JaxRsEntityPart read(String name, @Nullable String fileName, MediaType mediaType, byte[] content) {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.putSingle("Content-Type", mediaType.toString());
        return read(name, fileName, mediaType, headers, content, null);
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
    public InputStream getContent() {
        consume();
        if (content instanceof InputStream stream) {
            return stream;
        }
        return new ByteArrayInputStream(bytes(providers));
    }

    @Override
    public <T> T getContent(Class<T> type) throws IllegalArgumentException, IllegalStateException, IOException {
        return getContent(new GenericType<>(type));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getContent(GenericType<T> type) throws IllegalArgumentException, IllegalStateException, IOException {
        if (type == null) {
            throw new IllegalArgumentException("The type is null");
        }
        consume();
        Class<T> rawType = (Class<T>) type.getRawType();
        if (contentType != null && rawType.isInstance(content)) {
            return (T) content;
        }
        if (rawType == InputStream.class) {
            return (T) stream();
        }
        if (rawType == byte[].class) {
            return (T) stream().readAllBytes();
        }
        if (rawType == String.class) {
            return (T) new String(stream().readAllBytes(), charset());
        }
        MessageBodyReader<T> reader = providers == null ? null
            : providers.getMessageBodyReader(rawType, type.getType(), NO_ANNOTATIONS, mediaType);
        if (reader == null) {
            throw new IllegalArgumentException("No reader of " + type.getType() + " for the media type " + mediaType);
        }
        return reader.readFrom(rawType, type.getType(), NO_ANNOTATIONS, mediaType, headers, stream());
    }

    @Override
    public MultivaluedMap<String, String> getHeaders() {
        return headers;
    }

    @Override
    public MediaType getMediaType() {
        return mediaType;
    }

    /**
     * Write the content of this part.
     *
     * @param out       The stream
     * @param providers The providers that write the content of another type than the standard ones
     * @throws IOException if the content cannot be written
     */
    void write(OutputStream out, @Nullable Providers providers) throws IOException {
        if (content instanceof InputStream stream) {
            try (stream) {
                stream.transferTo(out);
            }
        } else {
            out.write(bytes(providers != null ? providers : this.providers));
        }
    }

    private void consume() {
        if (consumed) {
            throw new IllegalStateException("The content of the part " + name + " is already read");
        }
        consumed = true;
    }

    private InputStream stream() throws IOException {
        if (content instanceof InputStream stream) {
            return stream;
        }
        return new ByteArrayInputStream(bytes(providers));
    }

    private Charset charset() {
        String charset = mediaType.getParameters().get(MediaType.CHARSET_PARAMETER);
        return charset == null ? StandardCharsets.UTF_8 : Charset.forName(charset);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private byte[] bytes(@Nullable Providers providers) {
        if (content instanceof byte[] bytes) {
            return bytes;
        }
        if (content instanceof String string) {
            return string.getBytes(charset());
        }
        GenericType<?> type = contentType == null ? new GenericType<>(content.getClass()) : contentType;
        MessageBodyWriter writer = providers == null ? null
            : providers.getMessageBodyWriter(type.getRawType(), type.getType(), NO_ANNOTATIONS, mediaType);
        if (writer == null) {
            throw new ProcessingException("No writer of " + type.getType() + " for the media type " + mediaType);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            writer.writeTo(content, type.getRawType(), type.getType(), NO_ANNOTATIONS, mediaType, new MultivaluedHashMap<String, Object>(headers), out);
        } catch (IOException e) {
            throw new ProcessingException(e);
        }
        return out.toByteArray();
    }

    @Override
    public String toString() {
        return "EntityPart{name=" + name + ", fileName=" + fileName + ", mediaType=" + mediaType + ", headers=" + headers + '}';
    }
}
