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

import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanRegistration;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.qualifiers.MatchArgumentQualifier;
import io.micronaut.jaxrs.common.JaxRsContainerMessageBodyHandlerRegistry;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Comparator;
import java.util.ArrayList;

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
    public <T> @Nullable MessageBodyReader<T> getMessageBodyReader(Class<T> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
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
    public <T> @Nullable MessageBodyWriter<T> getMessageBodyWriter(Class<T> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
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
    public <T extends Throwable> @Nullable ExceptionMapper<T> getExceptionMapper(Class<T> type) {
        // the mapper of the nearest superclass of the exception, among the registered ones (JAX-RS 4.4)
        JaxRsContainerMessageBodyHandlerRegistry registry = beanContext.getBean(JaxRsContainerMessageBodyHandlerRegistry.class);
        ExceptionMapper<T> nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (BeanRegistration<ExceptionMapper> registration : beanContext.getBeanRegistrations(ExceptionMapper.class,
                MatchArgumentQualifier.contravariant(ExceptionMapper.class, Argument.of(type)))) {
            if (!registry.isRegistered(registration.getBeanDefinition().getBeanType())) {
                continue;
            }
            List<Argument<?>> arguments = registration.getBeanDefinition().getTypeArguments(ExceptionMapper.class);
            Class<?> mapped = arguments.isEmpty() ? Throwable.class : arguments.get(0).getType();
            int distance = 0;
            for (Class<?> t = type; t != null && t != mapped; t = t.getSuperclass()) {
                distance++;
            }
            if (distance < nearestDistance) {
                nearest = registration.getBean();
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> @Nullable ContextResolver<T> getContextResolver(Class<T> contextType, MediaType mediaType) {
        // the resolvers of the type whose produced types are compatible with the media type,
        // the most specific first (JAX-RS 4.3): the beans, and the instances of the application
        List<Candidate<T>> candidates = new ArrayList<>();
        for (BeanRegistration<ContextResolver> registration : beanContext.getBeanRegistrations(ContextResolver.class,
                MatchArgumentQualifier.covariant(ContextResolver.class, Argument.of(contextType)))) {
            int specificity = specificity(registration.getBeanDefinition().getAnnotationMetadata().stringValues(Produces.class), mediaType);
            if (specificity >= 0) {
                candidates.add(new Candidate<>((ContextResolver<T>) registration.getBean(), specificity));
            }
        }
        Application application = beanContext.findBean(Application.class).orElse(null);
        if (application != null) {
            for (Object singleton : application.getSingletons()) {
                if (singleton instanceof ContextResolver resolver && resolves(resolver, contextType)) {
                    int specificity = specificity(JaxRsReflection.get().annotationMetadata(singleton.getClass()).stringValues(Produces.class), mediaType);
                    if (specificity >= 0) {
                        candidates.add(new Candidate<>((ContextResolver<T>) resolver, specificity));
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            // "null if no matching context providers are found"
            return null;
        }
        candidates.sort(Comparator.comparingInt((Candidate<T> c) -> c.specificity()).reversed());
        List<ContextResolver<T>> resolvers = candidates.stream().map(Candidate::resolver).toList();
        if (resolvers.size() == 1) {
            return resolvers.get(0);
        }
        return type -> {
            // the first context that is not null
            for (ContextResolver<T> resolver : resolvers) {
                T context = resolver.getContext(type);
                if (context != null) {
                    return context;
                }
            }
            return null;
        };
    }

    /**
     * Whether a context resolver instance resolves contexts of a type: its type argument is the
     * type or a subtype, or it is not known.
     */
    private static boolean resolves(ContextResolver<?> resolver, Class<?> contextType) {
        Argument<?> argument = JaxRsReflection.get().resolveGeneric(resolver.getClass(), ContextResolver.class);
        Argument<?>[] arguments = argument == null ? new Argument<?>[0] : argument.getTypeParameters();
        return arguments.length == 0 || contextType.isAssignableFrom(arguments[0].getType());
    }

    /**
     * How specifically a context resolver produces a media type: 2 for the type, 1 for a type with a
     * wildcard subtype, 0 for any type, and -1 if it does not produce it.
     */
    private static int specificity(String[] produces, MediaType mediaType) {
        if (produces.length == 0) {
            return 0;
        }
        int best = -1;
        for (String value : produces) {
            MediaType produced = MediaType.valueOf(value);
            if (produced.isCompatible(mediaType)) {
                int specificity = produced.isWildcardType() ? 0 : produced.isWildcardSubtype() ? 1 : 2;
                best = Math.max(best, specificity);
            }
        }
        return best;
    }

    /**
     * A context resolver, and how specifically it produces the media type.
     *
     * @param resolver    The resolver
     * @param specificity The specificity
     * @param <T>         The type of the context
     */
    private record Candidate<T>(ContextResolver<T> resolver, int specificity) {
    }
}
