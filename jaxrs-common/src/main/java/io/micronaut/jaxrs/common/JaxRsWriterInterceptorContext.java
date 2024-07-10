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
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;

/**
 * The implementation of {@link WriterInterceptorContext}.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
final class JaxRsWriterInterceptorContext extends AbstractJaxRsInterceptorContext implements WriterInterceptorContext {

    private final Iterator<WriterInterceptor> interceptors;
    private final IOProceedCallback interceptedSupplier;
    private final MultivaluedMap<String, Object> headers;
    private Object entity;
    private OutputStream outputStream;

    JaxRsWriterInterceptorContext(Iterator<WriterInterceptor> interceptors,
                                  IOProceedCallback interceptedCallback,
                                  Argument<?> argument,
                                  MediaType mediaType,
                                  MultivaluedMap<String, Object> headers,
                                  Object entity,
                                  OutputStream outputStream) {
        super(argument, mediaType);
        this.interceptors = interceptors;
        this.interceptedSupplier = interceptedCallback;
        this.headers = headers;
        this.entity = entity;
        this.outputStream = outputStream;
    }

    @Override
    public void proceed() throws IOException, WebApplicationException {
        if (interceptors.hasNext()) {
            interceptors.next().aroundWriteTo(this);
        } else {
            interceptedSupplier.call(this);
        }
    }

    @Override
    public Object getEntity() {
        return entity;
    }

    @Override
    public void setEntity(Object entity) {
        this.entity = entity;
    }

    @Override
    public OutputStream getOutputStream() {
        return outputStream;
    }

    @Override
    public void setOutputStream(OutputStream os) {
        this.outputStream = os;
    }

    @Override
    public MultivaluedMap<String, Object> getHeaders() {
        return headers;
    }

    /**
     * The writer.
     */
    @FunctionalInterface
    interface IOProceedCallback {

        void call(JaxRsWriterInterceptorContext context) throws IOException;

    }
}
