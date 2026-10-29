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
import io.micronaut.http.HttpResponseProvider;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.MutableHttpResponse;
import jakarta.ws.rs.core.MultivaluedMap;
import org.jspecify.annotations.Nullable;

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

    public JaxRsMutableResponse(MutableHttpResponse<?> mutableHttpResponse) {
        this(mutableHttpResponse, new JaxRsResponseMetadata.State());
    }

    /**
     * @param mutableHttpResponse The response
     * @param metadata            The objects of its headers, see {@link JaxRsResponseMetadata}
     */
    public JaxRsMutableResponse(MutableHttpResponse<?> mutableHttpResponse, JaxRsResponseMetadata.State metadata) {
        super(mutableHttpResponse, mutableHttpResponse, HttpMessageEntityReader.DEFAULT);
        this.mutableHttpResponse = mutableHttpResponse;
        this.metadata = metadata;
    }

    public JaxRsMutableResponse(MutableHttpResponse<?> mutableHttpResponse, HttpMessageEntityReader entityReader) {
        this(mutableHttpResponse, entityReader, new JaxRsResponseMetadata.State());
    }

    private JaxRsMutableResponse(MutableHttpResponse<?> mutableHttpResponse, HttpMessageEntityReader entityReader, JaxRsResponseMetadata.State metadata) {
        super(mutableHttpResponse, mutableHttpResponse, entityReader);
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
        return entity().getEntityStream();
    }

    public void setEntityStream(InputStream entityStream) {
        entity().setEntityStream(entityStream);
    }
}
