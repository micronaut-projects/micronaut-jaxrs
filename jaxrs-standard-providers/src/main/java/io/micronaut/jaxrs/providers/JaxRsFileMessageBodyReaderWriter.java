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
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.ref.Cleaner;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The standard provider of {@link File} for all media types (JAX-RS 4.2.4): an entity is read
 * into a temporary file, deleted once the {@link File} it is read as is no longer reachable, or
 * at the latest when the JVM exits normally. An application that keeps the file beyond that
 * moves it, or keeps the {@link File}.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@Singleton
@Internal
public final class JaxRsFileMessageBodyReaderWriter implements MessageBodyReader<File>, MessageBodyWriter<File> {

    private static final Cleaner CLEANER = Cleaner.create();
    // the temporary files not deleted yet, deleted when the JVM exits: unlike File#deleteOnExit,
    // which keeps every path until then, a deleted file leaves it
    private static final Set<Path> TEMPORARY_FILES = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> TEMPORARY_FILES.forEach(JaxRsFileMessageBodyReaderWriter::delete),
            "jaxrs-entity-files"));
    }

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return type == File.class;
    }

    @Override
    public File readFrom(Class<File> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                         MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
        Path file = Files.createTempFile("jaxrs-entity", null);
        // into the created file, keeping the owner-only permissions createTempFile gave it
        try (OutputStream out = Files.newOutputStream(file)) {
            entityStream.transferTo(out);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }
        File read = file.toFile();
        TEMPORARY_FILES.add(file);
        CLEANER.register(read, () -> delete(file));
        return read;
    }

    /**
     * @return The number of the temporary files not deleted yet
     */
    static int temporaryFiles() {
        return TEMPORARY_FILES.size();
    }

    private static void delete(Path file) {
        TEMPORARY_FILES.remove(file);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // e.g. moved, or removed by the application
        }
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return File.class.isAssignableFrom(type);
    }

    @Override
    public long getSize(File file, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return file.length();
    }

    @Override
    public void writeTo(File file, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
        Files.copy(file.toPath(), entityStream);
    }
}
