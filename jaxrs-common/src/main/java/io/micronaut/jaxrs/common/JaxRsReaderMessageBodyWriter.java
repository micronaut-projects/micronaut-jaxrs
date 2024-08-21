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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.http.CaseInsensitiveMutableHttpHeaders;
import jakarta.inject.Singleton;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The implementation of {@link MessageBodyWriter} for {@link Reader}.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Singleton
@Internal
public final class JaxRsReaderMessageBodyWriter implements MessageBodyWriter<Reader> {

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
        return Reader.class.isAssignableFrom(type);
    }

    @Override
    public void writeTo(Reader reader,
                        Class<?> type,
                        Type genericType,
                        Annotation[] annotations,
                        jakarta.ws.rs.core.MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders,
                        OutputStream entityStream) throws IOException, WebApplicationException {
        Charset charset = io.micronaut.http.body.MessageBodyWriter.
            findCharset(JaxRsUtils.convert(mediaType), new CaseInsensitiveMutableHttpHeaders((Map) httpHeaders, ConversionService.SHARED))
            .orElse(StandardCharsets.UTF_8);
        try (OutputStreamWriter out = new OutputStreamWriter(entityStream, charset)) {
            reader.transferTo(out);
        }
        try {
            reader.close();
        } catch (IOException ignore) {
            // Ignore
        }
    }
}
