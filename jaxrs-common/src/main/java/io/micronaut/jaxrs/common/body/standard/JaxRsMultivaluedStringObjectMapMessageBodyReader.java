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
package io.micronaut.jaxrs.common.body.standard;

import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.io.IOUtils;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.Headers;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.codec.CodecException;
import io.micronaut.http.form.FormUrlEncodedDecoder;
import io.micronaut.jaxrs.common.JaxRsIOException;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The implementation of {@link MessageBodyReader} for {@link MessageBodyReader}.
 *
 * @author Denis Stepanov
 * @since 4.9
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
@Internal
@Prototype
public final class JaxRsMultivaluedStringObjectMapMessageBodyReader implements MessageBodyReader<MultivaluedMap<String, Object>> {

    private final FormUrlEncodedDecoder formUrlEncodedDecoder = new DefaultFormUrlEncodedDecoder();

    @Override
    public boolean isReadable(@NonNull Argument<MultivaluedMap<String, Object>> type, @Nullable MediaType mediaType) {
        return MessageBodyReader.super.isReadable(type, mediaType) && MediaType.APPLICATION_FORM_URLENCODED_TYPE.equals(mediaType);
    }

    @Override
    public @Nullable MultivaluedMap<String, Object> read(@NonNull Argument<MultivaluedMap<String, Object>> type,
                                                         @Nullable MediaType mediaType,
                                                         @NonNull Headers httpHeaders,
                                                         @NonNull InputStream inputStream) throws CodecException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            Map<String, Object> decoded = formUrlEncodedDecoder.decode(
                IOUtils.readText(reader),
                mediaType.getCharset().orElse(StandardCharsets.UTF_8)
            );
            MultivaluedHashMap<String, Object> map = new MultivaluedHashMap<>();
            decoded.forEach(map::add);
            return map;
        } catch (IOException e) {
            throw new JaxRsIOException("Failed to read to a string", e);
        }
    }
}
