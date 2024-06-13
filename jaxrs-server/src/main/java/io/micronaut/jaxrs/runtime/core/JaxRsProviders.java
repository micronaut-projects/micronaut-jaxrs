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
package io.micronaut.jaxrs.runtime.core;

import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import jakarta.inject.Singleton;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * The JAX-RS {@link Providers}.
 *
 * @author Jonas Konrad
 * @since 4.6.0
 */
@Internal
@Singleton
public final class JaxRsProviders implements Providers {
    private final BeanContext beanContext;

    public JaxRsProviders(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public <T> MessageBodyReader<T> getMessageBodyReader(Class<T> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        Collection<MessageBodyReader> messageBodyReaders = beanContext.getBeansOfType(Argument.of(MessageBodyReader.class, Argument.of(type)));
        return messageBodyReaders.stream()
            .filter(r -> r.isReadable(type, genericType, annotations, mediaType))
            .findFirst()
            .map(r -> new MessageBodyReader<T>() {
                @Override
                public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
                    return true;
                }

                @Override
                public T readFrom(Class<T> type, Type ignore1, Annotation[] ignore2, MediaType ignore3, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
                    return (T) r.readFrom(type, genericType, annotations, mediaType, httpHeaders, entityStream);
                }
            })
            .orElse(null);
    }

    @Override
    public <T> MessageBodyWriter<T> getMessageBodyWriter(Class<T> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        Collection<MessageBodyWriter> messageBodyWriters = beanContext.getBeansOfType(Argument.of(MessageBodyWriter.class, Argument.of(type)));
        return messageBodyWriters.stream()
            .filter(w -> w.isWriteable(type, genericType, annotations, mediaType))
            .findFirst()
            .map(w -> new MessageBodyWriter<T>() {
                @Override
                public boolean isWriteable(Class<?> ignore1, Type ignore2, Annotation[] ignore3, MediaType ignore4) {
                    return true;
                }

                @Override
                public long getSize(T t, Class<?> ignore1, Type ignore2, Annotation[] ignore3, MediaType ignore4) {
                    return w.getSize(t, type, genericType, annotations, mediaType);
                }

                @Override
                public void writeTo(T t, Class<?> ignore1, Type ignore2, Annotation[] ignore3, MediaType ignore4, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
                    w.writeTo(t, type, genericType, annotations, mediaType, httpHeaders, entityStream);
                }
            })
            .orElse(null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public <T extends Throwable> ExceptionMapper<T> getExceptionMapper(Class<T> type) {
        Iterator<BeanDefinition<ExceptionMapper>> iterator = beanContext.getBeanDefinitions(ExceptionMapper.class).stream()
            .filter(d -> {
                Argument<?> t = getExceptionType(d);
                return t == null || t.isAssignableFrom(type);
            })
            .iterator();
        if (!iterator.hasNext()) {
            return null;
        }
        BeanDefinition<ExceptionMapper> mostSpecificExceptionMapper = iterator.next();
        Argument<?> mostSpecificTypeVariable = getExceptionType(mostSpecificExceptionMapper);
        while (iterator.hasNext()) {
            BeanDefinition<ExceptionMapper> mapper = iterator.next();
            Argument<?> typeVar = getExceptionType(mapper);
            if (typeVar == null) {
                continue;
            }
            if (mostSpecificTypeVariable == null || mostSpecificTypeVariable.isAssignableFrom(typeVar)) {
                mostSpecificExceptionMapper = mapper;
                mostSpecificTypeVariable = typeVar;
            }
        }
        return beanContext.getBean(mostSpecificExceptionMapper);
    }

    private Argument<?> getExceptionType(BeanDefinition<ExceptionMapper> definition) {
        List<Argument<?>> args = definition.getTypeArguments(ExceptionMapper.class);
        return args.isEmpty() ? null : args.get(0);
    }

    @Override
    public <T> ContextResolver<T> getContextResolver(Class<T> contextType, MediaType mediaType) {
        // "null if no matching context providers are found"
        return null;
    }
}
