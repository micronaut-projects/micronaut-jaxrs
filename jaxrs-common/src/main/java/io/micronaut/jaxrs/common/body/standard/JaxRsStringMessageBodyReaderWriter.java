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
import io.micronaut.jaxrs.common.JaxRsIOException;
import io.micronaut.jaxrs.common.JaxRsMessageBodyProvider;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NoContentException;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * The implementation of {@link MessageBodyReader} for {@link String}.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@JaxRsMessageBodyProvider(
    readerType = String.class,
    writerType = String.class,
    consumes = MediaType.WILDCARD,
    produces = MediaType.WILDCARD
)
@Prototype
@Internal
public final class JaxRsStringMessageBodyReaderWriter implements MessageBodyReader<String>, MessageBodyWriter<String> {
    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return String.class.isAssignableFrom(type);
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return String.class.isAssignableFrom(type);
    }

    @Override
    public String readFrom(Class<String> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws WebApplicationException {
        return readToString(entityStream, mediaType, false);
    }

    static String readToString(InputStream entityStream, MediaType mediaType, boolean requiredNonEmpty) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(entityStream, getCharset(mediaType)))) {
            String text = IOUtils.readText(reader);
            if (requiredNonEmpty && text.isEmpty()) {
                throw new NoContentException("No content");
            }
            return text;
        } catch (IOException e) {
            throw new JaxRsIOException(e);
        }
    }

    private static Charset getCharset(MediaType mediaType) {
        if (mediaType == null) {
            return StandardCharsets.UTF_8;
        }
        String charsetString = mediaType.getParameters().get(MediaType.CHARSET_PARAMETER);
        if (charsetString != null) {
            return Charset.forName(charsetString);
        }
        return StandardCharsets.UTF_8;
    }

    @Override
    public void writeTo(String string, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
        entityStream.write(string.getBytes(getCharset(mediaType)));
    }
}
