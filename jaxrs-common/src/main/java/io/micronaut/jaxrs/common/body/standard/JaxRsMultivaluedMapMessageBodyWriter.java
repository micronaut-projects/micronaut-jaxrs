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
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The standard writer of a {@code MultivaluedMap<String, String>} as a form (JAX-RS 4.2.4): a JAX-RS
 * provider, so it is sorted with the ones of the application, by its media type first.
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
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return MultivaluedMap.class.isAssignableFrom(type) && FormMaps.isForm(mediaType);
    }

    @Override
    public void writeTo(MultivaluedMap<String, String> map, Class<?> type, Type genericType, Annotation[] annotations,
                        MediaType mediaType, MultivaluedMap<String, Object> httpHeaders,
                        OutputStream entityStream) throws IOException {
        QueryStringEncoder encoder = new QueryStringEncoder("", FormMaps.charset(mediaType));
        for (Map.Entry<String, List<String>> e : map.entrySet()) {
            e.getValue().forEach(value -> encoder.addParam(e.getKey(), value));
        }
        // the encoder builds a query: the form is without its leading '?'
        String form = encoder.toString();
        entityStream.write((form.startsWith("?") ? form.substring(1) : form).getBytes(StandardCharsets.UTF_8));
    }
}
