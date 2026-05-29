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
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.io.buffer.ByteBufferFactory;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.codec.CodecException;
import io.micronaut.jaxrs.common.JaxRsIOException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MultivaluedMap;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The writer of {@link MultivaluedMap} for {@link MessageBodyReader}.
 *
 * @author Denis Stepanov
 * @since 4.9
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@Produces(MediaType.APPLICATION_FORM_URLENCODED)
@Internal
@Prototype
public final class JaxRsMultivaluedMapMessageBodyWriter implements MessageBodyWriter<MultivaluedMap<String, String>> {

    @Override
    public boolean isWriteable(@NonNull Argument<MultivaluedMap<String, String>> type, @Nullable MediaType mediaType) {
        return MessageBodyWriter.super.isWriteable(type, mediaType) && MediaType.APPLICATION_FORM_URLENCODED_TYPE.equals(mediaType);
    }

    @Override
    public @NonNull ByteBuffer<?> writeTo(@NonNull Argument<MultivaluedMap<String, String>> type, @NonNull MediaType mediaType, MultivaluedMap<String, String> object, @NonNull MutableHeaders outgoingHeaders, @NonNull ByteBufferFactory<?, ?> bufferFactory) throws CodecException {
        return MessageBodyWriter.super.writeTo(type, mediaType, object, outgoingHeaders, bufferFactory);
    }

    @Override
    public void writeTo(@NonNull Argument<MultivaluedMap<String, String>> type, @NonNull MediaType mediaType, MultivaluedMap<String, String> object, @NonNull MutableHeaders outgoingHeaders, @NonNull OutputStream outputStream) throws CodecException {
        outgoingHeaders.setIfMissing(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED);
        try {
            QueryStringEncoder encoder = new QueryStringEncoder("", StandardCharsets.UTF_8);
            for (Map.Entry<String, List<String>> e : object.entrySet()) {
                e.getValue().forEach(value -> encoder.addParam(e.getKey(), value));
            }
            String encoded = encoder.toString();
            outputStream.write((encoded.startsWith("?") ? encoded.substring(1) : encoded).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new JaxRsIOException(e);
        }
    }

}
