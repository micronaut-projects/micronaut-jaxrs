/*
 * Copyright 2017-2024 original authors
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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

/**
 * The implementation of {@link ReaderInterceptorContext}.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
public final class JaxRsReaderInterceptorContext extends AbstractJaxRsInterceptorContext implements ReaderInterceptorContext {

    private final Iterator<ReaderInterceptor> interceptors;
    private final IOProceedSupplier<Object> interceptedSupplier;
    private final MultivaluedMap<String, String> headers;
    private InputStream inputStream;

    public JaxRsReaderInterceptorContext(Iterator<ReaderInterceptor> interceptors,
                                         IOProceedSupplier<Object> interceptedSupplier,
                                         Argument<?> argument,
                                         MediaType mediaType,
                                         MultivaluedMap<String, String> headers,
                                         InputStream inputStream) {
        super(argument, mediaType);
        this.interceptors = interceptors;
        this.interceptedSupplier = interceptedSupplier;
        this.headers = headers;
        this.inputStream = inputStream;
    }

    @Override
    public Object proceed() throws IOException, WebApplicationException {
        if (interceptors.hasNext()) {
            return interceptors.next().aroundReadFrom(this);
        }
        return interceptedSupplier.get(this);
    }

    @Override
    public InputStream getInputStream() {
        return inputStream;
    }

    @Override
    public void setInputStream(InputStream is) {
        this.inputStream = is;
    }

    @Override
    public MultivaluedMap<String, String> getHeaders() {
        return headers;
    }

    /**
     * The reader.
     *
     * @param <T> The type
     */
    @FunctionalInterface
    public interface IOProceedSupplier<T> {

        T get(JaxRsReaderInterceptorContext context) throws IOException;

    }
}
