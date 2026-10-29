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
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.qualifiers.MatchArgumentQualifier;
import io.micronaut.jaxrs.common.JaxRsContainerMessageBodyHandlerRegistry;
import io.micronaut.jaxrs.common.JaxRsUtils;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
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
import java.util.List;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    // what is known of the exception mappers and of the context resolvers, by bean definition
    private final Map<BeanDefinition<?>, MapperMetadata> mappers = new ConcurrentHashMap<>();
    private final Map<BeanDefinition<?>, ResolverMetadata> resolvers = new ConcurrentHashMap<>();

    JaxRsProviders(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public <T> @Nullable MessageBodyReader<T> getMessageBodyReader(Class<T> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        // the registered readers, selected like for an entity (JAX-RS 4.2.1)
        MessageBodyReader<T> r = registry().findJaxRsReader(type, genericType, annotations, mediaType);
        if (r == null) {
            return null;
        }
        return new MessageBodyReader<T>() {
            @Override
            public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
                return true;
            }

            @Override
            public T readFrom(Class<T> type, Type ignore1, Annotation[] ignore2, MediaType ignore3, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
                return r.readFrom(type, genericType, annotations, mediaType, httpHeaders, entityStream);
            }
        };
    }

    @Override
    public <T> @Nullable MessageBodyWriter<T> getMessageBodyWriter(Class<T> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        // the registered writers, selected like for an entity (JAX-RS 4.2.2)
        MessageBodyWriter<T> w = registry().findJaxRsWriter(type, genericType, annotations, mediaType);
        if (w == null) {
            return null;
        }
        return new MessageBodyWriter<T>() {
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
        };
    }

    private JaxRsContainerMessageBodyHandlerRegistry registry() {
        return beanContext.getBean(JaxRsContainerMessageBodyHandlerRegistry.class);
    }

    @SuppressWarnings({"unchecked"})
    @Override
    public <T extends Throwable> @Nullable ExceptionMapper<T> getExceptionMapper(Class<T> type) {
        // the mapper of the nearest superclass of the exception, among the registered ones (JAX-RS 4.4)
        JaxRsContainerMessageBodyHandlerRegistry registry = registry();
        ExceptionMapper<T> nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        int nearestPriority = Integer.MAX_VALUE;
        for (BeanRegistration<ExceptionMapper> registration : beanContext.getBeanRegistrations(ExceptionMapper.class,
                MatchArgumentQualifier.contravariant(ExceptionMapper.class, Argument.of(type)))) {
            MapperMetadata metadata = mappers.computeIfAbsent(registration.getBeanDefinition(), MapperMetadata::of);
            if (!registry.isRegistered(registration.getBeanDefinition().getBeanType(), ExceptionMapper.class)
                || !metadata.server()) {
                // not registered, or constrained to the client (@ConstrainedTo)
                continue;
            }
            Class<?> mapped = metadata.mapped();
            int distance = 0;
            for (Class<?> t = type; t != null && t != mapped; t = t.getSuperclass()) {
                distance++;
            }
            // equally near ones by the priority they are registered with, else their @Priority
            int priority = registry.priority(registration, ExceptionMapper.class);
            if (distance < nearestDistance || distance == nearestDistance && priority < nearestPriority) {
                nearest = registration.getBean();
                nearestDistance = distance;
                nearestPriority = priority;
            }
        }
        return nearest;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> @Nullable ContextResolver<T> getContextResolver(Class<T> contextType, MediaType mediaType) {
        // the resolvers of the type whose produced types are compatible with the media type,
        // the most specific first (JAX-RS 4.3): the registered beans, which the instances of the
        // application replace, see JaxRsApplicationSingletons
        JaxRsContainerMessageBodyHandlerRegistry registry = registry();
        List<Candidate<T>> candidates = new ArrayList<>();
        for (BeanRegistration<ContextResolver> registration : beanContext.getBeanRegistrations(ContextResolver.class)) {
            ResolverMetadata metadata = resolvers.computeIfAbsent(registration.getBeanDefinition(), ResolverMetadata::of);
            // a raw resolver, whose context type is not known, may resolve the context type too: its
            // contexts are checked, see below
            Class<?> resolved = metadata.contextType();
            boolean known = resolved != null;
            if (resolved != null && !contextType.isAssignableFrom(resolved)) {
                // the generic type of the resolver is assignable to the context type, e.g. not the
                // one of a resolver of objects for integers
                continue;
            }
            if (!registry.isRegistered(registration.getBeanDefinition().getBeanType(), ContextResolver.class)
                || !metadata.server()) {
                // not registered, or constrained to the client (@ConstrainedTo)
                continue;
            }
            int specificity = specificity(metadata.produces(), mediaType);
            if (specificity >= 0) {
                candidates.add(new Candidate<>((ContextResolver<T>) registration.getBean(), specificity, known));
            }
        }
        if (candidates.isEmpty()) {
            // "null if no matching context providers are found"
            return null;
        }
        candidates.sort(Comparator.comparingInt((Candidate<T> c) -> c.specificity()).reversed());
        List<ContextResolver<T>> resolvers = candidates.stream().map(Candidate::resolver).toList();
        if (resolvers.size() == 1 && candidates.get(0).known()) {
            return resolvers.get(0);
        }
        return type -> {
            // the first context that is not null, of the context type
            for (ContextResolver<T> resolver : resolvers) {
                Object context = resolver.getContext(type);
                if (context != null && (contextType.isPrimitive() || contextType.isInstance(context))) {
                    return (T) context;
                }
            }
            return null;
        };
    }

    /**
     * How specifically a context resolver produces a media type: 2 for the type, 1 for a type with a
     * wildcard subtype, 0 for any type, and -1 if it does not produce it.
     */
    private static int specificity(List<MediaType> produces, MediaType mediaType) {
        if (produces.isEmpty()) {
            return 0;
        }
        int best = -1;
        for (MediaType produced : produces) {
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
     * @param known       Whether the context type of the resolver is known, else its contexts are checked
     * @param <T>         The type of the context
     */
    private record Candidate<T>(ContextResolver<T> resolver, int specificity, boolean known) {
    }

    /**
     * What is known of an exception mapper, read once.
     *
     * @param mapped The type of the exceptions it maps
     * @param server Whether the server uses it, see {@link JaxRsUtils#isServerProvider}
     */
    private record MapperMetadata(Class<?> mapped, boolean server) {
        static MapperMetadata of(BeanDefinition<?> definition) {
            List<Argument<?>> arguments = definition.getTypeArguments(ExceptionMapper.class);
            return new MapperMetadata(arguments.isEmpty() ? Throwable.class : arguments.get(0).getType(),
                JaxRsUtils.isServerProvider(definition.getAnnotationMetadata()));
        }
    }

    /**
     * What is known of a context resolver, read once.
     *
     * @param contextType The type of its contexts, {@code null} for a raw resolver
     * @param produces    The media types of its {@code @Produces}
     * @param server      Whether the server uses it, see {@link JaxRsUtils#isServerProvider}
     */
    private record ResolverMetadata(@Nullable Class<?> contextType, List<MediaType> produces, boolean server) {
        static ResolverMetadata of(BeanDefinition<?> definition) {
            List<Argument<?>> arguments = definition.getTypeArguments(ContextResolver.class);
            List<MediaType> produces = new ArrayList<>();
            for (String value : definition.getAnnotationMetadata().stringValues(Produces.class)) {
                produces.add(MediaType.valueOf(value));
            }
            return new ResolverMetadata(arguments.isEmpty() ? null : arguments.get(0).getType(), List.copyOf(produces),
                JaxRsUtils.isServerProvider(definition.getAnnotationMetadata()));
        }
    }
}
