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
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/**
 * Reflection-backed JAXB entity provider for optional XML deployments.
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@JaxRsMessageBodyProvider(
    readerType = Object.class,
    readerTypeVariable = true,
    writerType = Object.class,
    writerTypeVariable = true,
    consumes = { MediaType.APPLICATION_XML, MediaType.TEXT_XML, "application/*+xml" },
    produces = { MediaType.APPLICATION_XML, MediaType.TEXT_XML, "application/*+xml" }
)
@Prototype
@Requires(classes = { JAXBContext.class, XmlRootElement.class, XmlType.class })
@Internal
@Consumes({ MediaType.APPLICATION_XML, MediaType.TEXT_XML, "application/*+xml" })
@Produces({ MediaType.APPLICATION_XML, MediaType.TEXT_XML, "application/*+xml" })
final class JaxRsJaxbMessageBodyReaderWriter implements MessageBodyReader<Object>, MessageBodyWriter<Object> {

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return JaxRsXmlMediaTypes.isXml(mediaType) && isJaxbType(type);
    }

    @Override
    public Object readFrom(Class<Object> type,
                           Type genericType,
                           Annotation[] annotations,
                           MediaType mediaType,
                           MultivaluedMap<String, String> httpHeaders,
                           InputStream entityStream) throws IOException, WebApplicationException {
        try {
            XMLStreamReader reader = JaxRsXmlFactories.xmlStreamReader(entityStream);
            try {
                Object result = JaxRsXmlFactories.unmarshaller(JAXBContext.newInstance(type)).unmarshal(reader);
                return result instanceof JAXBElement<?> element ? element.getValue() : result;
            } finally {
                reader.close();
            }
        } catch (JAXBException | XMLStreamException e) {
            throw new IOException("Cannot read JAXB entity", e);
        }
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return JaxRsXmlMediaTypes.isXml(mediaType) && isJaxbType(type);
    }

    @Override
    public void writeTo(Object entity,
                        Class<?> type,
                        Type genericType,
                        Annotation[] annotations,
                        MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders,
                        OutputStream entityStream) throws IOException, WebApplicationException {
        try {
            JaxRsXmlFactories.marshaller(JAXBContext.newInstance(type)).marshal(entity, entityStream);
        } catch (JAXBException e) {
            throw new IOException("Cannot write JAXB entity", e);
        }
    }

    private static boolean isJaxbType(Class<?> type) {
        return type.getAnnotation(XmlRootElement.class) != null || type.getAnnotation(XmlType.class) != null;
    }
}
