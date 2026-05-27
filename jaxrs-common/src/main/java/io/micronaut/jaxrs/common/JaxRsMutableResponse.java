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
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpResponseProvider;
import io.micronaut.http.MutableHttpResponse;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
    private final @Nullable MultivaluedMap<String, Object> metadata;
    private InputStream entityStream;

    public JaxRsMutableResponse(MutableHttpResponse<?> mutableHttpResponse) {
        this(mutableHttpResponse, HttpMessageEntityReader.DEFAULT, null);
    }

    public JaxRsMutableResponse(MutableHttpResponse<?> mutableHttpResponse, HttpMessageEntityReader entityReader) {
        this(mutableHttpResponse, entityReader, null);
    }

    JaxRsMutableResponse(MutableHttpResponse<?> mutableHttpResponse,
                         MultivaluedMap<String, Object> metadata) {
        this(mutableHttpResponse, HttpMessageEntityReader.DEFAULT, metadata);
    }

    private JaxRsMutableResponse(MutableHttpResponse<?> mutableHttpResponse,
                                 HttpMessageEntityReader entityReader,
                                 @Nullable MultivaluedMap<String, Object> metadata) {
        super(mutableHttpResponse, entityReader);
        this.mutableHttpResponse = mutableHttpResponse;
        this.metadata = metadata;
    }

    @Override
    public boolean hasEntity() {
        return entityStream != null || super.hasEntity();
    }

    @Override
    public <T> T readEntity(Argument<T> entityType) {
        if (entityStream != null) {
            if (entityType.getType().equals(InputStream.class)) {
                return (T) entityStream;
            }
            byte[] result;
            try {
                result = entityStream.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            try {
                entityStream.close();
            } catch (IOException ignore) {
                // Ignore
            }
            mutableHttpResponse.body(result);
            entityStream = null;
        }
        return super.readEntity(entityType);
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
    public Date getDate() {
        Date date = metadataDate(HttpHeaders.DATE);
        return date == null ? super.getDate() : date;
    }

    @Override
    public Date getLastModified() {
        Date lastModified = metadataDate(HttpHeaders.LAST_MODIFIED);
        return lastModified == null ? super.getLastModified() : lastModified;
    }

    @Override
    public MutableHttpResponse<?> getResponse() {
        return mutableHttpResponse;
    }

    @Override
    public MultivaluedMap<String, Object> getHeaders() {
        if (metadata != null) {
            return metadata;
        }
        return new JaxRsMutableObjectHeadersMultivaluedMap(mutableHttpResponse.getHeaders());
    }

    private @Nullable Date metadataDate(String name) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.getFirst(name);
        return value instanceof Date date ? date : null;
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
    public void close() {
        if (entityStream != null) {
            try {
                entityStream.close();
                entityStream = null;
            } catch (IOException e) {
                throw new ProcessingException(e);
            }
        }
        super.close();
    }
}
