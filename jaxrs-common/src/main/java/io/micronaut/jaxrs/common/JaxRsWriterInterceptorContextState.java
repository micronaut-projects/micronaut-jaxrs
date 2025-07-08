/*
 * Copyright 2017-2025 original authors
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
import io.micronaut.core.type.MutableHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.WriterInterceptorContext;

import java.io.OutputStream;

/**
 * State carried through a chain of {@link jakarta.ws.rs.ext.WriterInterceptor} calls. This allows
 * for lazily populating expensive-to-construct fields.
 *
 * @since 4.9.2
 * @author Jonas Konrad
 */
@Internal
public sealed interface JaxRsWriterInterceptorContextState permits JaxRsGenericEntityMessageBodyWriter.ByteBodyState, JaxRsWriterInterceptorContextState.ClassicState {
    /**
     * @see WriterInterceptorContext#getEntity()
     */
    Object getEntity();

    /**
     * @see WriterInterceptorContext#setEntity(Object)
     */
    void setEntity(Object entity);

    /**
     * @see WriterInterceptorContext#getOutputStream()
     */
    OutputStream getOutputStream();

    /**
     * @see WriterInterceptorContext#setOutputStream(OutputStream)
     */
    void setOutputStream(OutputStream outputStream);

    /**
     * @see WriterInterceptorContext#getHeaders()
     */
    MultivaluedMap<String, Object> getHeaders();

    /**
     * Implementation with simple fields.
     */
    final class ClassicState implements JaxRsWriterInterceptorContextState {
        final MultivaluedMap<String, Object> headers;
        Object entity;
        OutputStream outputStream;

        public ClassicState(MutableHeaders headers, Object entity, OutputStream outputStream) {
            this.headers = new JaxRsMutableObjectHeadersMultivaluedMap(headers);
            this.entity = entity;
            this.outputStream = outputStream;
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
        public void setOutputStream(OutputStream outputStream) {
            this.outputStream = outputStream;
        }

        @Override
        public MultivaluedMap<String, Object> getHeaders() {
            return headers;
        }
    }
}
