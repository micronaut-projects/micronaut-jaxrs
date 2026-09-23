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
package io.micronaut.jaxrs.common.sse;

import io.micronaut.core.annotation.Internal;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.Objects;

/**
 * An event a server sends.
 *
 * @param name           The name
 * @param id             The id
 * @param comment        The comment
 * @param reconnectDelay The reconnect delay in milliseconds, {@link #RECONNECT_NOT_SET} if not set
 * @param type           The type of the data
 * @param genericType    The generic type of the data
 * @param mediaType      The media type of the data
 * @param data           The data
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
public record JaxRsOutboundSseEvent(@Nullable String name,
                                    @Nullable String id,
                                    @Nullable String comment,
                                    long reconnectDelay,
                                    Class<?> type,
                                    Type genericType,
                                    MediaType mediaType,
                                    @Nullable Object data) implements OutboundSseEvent {

    @Override
    public @Nullable String getName() {
        return name;
    }

    @Override
    public @Nullable String getId() {
        return id;
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
    public @Nullable Object getData() {
        return data;
    }

    /**
     * The builder of an event, see {@link jakarta.ws.rs.sse.Sse#newEventBuilder()}.
     */
    public static final class Builder implements OutboundSseEvent.Builder {
        private @Nullable String name;
        private @Nullable String id;
        private @Nullable String comment;
        private long reconnectDelay = RECONNECT_NOT_SET;
        private @Nullable Class<?> type;
        private @Nullable Type genericType;
        private MediaType mediaType = MediaType.TEXT_PLAIN_TYPE;
        private @Nullable Object data;

        @Override
        public Builder id(@Nullable String id) {
            this.id = id;
            return this;
        }

        @Override
        public Builder name(@Nullable String name) {
            this.name = name;
            return this;
        }

        @Override
        public Builder reconnectDelay(long milliseconds) {
            this.reconnectDelay = milliseconds < 0 ? RECONNECT_NOT_SET : milliseconds;
            return this;
        }

        @Override
        public Builder mediaType(MediaType mediaType) {
            this.mediaType = Objects.requireNonNull(mediaType, "mediaType");
            return this;
        }

        @Override
        public Builder comment(@Nullable String comment) {
            this.comment = comment;
            return this;
        }

        @Override
        public Builder data(Class type, Object data) {
            this.type = Objects.requireNonNull(type, "type");
            this.genericType = type;
            this.data = Objects.requireNonNull(data, "data");
            return this;
        }

        @Override
        public Builder data(GenericType type, Object data) {
            Objects.requireNonNull(type, "type");
            this.type = type.getRawType();
            this.genericType = type.getType();
            this.data = Objects.requireNonNull(data, "data");
            return this;
        }

        @Override
        public Builder data(Object data) {
            Objects.requireNonNull(data, "data");
            if (data instanceof jakarta.ws.rs.core.GenericEntity<?> entity) {
                this.type = entity.getRawType();
                this.genericType = entity.getType();
                this.data = entity.getEntity();
            } else {
                this.type = data.getClass();
                this.genericType = data.getClass();
                this.data = data;
            }
            return this;
        }

        @Override
        public OutboundSseEvent build() {
            if (comment == null && data == null) {
                throw new IllegalStateException("An event needs a comment or data");
            }
            Class<?> dataType = type == null ? String.class : type;
            return new JaxRsOutboundSseEvent(name, id, comment, reconnectDelay, dataType,
                genericType == null ? dataType : genericType, mediaType, data);
        }
    }
}
