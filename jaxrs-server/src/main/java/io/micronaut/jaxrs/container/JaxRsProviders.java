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
package io.micronaut.jaxrs.container;

import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanRegistration;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.qualifiers.MatchArgumentQualifier;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.RuntimeType;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The JAX-RS {@link Providers}.
 *
 * @author Jonas Konrad
 * @since 4.6.0
 */
@Internal
@Singleton
final class JaxRsProviders implements Providers {
    private final BeanContext beanContext;

    JaxRsProviders(BeanContext beanContext) {
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

    @SuppressWarnings({"unchecked"})
    @Override
    public <T extends Throwable> ExceptionMapper<T> getExceptionMapper(Class<T> type) {
        return beanContext.getBeansOfType(ExceptionMapper.class,
                MatchArgumentQualifier.contravariant(ExceptionMapper.class, Argument.of(type)))
            .stream()
            .findFirst()
            .orElse(null);
    }

    @Override
    public <T> ContextResolver<T> getContextResolver(Class<T> contextType, MediaType mediaType) {
        MediaType requestedMediaType = mediaType == null ? MediaType.WILDCARD_TYPE : mediaType;
        List<BeanRegistration<ContextResolver<T>>> registrations = contextResolverRegistrations(contextType);
        registrations.sort(Comparator
            .<BeanRegistration<ContextResolver<T>>>comparingInt(registration -> mediaTypeScore(registration.getBeanDefinition().getAnnotationMetadata(), requestedMediaType))
            .reversed()
            .thenComparingInt(BeanRegistration::getOrder));
        return registrations.stream()
            .filter(registration -> mediaTypeScore(registration.getBeanDefinition().getAnnotationMetadata(), requestedMediaType) >= 0)
            .filter(registration -> isServerProvider(registration.getBeanDefinition().getAnnotationMetadata()))
            .findFirst()
            .map(BeanRegistration::getBean)
            .orElse(null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> List<BeanRegistration<ContextResolver<T>>> contextResolverRegistrations(Class<T> contextType) {
        Collection registrations = beanContext.getBeanRegistrations(
            Argument.of(ContextResolver.class),
            MatchArgumentQualifier.covariant(ContextResolver.class, Argument.of(contextType))
        );
        return new ArrayList<>(registrations);
    }

    private static boolean isServerProvider(AnnotationMetadata annotationMetadata) {
        Optional<RuntimeType> runtimeType = annotationMetadata.enumValue(ConstrainedTo.class, RuntimeType.class);
        return runtimeType.isEmpty() || runtimeType.get() == RuntimeType.SERVER;
    }

    private static int mediaTypeScore(AnnotationMetadata annotationMetadata, MediaType requestedMediaType) {
        String[] producedMediaTypes = annotationMetadata.stringValues(Produces.class);
        if (producedMediaTypes.length == 0) {
            producedMediaTypes = annotationMetadata.stringValues(io.micronaut.http.annotation.Produces.class);
        }
        if (producedMediaTypes.length == 0) {
            return mediaTypeScore(MediaType.WILDCARD_TYPE, requestedMediaType);
        }
        int score = -1;
        for (String producedMediaType : producedMediaTypes) {
            score = Math.max(score, mediaTypeScore(MediaType.valueOf(producedMediaType), requestedMediaType));
        }
        return score;
    }

    private static int mediaTypeScore(MediaType producedMediaType, MediaType requestedMediaType) {
        if (!producedMediaType.isCompatible(requestedMediaType)) {
            return -1;
        }
        if (requestedMediaType.isWildcardType()) {
            return producedMediaType.isWildcardType() ? 2 : 1;
        }
        if (producedMediaType.isWildcardType()) {
            return 0;
        }
        if (producedMediaType.isWildcardSubtype()) {
            return 1;
        }
        return 2;
    }
}
