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

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;
import jakarta.inject.Singleton;
import jakarta.json.JsonException;
import jakarta.json.JsonReader;
import jakarta.json.JsonReaderFactory;
import jakarta.json.JsonValue;
import jakarta.json.JsonWriter;
import jakarta.json.JsonWriterFactory;
import jakarta.json.spi.JsonProvider;
import jakarta.json.stream.JsonParsingException;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NoContentException;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import org.jspecify.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * The standard provider of JSON-P for the JSON media types (JAX-RS 11.2.6), when the JSON-P API
 * and an implementation, e.g. Eclipse Parsson, are present: it reads and writes {@link JsonValue}
 * and its subtypes, {@code JsonStructure}, {@code JsonObject}, {@code JsonArray},
 * {@code JsonString} and {@code JsonNumber}. It has the default priority, so it comes before
 * the JSON-B provider, which reads and writes any type, and the readers and writers of the
 * application come before it (JAX-RS 4.1.3). An empty entity is not a JSON value: reading one
 * throws a {@link NoContentException}, a 400 response.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Singleton
// the annotations of the provider the client registers, without reflection
@Introspected
@Internal
@Requires(classes = JsonValue.class)
@Requires(condition = JsonpImplementationCondition.class)
@Consumes({MediaType.APPLICATION_JSON, "text/json", "application/*+json"})
@Produces({MediaType.APPLICATION_JSON, "text/json", "application/*+json"})
public final class JaxRsJsonpMessageBodyReaderWriter implements MessageBodyReader<JsonValue>, MessageBodyWriter<JsonValue> {

    private volatile @Nullable JsonReaderFactory readerFactory;
    private volatile @Nullable JsonWriterFactory writerFactory;

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return JsonValue.class.isAssignableFrom(type) && isJson(mediaType);
    }

    @Override
    public JsonValue readFrom(Class<JsonValue> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                              MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
        // the entity stream is the container's to close
        InputStream in = new BufferedInputStream(entityStream) {
            @Override
            public void close() {
            }
        };
        in.mark(1);
        if (in.read() == -1) {
            throw new NoContentException("The JSON entity is empty");
        }
        in.reset();
        Charset charset = charset(mediaType);
        JsonValue value;
        try (JsonReader reader = charset == null ? readerFactory().createReader(in) : readerFactory().createReader(in, charset)) {
            value = reader.readValue();
        } catch (JsonParsingException e) {
            throw new BadRequestException(e);
        } catch (JsonException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new BadRequestException(e);
        }
        if (!type.isInstance(value)) {
            // e.g. an array read as a JsonObject
            throw new BadRequestException("The entity is a JSON " + value.getValueType().name().toLowerCase(Locale.ROOT)
                + " value, not the type of the entity parameter");
        }
        return value;
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return JsonValue.class.isAssignableFrom(type) && isJson(mediaType);
    }

    @Override
    public void writeTo(JsonValue value, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
        Charset charset = charset(mediaType);
        try (JsonWriter writer = writerFactory().createWriter(new NonClosingOutputStream(entityStream),
            charset == null ? StandardCharsets.UTF_8 : charset)) {
            writer.write(value);
        } catch (JsonException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new InternalServerErrorException(e);
        }
    }

    private static boolean isJson(@Nullable MediaType mediaType) {
        if (mediaType == null) {
            return false;
        }
        // media type tokens are case-insensitive (RFC 9110 8.3.1)
        String subtype = mediaType.getSubtype().toLowerCase(Locale.ROOT);
        return ("application".equalsIgnoreCase(mediaType.getType()) || "text".equalsIgnoreCase(mediaType.getType()))
            && ("json".equals(subtype) || subtype.endsWith("+json"));
    }

    private static @Nullable Charset charset(@Nullable MediaType mediaType) {
        String charset = mediaType == null ? null : mediaType.getParameters().get(MediaType.CHARSET_PARAMETER);
        return charset == null ? null : Charset.forName(charset);
    }

    private JsonReaderFactory readerFactory() {
        JsonReaderFactory factory = readerFactory;
        if (factory == null) {
            factory = JsonProvider.provider().createReaderFactory(Map.of());
            readerFactory = factory;
        }
        return factory;
    }

    private JsonWriterFactory writerFactory() {
        JsonWriterFactory factory = writerFactory;
        if (factory == null) {
            factory = JsonProvider.provider().createWriterFactory(Map.of());
            writerFactory = factory;
        }
        return factory;
    }

    /**
     * The entity stream, which the writer closes, is the container's to close (JAX-RS 4.2.2).
     */
    private static final class NonClosingOutputStream extends OutputStream {

        private final OutputStream out;

        NonClosingOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.flush();
        }
    }
}
