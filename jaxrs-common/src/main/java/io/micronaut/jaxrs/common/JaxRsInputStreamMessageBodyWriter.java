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
package io.micronaut.jaxrs.common;

import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.body.MessageBodyWriter;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MultivaluedMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/**
 * The implementation of {@link MessageBodyWriter} for {@link InputStream}.
 *
 * @param <T> The input type
 * @author Denis Stepanov
 * @since 4.6
 */
@Prototype
@Internal
public final class JaxRsInputStreamMessageBodyWriter<T extends InputStream> implements jakarta.ws.rs.ext.MessageBodyWriter<T> {

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
        return InputStream.class.isAssignableFrom(type);
    }

    @Override
    public void writeTo(T inputStream,
                        Class<?> type,
                        Type genericType,
                        Annotation[] annotations,
                        jakarta.ws.rs.core.MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders,
                        OutputStream entityStream) throws IOException, WebApplicationException {
        try (OutputStreamWriter out = new OutputStreamWriter(entityStream)) {
            new InputStreamReader(inputStream).transferTo(out);
        }
        try {
            inputStream.close();
        } catch (IOException ignore) {
            // Ignore
        }
    }
}
