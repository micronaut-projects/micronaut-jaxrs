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
package io.micronaut.jaxrs.common.body.standard;

import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.jaxrs.common.JaxRsMessageBodyProvider;
import io.micronaut.jaxrs.common.JaxRsTemporaryFiles;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The read/write body for {@link File}.
 *
 * @author Denis Stepanov
 * @since 4.9
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@JaxRsMessageBodyProvider(
    readerType = File.class,
    writerType = File.class,
    consumes = MediaType.WILDCARD,
    produces = MediaType.WILDCARD
)
@Prototype
@Internal
public final class JaxRsFileMessageBodyReaderWriter implements MessageBodyReader<File>, MessageBodyWriter<File> {
    private final @Nullable Path tempDirectory;

    /**
     * Default constructor.
     */
    public JaxRsFileMessageBodyReaderWriter() {
        this(null);
    }

    /**
     * Constructor.
     *
     * @param tempDirectory The temporary file directory
     */
    public JaxRsFileMessageBodyReaderWriter(@Nullable Path tempDirectory) {
        this.tempDirectory = tempDirectory;
    }

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return File.class.isAssignableFrom(type);
    }

    @Override
    public File readFrom(Class<File> type,
                         Type genericType,
                         Annotation[] annotations,
                         MediaType mediaType,
                         MultivaluedMap<String, String> httpHeaders,
                         InputStream entityStream) throws IOException, WebApplicationException {
        File file = JaxRsTemporaryFiles.createTempFile("jaxrs-entity-", ".tmp", tempDirectory);
        file.deleteOnExit();
        try (OutputStream outputStream = Files.newOutputStream(file.toPath())) {
            entityStream.transferTo(outputStream);
        }
        return file;
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return File.class.isAssignableFrom(type);
    }

    @Override
    public void writeTo(File file,
                        Class<?> type,
                        Type genericType,
                        Annotation[] annotations,
                        MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders,
                        OutputStream entityStream) throws IOException, WebApplicationException {
        Files.copy(file.toPath(), entityStream);
    }
}
