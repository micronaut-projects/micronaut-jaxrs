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
package io.micronaut.jaxrs.providers;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import jakarta.activation.DataSource;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/**
 * The standard provider of {@link DataSource} for all media types (JAX-RS 4.2.4): an entity is
 * read into a data source of its bytes and media type.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@Singleton
@Internal
public final class JaxRsDataSourceMessageBodyReaderWriter implements MessageBodyReader<DataSource>, MessageBodyWriter<DataSource> {

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return type == DataSource.class;
    }

    @Override
    public DataSource readFrom(Class<DataSource> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                               MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
        return new EntityDataSource(entityStream.readAllBytes(), mediaType == null ? MediaType.APPLICATION_OCTET_STREAM : mediaType.toString());
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return DataSource.class.isAssignableFrom(type);
    }

    @Override
    public void writeTo(DataSource dataSource, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
        try (InputStream in = dataSource.getInputStream()) {
            in.transferTo(entityStream);
        }
    }

    /**
     * The data source of an entity.
     *
     * @param bytes       The entity
     * @param contentType Its media type
     */
    private record EntityDataSource(byte[] bytes, String contentType) implements DataSource {

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            throw new IOException("The data source of an entity is read-only");
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public String getName() {
            return "entity";
        }
    }
}
