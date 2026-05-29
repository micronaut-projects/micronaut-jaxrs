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

import io.micronaut.context.annotation.Prototype;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.jaxrs.common.JaxRsMessageBodyProvider;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import org.jspecify.annotations.Nullable;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * The read/write body for {@link JAXBElement}.
 *
 * @author Denis Stepanov
 * @since 4.9
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@JaxRsMessageBodyProvider(
    readerType = JAXBElement.class,
    writerType = JAXBElement.class,
    consumes = { MediaType.APPLICATION_XML, MediaType.TEXT_XML, "application/*+xml" },
    produces = { MediaType.APPLICATION_XML, MediaType.TEXT_XML, "application/*+xml" }
)
@Prototype
@Requires(classes = { JAXBContext.class, JAXBElement.class })
@Internal
@Consumes({ MediaType.APPLICATION_XML, MediaType.TEXT_XML, "application/*+xml" })
@Produces({ MediaType.APPLICATION_XML, MediaType.TEXT_XML, "application/*+xml" })
public final class JaxRsJaxbElementMessageBodyReaderWriter implements MessageBodyReader<JAXBElement<?>>, MessageBodyWriter<JAXBElement<?>> {
    private final @Nullable Providers providers;

    /**
     * Creates a JAXB element reader/writer.
     *
     * @param providers The Jakarta REST providers registry, if available
     */
    public JaxRsJaxbElementMessageBodyReaderWriter(@Nullable Providers providers) {
        this.providers = providers;
    }

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return JAXBElement.class.isAssignableFrom(type) && JaxRsXmlMediaTypes.isXml(mediaType);
    }

    @Override
    public JAXBElement<?> readFrom(Class<JAXBElement<?>> type,
                                   Type genericType,
                                   Annotation[] annotations,
                                   MediaType mediaType,
                                   MultivaluedMap<String, String> httpHeaders,
                                   InputStream entityStream) throws IOException, WebApplicationException {
        JAXBContext jaxbContext = jaxbContext(genericType, mediaType);
        if (jaxbContext != null) {
            try {
                XMLStreamReader reader = JaxRsXmlFactories.xmlStreamReader(entityStream);
                try {
                    return JaxRsXmlFactories.unmarshaller(jaxbContext).unmarshal(reader, declaredType(genericType));
                } finally {
                    reader.close();
                }
            } catch (JAXBException | XMLStreamException e) {
                throw new IOException("Cannot read JAXBElement", e);
            }
        }
        try {
            XMLStreamReader reader = JaxRsXmlFactories.xmlStreamReader(entityStream);
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                        QName name = reader.getName();
                        return new JAXBElement<>(name, String.class, reader.getElementText());
                    }
                }
                throw new IOException("Missing XML root element");
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new IOException("Cannot read JAXBElement", e);
        }
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return JAXBElement.class.isAssignableFrom(type) && JaxRsXmlMediaTypes.isXml(mediaType);
    }

    @Override
    public void writeTo(JAXBElement<?> element,
                        Class<?> type,
                        Type genericType,
                        Annotation[] annotations,
                        MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders,
                        OutputStream entityStream) throws IOException, WebApplicationException {
        JAXBContext jaxbContext = jaxbContext(genericType, mediaType);
        if (jaxbContext != null) {
            try {
                JaxRsXmlFactories.marshaller(jaxbContext).marshal(element, entityStream);
                return;
            } catch (JAXBException e) {
                throw new IOException("Cannot write JAXBElement", e);
            }
        }
        try {
            XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(entityStream);
            try {
                QName name = element.getName();
                String namespace = name.getNamespaceURI();
                String prefix = name.getPrefix();
                if (namespace == null || namespace.isEmpty()) {
                    writer.writeStartElement(name.getLocalPart());
                } else if (prefix == null || prefix.isEmpty()) {
                    writer.writeStartElement(namespace, name.getLocalPart());
                    writer.writeDefaultNamespace(namespace);
                } else {
                    writer.writeStartElement(prefix, name.getLocalPart(), namespace);
                    writer.writeNamespace(prefix, namespace);
                }
                Object value = element.getValue();
                if (value != null) {
                    writer.writeCharacters(value.toString());
                }
                writer.writeEndElement();
                writer.flush();
            } finally {
                writer.close();
            }
        } catch (XMLStreamException e) {
            throw new IOException("Cannot write JAXBElement", e);
        }
    }

    private @Nullable JAXBContext jaxbContext(Type genericType, MediaType mediaType) {
        if (providers == null) {
            return null;
        }
        ContextResolver<JAXBContext> resolver = providers.getContextResolver(JAXBContext.class, mediaType);
        return resolver == null ? null : resolver.getContext(declaredType(genericType));
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> declaredType(Type genericType) {
        if (genericType instanceof ParameterizedType parameterizedType) {
            Type typeArgument = parameterizedType.getActualTypeArguments()[0];
            if (typeArgument instanceof Class<?> type) {
                return (Class<T>) type;
            }
        }
        return (Class<T>) Object.class;
    }
}
