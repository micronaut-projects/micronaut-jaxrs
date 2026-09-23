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
import io.micronaut.core.io.IOUtils;
import io.micronaut.core.order.Ordered;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The standard reader of a form as a {@code MultivaluedMap<String, String>} (JAX-RS 4.2.4): a JAX-RS
 * provider, so it is sorted with the ones of the application, by its media type first.
 *
 * @author Denis Stepanov
 * @since 4.9
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
@Internal
@Prototype
public final class JaxRsMultivaluedStringStringMapMessageBodyReader implements MessageBodyReader<MultivaluedMap<String, String>> {

    private final DefaultFormUrlEncodedDecoder formUrlEncodedDecoder = new DefaultFormUrlEncodedDecoder();

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return FormMaps.isFormMap(type) && FormMaps.isForm(mediaType);
    }

    @Override
    public MultivaluedMap<String, String> readFrom(Class<MultivaluedMap<String, String>> type, Type genericType, Annotation[] annotations,
                                                MediaType mediaType, MultivaluedMap<String, String> httpHeaders,
                                                InputStream entityStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(entityStream, StandardCharsets.UTF_8));
        Map<String, List<String>> decoded = formUrlEncodedDecoder.decodeAll(IOUtils.readText(reader), FormMaps.charset(mediaType));
        MultivaluedHashMap<String, String> map = new MultivaluedHashMap<>();
        // every value of a field repeated in the form
        decoded.forEach((key, values) -> map.addAll(key, values));
        return map;
    }
}
