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
package io.micronaut.jaxrs.container;

import io.micronaut.core.annotation.Internal;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.SseEvent;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.Objects;

/**
 * Default {@link OutboundSseEvent} implementation.
 */
@Internal
final class JaxRsSseEvent implements OutboundSseEvent {
    private final @Nullable String id;
    private final @Nullable String name;
    private final @Nullable String comment;
    private final long reconnectDelay;
    private final MediaType mediaType;
    private final Class<?> type;
    private final Type genericType;
    private final Object data;

    private JaxRsSseEvent(@Nullable String id,
                          @Nullable String name,
                          @Nullable String comment,
                          long reconnectDelay,
                          MediaType mediaType,
                          Class<?> type,
                          Type genericType,
                          Object data) {
        this.id = id;
        this.name = name;
        this.comment = comment;
        this.reconnectDelay = reconnectDelay;
        this.mediaType = mediaType;
        this.type = type;
        this.genericType = genericType;
        this.data = data;
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
        return reconnectDelay != SseEvent.RECONNECT_NOT_SET;
    }

    @Override
    public Class<?> getType() {
        return type;
    }

    @Override
    public Type getGenericType() {
        return genericType;
    }

    @Override
    public MediaType getMediaType() {
        return mediaType;
    }

    @Override
    public Object getData() {
        return data;
    }

    static final class Builder implements OutboundSseEvent.Builder {
        private @Nullable String id;
        private @Nullable String name;
        private @Nullable String comment;
        private long reconnectDelay = SseEvent.RECONNECT_NOT_SET;
        private MediaType mediaType = MediaType.TEXT_PLAIN_TYPE;
        private @Nullable Class<?> type;
        private @Nullable Type genericType;
        private @Nullable Object data;

        @Override
        public OutboundSseEvent.Builder id(String id) {
            this.id = id;
            return this;
        }

        @Override
        public OutboundSseEvent.Builder name(String name) {
            this.name = name;
            return this;
        }

        @Override
        public OutboundSseEvent.Builder reconnectDelay(long reconnectDelay) {
            this.reconnectDelay = reconnectDelay;
            return this;
        }

        @Override
        public OutboundSseEvent.Builder mediaType(MediaType mediaType) {
            this.mediaType = Objects.requireNonNull(mediaType, "mediaType");
            return this;
        }

        @Override
        public OutboundSseEvent.Builder comment(String comment) {
            this.comment = comment;
            return this;
        }

        @Override
        public OutboundSseEvent.Builder data(Class type, Object data) {
            this.type = Objects.requireNonNull(type, "type");
            this.genericType = type;
            this.data = Objects.requireNonNull(data, "data");
            return this;
        }

        @Override
        public OutboundSseEvent.Builder data(GenericType type, Object data) {
            Objects.requireNonNull(type, "type");
            this.type = type.getRawType();
            this.genericType = type.getType();
            this.data = Objects.requireNonNull(data, "data");
            return this;
        }

        @Override
        public OutboundSseEvent.Builder data(Object data) {
            this.data = Objects.requireNonNull(data, "data");
            this.type = data.getClass();
            this.genericType = data.getClass();
            return this;
        }

        @Override
        public OutboundSseEvent build() {
            Object resolvedData = Objects.requireNonNull(data, "data");
            Class<?> resolvedType = type == null ? resolvedData.getClass() : type;
            Type resolvedGenericType = genericType == null ? resolvedType : genericType;
            return new JaxRsSseEvent(
                id,
                name,
                comment,
                reconnectDelay,
                mediaType,
                resolvedType,
                resolvedGenericType,
                resolvedData
            );
        }
    }
}
