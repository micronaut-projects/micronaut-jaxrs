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
package io.micronaut.jaxrs.providers;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/**
 * The standard provider of {@link Source} for the XML media types (JAX-RS 4.2.4): a
 * {@link StreamSource}, {@link SAXSource} or {@link DOMSource}, read from the bytes of the entity.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@Singleton
@Internal
// application/* for application/*+xml, which isReadable and isWriteable check
@Consumes({MediaType.TEXT_XML, MediaType.APPLICATION_XML, "application/*"})
@Produces({MediaType.TEXT_XML, MediaType.APPLICATION_XML, "application/*"})
public final class JaxRsSourceMessageBodyReaderWriter implements MessageBodyReader<Source>, MessageBodyWriter<Source> {

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return (type == Source.class || type == StreamSource.class || type == SAXSource.class || type == DOMSource.class)
            && XmlMediaTypes.isXml(mediaType);
    }

    @Override
    public Source readFrom(Class<Source> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                           MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
        // the entity outlives the stream the runtime closes after reading
        InputStream entity = new ByteArrayInputStream(entityStream.readAllBytes());
        Class<?> requested = type;
        if (requested == DOMSource.class) {
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                return new DOMSource(factory.newDocumentBuilder().parse(entity));
            } catch (SAXException e) {
                throw new BadRequestException(e);
            } catch (ParserConfigurationException e) {
                throw new InternalServerErrorException(e);
            }
        }
        if (requested == SAXSource.class) {
            return new SAXSource(new InputSource(entity));
        }
        return new StreamSource(entity);
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return Source.class.isAssignableFrom(type) && XmlMediaTypes.isXml(mediaType);
    }

    @Override
    public void writeTo(Source source, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.newTransformer().transform(source, new StreamResult(entityStream));
        } catch (TransformerException e) {
            throw new IOException("Failed to write the XML source", e);
        }
    }
}
