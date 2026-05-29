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
package io.micronaut.jaxrs.xml;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.jaxrs.client.JaxRsSseEventDataReader;
import jakarta.activation.DataSource;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.MediaType;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Optional XML and JAXB conversions for SSE event data.
 */
@Internal
public final class JaxRsXmlSseEventDataReader implements JaxRsSseEventDataReader {
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    /**
     * Default constructor used by service loading.
     */
    public JaxRsXmlSseEventDataReader() {
    }

    @Override
    public <T> Optional<T> readData(Class<T> type, Type genericType, MediaType mediaType, String data) {
        Charset charset = charset(mediaType);
        if (DataSource.class.isAssignableFrom(type)) {
            return Optional.of(type.cast(new ByteArrayDataSource(data.getBytes(charset), mediaType == null ? MediaType.TEXT_PLAIN : mediaType.toString())));
        }
        if (Source.class.isAssignableFrom(type)) {
            return Optional.of(type.cast(new StreamSource(new ByteArrayInputStream(data.getBytes(charset)))));
        }
        if (JAXBElement.class.isAssignableFrom(type)) {
            return Optional.of(type.cast(readJaxbElement(genericType, data)));
        }
        if (isXml(mediaType)) {
            return Optional.of(readJaxbObject(type, data));
        }
        return Optional.empty();
    }

    private static boolean isXml(MediaType mediaType) {
        if (mediaType == null) {
            return false;
        }
        String subtype = mediaType.getSubtype().toLowerCase(Locale.ROOT);
        return "xml".equals(subtype) || subtype.endsWith("+xml");
    }

    private static Charset charset(MediaType mediaType) {
        if (mediaType != null) {
            String charset = mediaType.getParameters().get("charset");
            if (charset != null) {
                return Charset.forName(charset);
            }
        }
        return DEFAULT_CHARSET;
    }

    private static JAXBElement<?> readJaxbElement(Type genericType, String data) {
        Class<?> declaredType = declaredType(genericType);
        try {
            XMLStreamReader reader = xmlInputFactory().createXMLStreamReader(new StringReader(data));
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                        QName name = reader.getName();
                        String text = reader.getElementText();
                        Object value = text;
                        if (declaredType != String.class) {
                            Optional<?> converted = ConversionService.SHARED.convert(text, declaredType);
                            if (converted.isPresent()) {
                                value = converted.get();
                            }
                        }
                        return new JAXBElement(name, declaredType, value);
                    }
                }
                throw new ProcessingException("Missing XML root element in SSE event data");
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new ProcessingException("Cannot read SSE event data as JAXBElement", e);
        }
    }

    private static <T> T readJaxbObject(Class<T> type, String data) {
        try {
            Object result = JAXBContext.newInstance(type).createUnmarshaller().unmarshal(new StringReader(data));
            if (result instanceof JAXBElement<?> element) {
                result = element.getValue();
            }
            return type.cast(result);
        } catch (JAXBException | ClassCastException e) {
            throw new ProcessingException("Cannot read SSE event data as " + type.getName(), e);
        }
    }

    private static Class<?> declaredType(Type genericType) {
        if (genericType instanceof ParameterizedType parameterizedType) {
            Type typeArgument = parameterizedType.getActualTypeArguments()[0];
            if (typeArgument instanceof Class<?> type) {
                return type;
            }
        }
        return String.class;
    }

    private static XMLInputFactory xmlInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        disable(factory, XMLInputFactory.SUPPORT_DTD);
        disable(factory, "javax.xml.stream.isSupportingExternalEntities");
        return factory;
    }

    private static void disable(XMLInputFactory factory, String propertyName) {
        try {
            factory.setProperty(propertyName, false);
        } catch (IllegalArgumentException ignored) {
            // Some XMLInputFactory implementations do not support every hardening property.
        }
    }

    private record ByteArrayDataSource(byte[] bytes, String contentType) implements DataSource {

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            throw new IOException("Read-only DataSource");
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public String getName() {
            return "jaxrs-sse";
        }
    }
}
