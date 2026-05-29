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

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/**
 * The read/write body for {@link Source}.
 *
 * @author Denis Stepanov
 * @since 4.9
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@JaxRsMessageBodyProvider(
    readerType = Source.class,
    writerType = Source.class,
    consumes = { MediaType.APPLICATION_XML, MediaType.TEXT_XML, "application/*+xml" },
    produces = { MediaType.APPLICATION_XML, MediaType.TEXT_XML, "application/*+xml" }
)
@Prototype
@Requires(classes = Source.class)
@Internal
@Consumes({ MediaType.APPLICATION_XML, MediaType.TEXT_XML, "application/*+xml" })
@Produces({ MediaType.APPLICATION_XML, MediaType.TEXT_XML, "application/*+xml" })
public final class JaxRsSourceMessageBodyReaderWriter implements MessageBodyReader<Source>, MessageBodyWriter<Source> {

    /**
     * Default constructor.
     */
    public JaxRsSourceMessageBodyReaderWriter() {
    }

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return Source.class.isAssignableFrom(type) && JaxRsXmlMediaTypes.isXml(mediaType);
    }

    @Override
    public Source readFrom(Class<Source> type,
                           Type genericType,
                           Annotation[] annotations,
                           MediaType mediaType,
                           MultivaluedMap<String, String> httpHeaders,
                           InputStream entityStream) throws IOException, WebApplicationException {
        return new StreamSource(new ByteArrayInputStream(JaxRsXmlFactories.validatedXmlBytes(entityStream)));
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return Source.class.isAssignableFrom(type) && JaxRsXmlMediaTypes.isXml(mediaType);
    }

    @Override
    public void writeTo(Source source,
                        Class<?> type,
                        Type genericType,
                        Annotation[] annotations,
                        MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders,
                        OutputStream entityStream) throws IOException, WebApplicationException {
        try {
            Transformer transformer = JaxRsXmlFactories.transformerFactory().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.transform(source, new StreamResult(entityStream));
        } catch (TransformerException e) {
            throw new IOException("Cannot write Source", e);
        }
    }
}
