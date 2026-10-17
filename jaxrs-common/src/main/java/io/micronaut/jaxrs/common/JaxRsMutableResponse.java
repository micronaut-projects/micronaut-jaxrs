/*
 * Copyright 2017-2020 original authors
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
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpResponseProvider;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.MutableHttpResponse;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.MultivaluedMap;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;

/**
 * Adapter for JAX-RS and final Micronaut response.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Internal
public final class JaxRsMutableResponse extends JaxRsResponse implements HttpResponseProvider {

    private final MutableHttpResponse<?> mutableHttpResponse;
    // the objects of the headers, see JaxRsResponseMetadata
    private final JaxRsResponseMetadata.State metadata;
    @Nullable
    private InputStream entityStream;

    public JaxRsMutableResponse(MutableHttpResponse<?> mutableHttpResponse) {
        this(mutableHttpResponse, new JaxRsResponseMetadata.State());
    }

    /**
     * @param mutableHttpResponse The response
     * @param metadata            The objects of its headers, see {@link JaxRsResponseMetadata}
     */
    public JaxRsMutableResponse(MutableHttpResponse<?> mutableHttpResponse, JaxRsResponseMetadata.State metadata) {
        super(mutableHttpResponse);
        this.mutableHttpResponse = mutableHttpResponse;
        this.metadata = metadata;
    }

    public JaxRsMutableResponse(MutableHttpResponse<?> mutableHttpResponse, HttpMessageEntityReader entityReader) {
        this(mutableHttpResponse, entityReader, new JaxRsResponseMetadata.State());
    }

    private JaxRsMutableResponse(MutableHttpResponse<?> mutableHttpResponse, HttpMessageEntityReader entityReader, JaxRsResponseMetadata.State metadata) {
        super(mutableHttpResponse, entityReader);
        this.mutableHttpResponse = mutableHttpResponse;
        this.metadata = metadata;
    }

    @Override
    public @Nullable Date getDate() {
        // the date it was given, not only to the second of the header
        return getHeaders().getFirst(HttpHeaders.DATE) instanceof Date date ? date : super.getDate();
    }

    @Override
    public @Nullable Date getLastModified() {
        return getHeaders().getFirst(HttpHeaders.LAST_MODIFIED) instanceof Date date ? date : super.getLastModified();
    }

    @Override
    public boolean hasEntity() {
        checkCanReadEntity();
        return entityStream != null || super.hasEntity();
    }

    @Override
    public @Nullable Object getEntity() {
        if (entityStream != null) {
            checkCanReadEntity();
            // the entity stream, e.g. the one a filter set, not read yet
            return entityStream;
        }
        return super.getEntity();
    }

    @Override
    InputStream receivedEntityStream(ByteBuffer<?> byteBuffer) {
        // the entity stream the entity is then read from
        return getEntityStream();
    }

    @Override
    @Nullable Object readUnbuffered(Argument<?> entityType) {
        InputStream stream = entityStream;
        if (stream != null) {
            if (entityType.getType() == InputStream.class) {
                // it stays the entity stream, which close() closes
                return stream;
            }
            entityStream = null;
            mutableHttpResponse.body(readAll(stream));
        }
        return super.readUnbuffered(entityType);
    }

    @Override
    byte @Nullable [] bufferedBytes() {
        // the current entity stream, e.g. the one a response filter set
        InputStream stream = entityStream;
        if (stream != null) {
            entityStream = null;
            byte[] bytes = readAll(stream);
            mutableHttpResponse.body(bytes);
            return bytes;
        }
        return super.bufferedBytes();
    }

    /**
     * Read a stream to its end, and close it.
     */
    private static byte[] readAll(InputStream stream) {
        try (stream) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new ProcessingException(e);
        }
    }

    @Override
    public MultivaluedMap<String, String> getStringHeaders() {
        return JaxRsMutableHttpHeaders.forResponse(mutableHttpResponse.getHeaders()).getRequestHeaders();
    }

    @Override
    public JaxRsMutableResponse withEntityReader(HttpMessageEntityReader entityReader) {
        return new JaxRsMutableResponse(mutableHttpResponse, entityReader, metadata);
    }

    @Override
    public MutableHttpResponse<?> getResponse() {
        return mutableHttpResponse;
    }

    @Override
    public MultivaluedMap<String, Object> getHeaders() {
        return new JaxRsResponseMetadata(mutableHttpResponse.getHeaders(), metadata);
    }

    public InputStream getEntityStream() {
        if (entityStream == null) {
            byte[] bytes = mutableHttpResponse.getBody(byte[].class).orElse(new byte[] {});
            entityStream = new ByteArrayInputStream(bytes);
        }
        return entityStream;
    }

    public void setEntityStream(InputStream entityStream) {
        this.entityStream = entityStream;
    }

    @Override
    void release() {
        try {
            InputStream stream = entityStream;
            if (stream != null) {
                entityStream = null;
                stream.close();
            }
            // an entity that holds a resource, e.g. Response.ok(new FileInputStream(file))
            Object body = mutableHttpResponse.body();
            if (body instanceof GenericEntity<?> genericEntity) {
                body = genericEntity.getEntity();
            }
            if (body instanceof Closeable closeable) {
                closeable.close();
            }
        } catch (IOException e) {
            throw new ProcessingException(e);
        }
    }
}
