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
package io.micronaut.jaxrs.runtime.core;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.DynamicMessageBodyWriter;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.codec.CodecException;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * The JAX RS response body writer
 *
 * @author graemerocher
 * @since 4.6.0
 */
@Singleton
@Internal
final class JaxRsMessageBodyWriter implements MessageBodyWriter<Response> {

    private final DynamicMessageBodyWriter dynamicMessageBodyWriter;

    JaxRsMessageBodyWriter(MessageBodyHandlerRegistry registry) {
        this.dynamicMessageBodyWriter = new DynamicMessageBodyWriter(registry, List.of());
    }

    @Override
    public boolean isWriteable(@NonNull Argument<Response> type, @Nullable MediaType mediaType) {
        return true;
    }

    @Override
    public void writeTo(@NonNull Argument<Response> type, @NonNull MediaType mediaType, Response object, @NonNull MutableHeaders outgoingHeaders, @NonNull OutputStream outputStream) throws CodecException {
        object.getStringHeaders().forEach((name, list) -> {
            for (String value : list) {
                outgoingHeaders.add(name, value);
            }
        });
        if (object.hasEntity()) {
            Argument<Object> argument = (Argument<Object>) Argument.of(object.getEntity().getClass());
            dynamicMessageBodyWriter.writeTo(argument, mediaType, object.getEntity(), outgoingHeaders, outputStream);
        } else {
            try {
                outputStream.flush();
            } catch (IOException e) {
                throw new CodecException(e.getMessage(), e);
            }
        }
    }
}
