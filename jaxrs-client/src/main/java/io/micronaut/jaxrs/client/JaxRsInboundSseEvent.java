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
package io.micronaut.jaxrs.client;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.http.sse.Event;
import io.micronaut.jaxrs.common.JaxRsUtils;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.sse.InboundSseEvent;
import jakarta.ws.rs.sse.SseEvent;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Type;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Default {@link InboundSseEvent} implementation.
 */
@Internal
final class JaxRsInboundSseEvent implements InboundSseEvent {
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;
    private static final List<JaxRsSseEventDataReader> OPTIONAL_DATA_READERS = optionalDataReaders();

    private final Event<String> event;
    private final @Nullable Path tempDirectory;

    JaxRsInboundSseEvent(Event<String> event, @Nullable Path tempDirectory) {
        this.event = event;
        this.tempDirectory = tempDirectory;
    }

    @Override
    public String getId() {
        return event.getId();
    }

    @Override
    public String getName() {
        return event.getName();
    }

    @Override
    public String getComment() {
        return event.getComment();
    }

    @Override
    public long getReconnectDelay() {
        Duration retry = event.getRetry();
        return retry == null ? SseEvent.RECONNECT_NOT_SET : retry.toMillis();
    }

    @Override
    public boolean isReconnectDelaySet() {
        return event.getRetry() != null;
    }

    @Override
    public boolean isEmpty() {
        String data = event.getData();
        return data == null || data.isEmpty();
    }

    @Override
    public String readData() {
        String data = event.getData();
        return data == null ? "" : data;
    }

    @Override
    public <T> T readData(Class<T> type) {
        return readData(type, MediaType.TEXT_PLAIN_TYPE);
    }

    @Override
    public <T> T readData(GenericType<T> type) {
        return readData(type, MediaType.TEXT_PLAIN_TYPE);
    }

    @Override
    public <T> T readData(Class<T> type, MediaType mediaType) {
        T result = readStandardData(type, type, mediaType);
        if (result != null) {
            return result;
        }
        return ConversionService.SHARED.convert(readData(), type)
            .orElseThrow(() -> new ProcessingException("Cannot convert SSE event data to " + type.getName()));
    }

    @Override
    public <T> T readData(GenericType<T> type, MediaType mediaType) {
        T result = readStandardData((Class<T>) type.getRawType(), type.getType(), mediaType);
        if (result != null) {
            return result;
        }
        return ConversionService.SHARED.convert(readData(), type.getRawType())
            .map(value -> (T) value)
            .orElseThrow(() -> new ProcessingException("Cannot convert SSE event data to " + type.getRawType().getName()));
    }

    @SuppressWarnings("unchecked")
    private <T> T readStandardData(Class<T> type, Type genericType, MediaType mediaType) {
        String data = readData();
        Charset charset = charset(mediaType);
        if (type == String.class || type == Object.class) {
            return (T) data;
        }
        if (type == byte[].class) {
            return (T) data.getBytes(charset);
        }
        if (InputStream.class.isAssignableFrom(type)) {
            return (T) new ByteArrayInputStream(data.getBytes(charset));
        }
        if (Reader.class.isAssignableFrom(type)) {
            return (T) new StringReader(data);
        }
        if (File.class.isAssignableFrom(type)) {
            return (T) file(data, charset, tempDirectory);
        }
        if (MultivaluedMap.class.isAssignableFrom(type)) {
            return (T) multivaluedMap(data, charset);
        }
        for (JaxRsSseEventDataReader reader : OPTIONAL_DATA_READERS) {
            Optional<T> result = reader.readData(type, genericType, mediaType, data);
            if (result.isPresent()) {
                return result.get();
            }
        }
        return null;
    }

    private static Charset charset(MediaType mediaType) {
        if (mediaType != null) {
            String charset = mediaType.getParameters().get("charset");
            if (charset != null) {
                return Charset.forName(charset);
            }
        }
        return DEFAULT_CHARSET;
    }

    private static File file(String data, Charset charset, @Nullable Path tempDirectory) {
        try {
            File file = JaxRsUtils.createTempFile("jaxrs-sse-", ".tmp", tempDirectory);
            file.deleteOnExit();
            java.nio.file.Files.writeString(file.toPath(), data, charset);
            return file;
        } catch (IOException e) {
            throw new ProcessingException("Cannot write SSE event data to file", e);
        }
    }

    private static MultivaluedMap<String, String> multivaluedMap(String data, Charset charset) {
        String body = data.startsWith("?") ? data.substring(1) : data;
        MultivaluedHashMap<String, String> map = new MultivaluedHashMap<>();
        if (body.isEmpty()) {
            return map;
        }
        for (String parameter : body.split("[&;]")) {
            if (parameter.isEmpty()) {
                continue;
            }
            int separator = parameter.indexOf('=');
            String name = separator > -1 ? parameter.substring(0, separator) : parameter;
            String value = separator > -1 ? parameter.substring(separator + 1) : "";
            map.add(URLDecoder.decode(name, charset), URLDecoder.decode(value, charset));
        }
        return map;
    }

    private static List<JaxRsSseEventDataReader> optionalDataReaders() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = JaxRsInboundSseEvent.class.getClassLoader();
        }
        return ServiceLoader.load(JaxRsSseEventDataReader.class, classLoader)
            .stream()
            .map(ServiceLoader.Provider::get)
            .toList();
    }
}
