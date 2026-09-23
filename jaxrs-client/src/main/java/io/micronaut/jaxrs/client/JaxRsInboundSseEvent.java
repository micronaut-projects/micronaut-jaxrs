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
package io.micronaut.jaxrs.client;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.simple.SimpleHttpHeaders;
import io.micronaut.jaxrs.common.JaxRsArgumentUtil;
import io.micronaut.jaxrs.common.JaxRsUtils;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.InboundSseEvent;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * An event a client received, whose data the readers of the client read (JAX-RS 9.4).
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
final class JaxRsInboundSseEvent implements InboundSseEvent {

    private final @Nullable String name;
    private final @Nullable String id;
    private final @Nullable String comment;
    private final long reconnectDelay;
    private final @Nullable String data;
    private final JaxRsConfiguration configuration;

    JaxRsInboundSseEvent(@Nullable String name, @Nullable String id, @Nullable String comment, long reconnectDelay,
                         @Nullable String data, JaxRsConfiguration configuration) {
        this.name = name;
        this.id = id;
        this.comment = comment;
        this.reconnectDelay = reconnectDelay;
        this.data = data;
        this.configuration = configuration;
    }

    @Override
    public @Nullable String getId() {
        return id;
    }

    @Override
    public @Nullable String getName() {
        return name;
    }

    @Override
    public @Nullable String getComment() {
        return comment;
    }

    @Override
    public long getReconnectDelay() {
        return reconnectDelay;
    }

    @Override
    public boolean isReconnectDelaySet() {
        return reconnectDelay != RECONNECT_NOT_SET;
    }

    @Override
    public boolean isEmpty() {
        return data == null || data.isEmpty();
    }

    @Override
    public @Nullable String readData() {
        return data;
    }

    @Override
    public <T> @Nullable T readData(Class<T> type) {
        return readData(Argument.of(type), MediaType.TEXT_PLAIN_TYPE);
    }

    @Override
    public <T> @Nullable T readData(GenericType<T> type) {
        return readData(JaxRsArgumentUtil.from(type), MediaType.TEXT_PLAIN_TYPE);
    }

    @Override
    public @Nullable Object readData(Class type, MediaType mediaType) {
        return readData(Argument.of(type), mediaType);
    }

    @Override
    public @Nullable Object readData(GenericType type, MediaType mediaType) {
        return readData(JaxRsArgumentUtil.from(type), mediaType);
    }

    private <T> @Nullable T readData(Argument<T> type, MediaType mediaType) {
        io.micronaut.http.MediaType micronautType = Objects.requireNonNull(JaxRsUtils.convert(mediaType));
        MessageBodyReader<T> reader = configuration.findReader(type, micronautType);
        if (reader == null) {
            throw new ProcessingException("No reader reads the data of the event as " + type + " and " + mediaType);
        }
        byte[] bytes = data == null ? new byte[0] : data.getBytes(StandardCharsets.UTF_8);
        return reader.read(type, micronautType, new SimpleHttpHeaders(), new ByteArrayInputStream(bytes));
    }

    @Override
    public String toString() {
        return "InboundSseEvent[name=" + name + ", id=" + id + ", data=" + data + "]";
    }
}
