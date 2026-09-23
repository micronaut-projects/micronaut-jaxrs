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
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.UnmarshalException;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;
import org.jspecify.annotations.Nullable;

import javax.xml.namespace.QName;
import javax.xml.transform.stream.StreamSource;
import java.beans.Introspector;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The standard provider of JAXB for the XML media types (JAX-RS 4.2.4): the classes annotated
 * with {@link XmlRootElement} or {@link XmlType}, and {@link JAXBElement}. The {@link JAXBContext}
 * of a class is the one a {@code ContextResolver<JAXBContext>} of the application gives, else one
 * created for the class.
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
public final class JaxRsJaxbMessageBodyReaderWriter implements MessageBodyReader<Object>, MessageBodyWriter<Object> {

    private final BeanProvider<Providers> providers;
    private final Map<Class<?>, JAXBContext> contexts = new ConcurrentHashMap<>();

    JaxRsJaxbMessageBodyReaderWriter(BeanProvider<Providers> providers) {
        this.providers = providers;
    }

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return isJaxb(type) && XmlMediaTypes.isXml(mediaType);
    }

    @Override
    public Object readFrom(Class<Object> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                           MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
        StreamSource entity = new StreamSource(new ByteArrayInputStream(entityStream.readAllBytes()));
        try {
            if (JAXBElement.class.equals(type)) {
                Class<?> declared = typeArgument(genericType);
                return unmarshaller(declared, mediaType).unmarshal(entity, declared);
            }
            if (type.isAnnotationPresent(XmlRootElement.class)) {
                Object value = unmarshaller(type, mediaType).unmarshal(entity);
                return value instanceof JAXBElement<?> element ? element.getValue() : value;
            }
            return unmarshaller(type, mediaType).unmarshal(entity, type).getValue();
        } catch (UnmarshalException e) {
            throw new BadRequestException(e);
        } catch (JAXBException e) {
            throw new InternalServerErrorException(e);
        }
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return isJaxb(type) && XmlMediaTypes.isXml(mediaType);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void writeTo(Object value, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
        try {
            Object root = value;
            Class<?> bound;
            if (value instanceof JAXBElement<?> element) {
                bound = element.getDeclaredType();
            } else {
                bound = value.getClass();
                if (!bound.isAnnotationPresent(XmlRootElement.class)) {
                    // an @XmlType without a root element: the element is named after the class
                    root = new JAXBElement(new QName(Introspector.decapitalize(bound.getSimpleName())), bound, value);
                }
            }
            Marshaller marshaller = context(bound, mediaType).createMarshaller();
            String charset = mediaType == null ? null : mediaType.getParameters().get(MediaType.CHARSET_PARAMETER);
            if (charset != null) {
                marshaller.setProperty(Marshaller.JAXB_ENCODING, charset);
            }
            marshaller.marshal(root, entityStream);
        } catch (JAXBException e) {
            throw new InternalServerErrorException(e);
        }
    }

    private static boolean isJaxb(Class<?> type) {
        return JAXBElement.class.isAssignableFrom(type)
            || type.isAnnotationPresent(XmlRootElement.class)
            || type.isAnnotationPresent(XmlType.class);
    }

    private static Class<?> typeArgument(Type genericType) {
        if (genericType instanceof ParameterizedType parameterized) {
            Type argument = parameterized.getActualTypeArguments()[0];
            if (argument instanceof Class<?> type) {
                return type;
            }
            if (argument instanceof ParameterizedType parameterizedArgument) {
                return (Class<?>) parameterizedArgument.getRawType();
            }
        }
        return Object.class;
    }

    private Unmarshaller unmarshaller(Class<?> type, @Nullable MediaType mediaType) throws JAXBException {
        return context(type, mediaType).createUnmarshaller();
    }

    /**
     * The context of a class: the one a context resolver of the application gives, else one created
     * for the class.
     */
    @SuppressWarnings("unchecked")
    private JAXBContext context(Class<?> type, @Nullable MediaType mediaType) throws JAXBException {
        Providers providers = this.providers.isPresent() ? this.providers.get() : null;
        ContextResolver<JAXBContext> resolver = providers == null ? null : providers.getContextResolver(JAXBContext.class, mediaType);
        if (resolver != null) {
            JAXBContext context = resolver.getContext(type);
            if (context != null) {
                return context;
            }
        }
        JAXBContext context = contexts.get(type);
        if (context == null) {
            context = JAXBContext.newInstance(type);
            contexts.put(type, context);
        }
        return context;
    }
}
