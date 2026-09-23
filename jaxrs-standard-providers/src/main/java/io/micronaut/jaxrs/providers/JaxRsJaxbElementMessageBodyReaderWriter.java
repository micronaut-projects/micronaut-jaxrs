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

import io.micronaut.context.BeanProvider;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;
import jakarta.xml.bind.JAXBElement;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/**
 * The standard provider of JAXB for {@link JAXBElement}: typed, so that it is sorted with the
 * providers of the application of the same type by its XML media types (JAX-RS 4.2.2, step 4),
 * see {@link JaxRsJaxbMessageBodyReaderWriter}.
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
public final class JaxRsJaxbElementMessageBodyReaderWriter implements MessageBodyReader<JAXBElement<?>>, MessageBodyWriter<JAXBElement<?>> {

    private final JaxRsJaxbMessageBodyReaderWriter jaxb;

    @Inject
    JaxRsJaxbElementMessageBodyReaderWriter(BeanProvider<Providers> providers) {
        this.jaxb = new JaxRsJaxbMessageBodyReaderWriter(providers);
    }

    /**
     * A provider without the context resolvers of an application, for the client.
     */
    JaxRsJaxbElementMessageBodyReaderWriter() {
        this.jaxb = new JaxRsJaxbMessageBodyReaderWriter();
    }

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return JAXBElement.class.isAssignableFrom(type) && jaxb.isReadable(type, genericType, annotations, mediaType);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public JAXBElement<?> readFrom(Class<JAXBElement<?>> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                                   MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
        return (JAXBElement<?>) jaxb.readFrom((Class) type, genericType, annotations, mediaType, httpHeaders, entityStream);
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return JAXBElement.class.isAssignableFrom(type) && jaxb.isWriteable(type, genericType, annotations, mediaType);
    }

    @Override
    public void writeTo(JAXBElement<?> element, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
        jaxb.writeTo(element, type, genericType, annotations, mediaType, httpHeaders, entityStream);
    }
}
