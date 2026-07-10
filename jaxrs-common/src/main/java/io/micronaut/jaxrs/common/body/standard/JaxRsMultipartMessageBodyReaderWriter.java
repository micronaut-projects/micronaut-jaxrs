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
package io.micronaut.jaxrs.common.body.standard;

import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.jaxrs.common.JaxRsMessageBodyProvider;
import io.micronaut.jaxrs.common.JaxRsMultipart;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Multipart/form-data reader and writer for {@link EntityPart} lists.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@JaxRsMessageBodyProvider(
    readerType = List.class,
    writerType = List.class,
    consumes = JaxRsMultipart.MULTIPART_FORM_DATA,
    produces = JaxRsMultipart.MULTIPART_FORM_DATA
)
@Prototype
@Internal
public final class JaxRsMultipartMessageBodyReaderWriter implements MessageBodyReader<List<EntityPart>>, MessageBodyWriter<List<EntityPart>> {

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return List.class.isAssignableFrom(type) && isEntityPartList(genericType) && JaxRsMultipart.isMultipartFormData(mediaType);
    }

    @Override
    public List<EntityPart> readFrom(Class<List<EntityPart>> type,
                                     Type genericType,
                                     Annotation[] annotations,
                                     MediaType mediaType,
                                     MultivaluedMap<String, String> httpHeaders,
                                     InputStream entityStream) throws IOException, WebApplicationException {
        return JaxRsMultipart.readParts(entityStream, mediaType);
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return List.class.isAssignableFrom(type) && isEntityPartList(genericType) && JaxRsMultipart.isMultipartFormData(mediaType);
    }

    @Override
    public void writeTo(List<EntityPart> entityParts,
                        Class<?> type,
                        Type genericType,
                        Annotation[] annotations,
                        MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders,
                        OutputStream entityStream) throws IOException, WebApplicationException {
        String boundary = JaxRsMultipart.writeParts(entityParts, entityStream, mediaType);
        httpHeaders.putSingle(HttpHeaders.CONTENT_TYPE, JaxRsMultipart.MULTIPART_FORM_DATA + "; boundary=" + boundary);
    }

    private static boolean isEntityPartList(Type genericType) {
        if (genericType instanceof ParameterizedType parameterizedType) {
            Type[] arguments = parameterizedType.getActualTypeArguments();
            return arguments.length == 1 && arguments[0] == EntityPart.class;
        }
        return true;
    }
}
