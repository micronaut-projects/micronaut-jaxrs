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
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.jaxrs.common.JaxRsMessageBodyProvider;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/**
 * The implementation of {@link MessageBodyWriter} for {@link StreamingOutput}.
 *
 * @param <T> The steaming output
 * @author Denis Stepanov
 * @since 4.6
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@JaxRsMessageBodyProvider(
    writerType = StreamingOutput.class,
    writerTypeVariable = true,
    produces = jakarta.ws.rs.core.MediaType.WILDCARD
)
@Prototype
@Internal
public final class JaxRsStreamingOutputMessageBodyWriter<T extends StreamingOutput> implements MessageBodyWriter<T> {

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
        return StreamingOutput.class.isAssignableFrom(type);
    }

    @Override
    public void writeTo(T streamingOutput, Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
        streamingOutput.write(entityStream);
    }
}
