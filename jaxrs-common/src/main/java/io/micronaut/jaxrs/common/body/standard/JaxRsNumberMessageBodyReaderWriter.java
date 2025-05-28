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
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.jaxrs.common.JaxRsIOException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * The read/write body for {@link Number}.
 *
 * @param <T> The number type
 * @author Denis Stepanov
 * @since 4.9
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@Produces(MediaType.TEXT_PLAIN)
@Consumes(MediaType.TEXT_PLAIN)
@Prototype
@Internal
final class JaxRsNumberMessageBodyReaderWriter<T extends Number> implements jakarta.ws.rs.ext.MessageBodyReader<T>, MessageBodyWriter<Number> {

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
        return Number.class.isAssignableFrom(type) && jakarta.ws.rs.core.MediaType.TEXT_PLAIN_TYPE.isCompatible(mediaType);
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
        return isReadable(type, genericType, annotations, mediaType);
    }

    @Override
    public T readFrom(Class<T> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
        String text = JaxRsStringMessageBodyReaderWriter.readToString(entityStream, mediaType, true);
        return (T) readNumber(type, text);
    }

    private static <T extends Number> Number readNumber(Class<T> type, String text) {
        Class<?> genType = ReflectionUtils.getWrapperType(type);
        if (genType == Byte.class) {
            return Byte.valueOf(text);
        }
        if (genType == Short.class) {
            return Integer.valueOf(text);
        }
        if (genType == Integer.class) {
            return Integer.valueOf(text);
        }
        if (genType == Long.class) {
            return Long.valueOf(text);
        }
        if (genType == Float.class) {
            return Float.valueOf(text);
        }
        if (genType == Double.class) {
            return Double.valueOf(text);
        }
        if (genType == BigDecimal.class) {
            return new BigDecimal(text);
        }
        if (genType == BigInteger.class) {
            return new BigInteger(text);
        }
        throw new IllegalArgumentException("Unsupported type: " + type);
    }

    @Override
    public void writeTo(Number number, Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
        try {
            entityStream.write(
                convertToBytes(number, mediaType)
            );
        } catch (IOException e) {
            throw new JaxRsIOException(e);
        }
    }

    private byte[] convertToBytes(Number number, jakarta.ws.rs.core.MediaType mediaType) {
        String charset = mediaType.getParameters().getOrDefault(MediaType.CHARSET_PARAMETER, StandardCharsets.UTF_8.name());
        if (number instanceof Byte) {
            try {
                return Byte.toString(number.byteValue()).getBytes(charset);
            } catch (UnsupportedEncodingException e) {
                return Byte.toString(number.byteValue()).getBytes();
            }
        }
        if (number instanceof Double) {
            try {
                return Double.toString(number.doubleValue()).getBytes(charset);
            } catch (UnsupportedEncodingException e) {
                return Double.toString(number.doubleValue()).getBytes();
            }
        }
        if (number instanceof Float) {
            try {
                return Float.toString(number.floatValue()).getBytes(charset);
            } catch (UnsupportedEncodingException e) {
                return Float.toString(number.floatValue()).getBytes();
            }
        }
        if (number instanceof Integer) {
            try {
                return Integer.toString(number.intValue()).getBytes(charset);
            } catch (UnsupportedEncodingException e) {
                return Integer.toString(number.intValue()).getBytes();
            }
        }
        if (number instanceof Long) {
            try {
                return Long.toString(number.longValue()).getBytes(charset);
            } catch (UnsupportedEncodingException e) {
                return Long.toString(number.longValue()).getBytes();
            }
        }
        if (number instanceof Short) {
            try {
                return Short.toString(number.shortValue()).getBytes(charset);
            } catch (UnsupportedEncodingException e) {
                return Short.toString(number.shortValue()).getBytes();
            }
        }
        if (number instanceof BigDecimal) {
            try {
                return BigDecimal.class.cast(number).toString().getBytes(charset);
            } catch (UnsupportedEncodingException e) {
                return BigDecimal.class.cast(number).toString().getBytes();
            }
        }
        if (number instanceof BigInteger) {
            try {
                return BigInteger.class.cast(number).toString().getBytes(charset);
            } catch (UnsupportedEncodingException e) {
                return BigInteger.class.cast(number).toString().getBytes();
            }
        }
        throw new IllegalStateException("Unsupported type: " + number.getClass());
    }
}
