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
import jakarta.ws.rs.core.MultivaluedMap;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Adapter for JAX-RS and final Micronaut response.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Internal
public final class JaxRsMutableResponse extends JaxRsResponse implements HttpResponseProvider {

    private final MutableHttpResponse<?> mutableHttpResponse;
    private InputStream entityStream;

    public JaxRsMutableResponse(MutableHttpResponse<?> mutableHttpResponse) {
        super(mutableHttpResponse);
        this.mutableHttpResponse = mutableHttpResponse;
    }

    public JaxRsMutableResponse(MutableHttpResponse<?> mutableHttpResponse, HttpMessageEntityReader entityReader) {
        super(mutableHttpResponse, entityReader);
        this.mutableHttpResponse = mutableHttpResponse;
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
            mutableHttpResponse.body(JaxRsUtils.readIOStream(entityStream));
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
        return new JaxRsMutableResponse(mutableHttpResponse, entityReader);
    }

    @Override
    public MutableHttpResponse<?> getResponse() {
        return mutableHttpResponse;
    }

    @Override
    public MultivaluedMap<String, Object> getHeaders() {
        return new JaxRsMutableObjectHeadersMultivaluedMap(mutableHttpResponse.getHeaders());
    }

    public InputStream getEntityStream() {
        if (entityStream == null) {
            byte[] bytes = mutableHttpResponse.getBody(byte[].class).orElse(new byte[]{});
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
