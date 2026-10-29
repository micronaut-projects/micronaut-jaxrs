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
package io.micronaut.jaxrs.client;

import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import io.micronaut.jaxrs.client.JaxRsConfiguration.Component;
import io.micronaut.jaxrs.client.JaxRsConfiguration.ComponentContract;
import io.micronaut.context.BeanRegistration;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.TypedMessageBodyReader;
import io.micronaut.http.body.TypedMessageBodyWriter;
import io.micronaut.jaxrs.common.JaxRsMessageBodyReader;
import io.micronaut.jaxrs.common.JaxRsMessageBodyReaderDefinition;
import io.micronaut.jaxrs.common.JaxRsMessageBodyWriter;
import io.micronaut.jaxrs.common.JaxRsRouteInterceptors;
import io.micronaut.jaxrs.common.JaxRsUtils;
import io.micronaut.jaxrs.common.body.standard.JaxRsByteArrayMessageBodyReaderWriter;
import io.micronaut.jaxrs.common.body.standard.JaxRsStringMessageBodyReaderWriter;
import io.micronaut.jaxrs.common.JaxRsCurrentProviders;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.ext.Providers;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.WriterInterceptor;

import java.lang.reflect.Type;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
/**
 * The providers of a client configuration (JAX-RS 4): the readers, writers, interceptors, filters
 * and context resolvers found among its components, built lazily, how they are selected, and the
 * context they are injected with. It is the {@link Providers} of the configuration, which is the
 * current one while an entity is read or written, or the filters run.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
final class JaxRsClientProviders implements Providers {

    private final JaxRsConfiguration configuration;
    // the context the providers are injected with: the copies of a configuration share the
    // providers, so these use the configuration of the current invocation, else this one
    private final Providers contextProviders = new CurrentProviders(this);
    private final Configuration contextConfiguration;
    private volatile Tables tables;

    /**
     * @param configuration The configuration
     * @param tables        The providers found among its components so far
     */
    JaxRsClientProviders(JaxRsConfiguration configuration, Tables tables) {
        this.configuration = configuration;
        this.contextConfiguration = new CurrentConfiguration(configuration);
        this.tables = tables;
    }

    /**
     * @return The configuration
     */
    JaxRsConfiguration configuration() {
        return configuration;
    }

    /**
     * @return The providers found so far, which a copy of the configuration starts with
     */
    Tables tables() {
        return tables.copy();
    }

    /**
     * Forget the providers found among the components, when the components change.
     */
    void clear() {
        tables = new Tables();
    }

    List<BeanRegistration<ReaderInterceptor>> getReaderInterceptors() {
        List<BeanRegistration<ReaderInterceptor>> interceptors = tables.readerInterceptors;
        if (interceptors == null) {
            interceptors = registrationsOfType(ReaderInterceptor.class);
            tables.readerInterceptors = interceptors;
        }
        return interceptors;
    }

    List<BeanRegistration<WriterInterceptor>> getWriterInterceptors() {
        List<BeanRegistration<WriterInterceptor>> interceptors = tables.writerInterceptors;
        if (interceptors == null) {
            interceptors = registrationsOfType(WriterInterceptor.class);
            tables.writerInterceptors = interceptors;
        }
        return interceptors;
    }

    private List<JaxRsMessageBodyReaderDefinition> getReaders() {
        List<JaxRsMessageBodyReaderDefinition> found = tables.readers;
        if (found == null) {
            List<JaxRsMessageBodyReaderDefinition> built = new ArrayList<>();
            for (JaxRsConfiguration.Component component : configuration.components()) {
                AnnotationMetadata metadata = component.metadata();
                if (isNotConstrainedToClient(metadata)) {
                    continue;
                }
                MessageBodyReader<?> reader = registeredFor(component, MessageBodyReader.class)
                    ? injected(component.tryGet(MessageBodyReader.class)) : null;
                if (reader != null) {
                    built.add(new JaxRsMessageBodyReaderDefinition(
                        providedType(reader, MessageBodyReader.class),
                        new JaxRsMessageBodyReader<>(reader),
                        priority(component, MessageBodyReader.class),
                        metadata
                    ));
                }
                io.micronaut.http.body.MessageBodyReader<?> micronautReader = registeredFor(component, io.micronaut.http.body.MessageBodyReader.class)
                    ? injected(component.tryGet(io.micronaut.http.body.MessageBodyReader.class)) : null;
                if (micronautReader != null) {
                    Argument<?> type = micronautReader instanceof TypedMessageBodyReader<?> typedMessageBodyReader
                        ? typedMessageBodyReader.getType()
                        : providedType(micronautReader, io.micronaut.http.body.MessageBodyReader.class);
                    built.add(new JaxRsMessageBodyReaderDefinition(
                        type,
                        micronautReader,
                        priority(component, io.micronaut.http.body.MessageBodyReader.class),
                        metadata
                    ));
                }
            }
            // in the order of their priorities (JAX-RS 4.1.3), a stable sort
            built.sort(Comparator.comparingInt(JaxRsMessageBodyReaderDefinition::priority));
            found = Collections.unmodifiableList(built);
            tables.readers = found;
        }
        return found;
    }

    static boolean isNotConstrainedToClient(AnnotationMetadata annotationMetadata) {
        AnnotationValue<ConstrainedTo> constrainedTo = annotationMetadata.getAnnotation(ConstrainedTo.class);
        if (constrainedTo == null) {
            return false;
        }
        Optional<RuntimeType> runtimeType = constrainedTo.enumValue(RuntimeType.class);
        return runtimeType.isPresent() && runtimeType.get() != RuntimeType.CLIENT;
    }

    /**
     * Whether a component is registered for a contract: a registration with contracts registers
     * only those (JAX-RS, {@code Configurable#register(Object, Class[])}).
     */
    static boolean registeredFor(Component component, Class<?> contract) {
        List<ComponentContract> contracts = component.components();
        return contracts.isEmpty() || contracts.stream().anyMatch(c -> c.contract() == contract);
    }

    /**
     * The priority of a component for a contract: the one of the registration, else of the
     * contract, else its {@code @Priority}, else {@link jakarta.ws.rs.Priorities#USER}.
     */
    static int priority(Component component, Class<?> contract) {
        if (component.priority() != JaxRsConfiguration.NO_PRIORITY) {
            return component.priority();
        }
        for (ComponentContract c : component.components()) {
            if (c.contract() == contract && c.priority() != JaxRsConfiguration.NO_PRIORITY) {
                return c.priority();
            }
        }
        return JaxRsUtils.getPriorityOrder(component.metadata());
    }

    private List<JaxRsMessageBodyWriterDefinition> getWriters() {
        List<JaxRsMessageBodyWriterDefinition> found = tables.writers;
        if (found == null) {
            List<JaxRsMessageBodyWriterDefinition> built = new ArrayList<>();
            for (JaxRsConfiguration.Component component : configuration.components()) {
                AnnotationMetadata metadata = component.metadata();
                if (isNotConstrainedToClient(metadata)) {
                    continue;
                }
                MessageBodyWriter<?> writer = registeredFor(component, MessageBodyWriter.class)
                    ? injected(component.tryGet(MessageBodyWriter.class)) : null;
                if (writer != null) {
                    built.add(new JaxRsMessageBodyWriterDefinition(
                        providedType(writer, MessageBodyWriter.class),
                        new JaxRsMessageBodyWriter<>(metadata, (MessageBodyWriter<Object>) writer),
                        priority(component, MessageBodyWriter.class),
                        metadata
                    ));
                }
                io.micronaut.http.body.MessageBodyWriter<?> micronautWriter = registeredFor(component, io.micronaut.http.body.MessageBodyWriter.class)
                    ? injected(component.tryGet(io.micronaut.http.body.MessageBodyWriter.class)) : null;
                if (micronautWriter != null) {
                    Argument<?> type = micronautWriter instanceof TypedMessageBodyWriter<?> typedMessageBodyWriter
                        ? typedMessageBodyWriter.getType()
                        : providedType(micronautWriter, io.micronaut.http.body.MessageBodyWriter.class);
                    built.add(new JaxRsMessageBodyWriterDefinition(
                        type,
                        micronautWriter,
                        priority(component, io.micronaut.http.body.MessageBodyWriter.class),
                        metadata
                    ));
                }
            }
            // in the order of their priorities (JAX-RS 4.1.3), a stable sort
            built.sort(Comparator.comparingInt(JaxRsMessageBodyWriterDefinition::priority));
            found = Collections.unmodifiableList(built);
            tables.writers = found;
        }
        return found;
    }

    /**
     * The type a provider reads or writes: the type argument of its provider interface, known with
     * reflection, else any type.
     */
    private static Argument<?> providedType(Object provider, Class<?> providerType) {
        Argument<?> argument = JaxRsReflection.get().resolveGeneric(provider.getClass(), providerType);
        return argument == null || argument.getTypeParameters().length == 0 ? Argument.OBJECT_ARGUMENT : argument.getTypeParameters()[0];
    }

    /**
     * Get a value with the providers of this configuration as the current ones, e.g. to read
     * with a reader that looks up the context resolvers of the client (JAX-RS 4.3).
     *
     * @param supplier The supplier of the value
     * @param <T>      The type of the value
     * @return The value
     */
    <T> T withProviders(Supplier<T> supplier) {
        Providers previous = JaxRsCurrentProviders.set(this);
        try {
            return supplier.get();
        } finally {
            JaxRsCurrentProviders.restore(previous);
        }
    }

    /**
     * Make the providers of this configuration the current ones until the returned scope is
     * closed, e.g. while the filters run: a filter shared by the copies of a configuration is
     * injected with the configuration of the current invocation (JAX-RS 10.2.8).
     *
     * @return The scope, which restores the previous providers once closed
     */
    CurrentScope current() {
        Providers previous = JaxRsCurrentProviders.set(this);
        return () -> JaxRsCurrentProviders.restore(previous);
    }

    <T> io.micronaut.http.body.@Nullable MessageBodyReader<T> findReader(Argument<T> argument,
                                                                                @Nullable MediaType mediaType) {
        // First, the JAX-RS reader in the order of the readers (JAX-RS 4.2.1)
        io.micronaut.http.body.MessageBodyReader<T> best = null;
        ReaderRank bestRank = null;
        for (JaxRsMessageBodyReaderDefinition readerDer : getReaders()) {
            io.micronaut.http.body.MessageBodyReader<T> reader = (io.micronaut.http.body.MessageBodyReader<T>) readerDer.messageBodyReader();
            if (reader instanceof JaxRsMessageBodyReader<?> jaxRsReader
                && isCompatible(readerDer.metadata(), Consumes.class, mediaType)
                && readerDer.type().isAssignableFrom(argument.getType()) && reader.isReadable(argument, mediaType)) {
                ReaderRank rank = ReaderRank.of(readerDer, jaxRsReader.delegate(), argument.getType(), mediaType);
                if (bestRank == null || rank.compareTo(bestRank) < 0) {
                    best = reader;
                    bestRank = rank;
                }
            }
        }
        if (best != null) {
            return best;
        }
        // Find any kind of reader
        for (JaxRsMessageBodyReaderDefinition readerDer : getReaders()) {
            io.micronaut.http.body.MessageBodyReader<T> reader = (io.micronaut.http.body.MessageBodyReader<T>) readerDer.messageBodyReader();
            // the JAX-RS readers were all considered above
            if (!(reader instanceof JaxRsMessageBodyReader<?>)
                && readerDer.type().isAssignableFrom(argument.getType()) && reader.isReadable(argument, mediaType)) {
                return reader;
            }
        }
        return null;
    }

    <T> io.micronaut.http.body.@Nullable MessageBodyWriter<T> findWriter(Argument<T> argument,
                                                                                MediaType mediaType) {
        // First, the JAX-RS writer in the order of the providers, then of their priority
        io.micronaut.http.body.MessageBodyWriter<T> best = null;
        long bestRank = Long.MAX_VALUE;
        for (JaxRsMessageBodyWriterDefinition writerDef : getWriters()) {
            io.micronaut.http.body.MessageBodyWriter<T> writer = (io.micronaut.http.body.MessageBodyWriter<T>) writerDef.messageBodyWriter();
            if (writer instanceof JaxRsMessageBodyWriter<?> jaxRsWriter
                && isCompatible(writerDef.metadata(), Produces.class, mediaType)
                && writerDef.type().isAssignableFrom(argument.getType()) && writer.isWriteable(argument, mediaType)) {
                long rank = rank(writerDef.type().getType(), jaxRsWriter.delegate(), writerDef.metadata(), Produces.class, argument.getType(), mediaType);
                if (rank < bestRank) {
                    best = writer;
                    bestRank = rank;
                }
            }
        }
        if (best != null) {
            return best;
        }
        // Find any kind of writer
        for (JaxRsMessageBodyWriterDefinition writerDef : getWriters()) {
            io.micronaut.http.body.MessageBodyWriter<T> writer = (io.micronaut.http.body.MessageBodyWriter<T>) writerDef.messageBodyWriter();
            // the JAX-RS writers were all considered above
            if (!(writer instanceof JaxRsMessageBodyWriter<?>)
                && writerDef.type().isAssignableFrom(argument.getType()) && writer.isWriteable(argument, mediaType)) {
                return writer;
            }
        }
        return null;
    }

    /**
     * Whether a JAX-RS provider reads or writes a media type: the types of its {@code @Consumes}
     * or {@code @Produces}, any type without one, are compatible with it (JAX-RS 4.2.1, 4.2.2).
     */
    private static boolean isCompatible(AnnotationMetadata metadata, Class<? extends Annotation> declaring, @Nullable MediaType mediaType) {
        String[] declared = metadata.stringValues(declaring);
        if (mediaType == null || declared.length == 0) {
            return true;
        }
        for (String value : declared) {
            for (String type : value.split(",")) {
                MediaType candidate = new MediaType(type.trim());
                if (JaxRsUtils.isCompatible(candidate, mediaType)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The order of a JAX-RS provider, like the one of the server (JAX-RS 4.2.2, step 4): the nearest
     * type first, then the most specific media type, then the providers of the application before
     * the standard ones. Equal ones keep the order of their priority.
     */
    private static long rank(Class<?> provided, Object provider, AnnotationMetadata metadata, Class<? extends Annotation> declaring,
                             Class<?> type, @Nullable MediaType mediaType) {
        String[] declared = metadata.stringValues(declaring);
        int distance = JaxRsUtils.typeDistance(provided, type);
        int specificity = mediaType == null ? 0 : JaxRsUtils.mediaTypeSpecificity(declared, List.of(mediaType));
        int standard = provider.getClass().getName().startsWith("io.micronaut.") ? 1 : 0;
        return ((long) distance * 3 + (2 - specificity)) * 2 + standard;
    }

    List<ClientRequestFilter> getRequestFilters() {
        List<ClientRequestFilter> filters = tables.requestFilters;
        if (filters == null) {
            filters = getComponentOfType(ClientRequestFilter.class);
            tables.requestFilters = filters;
        }
        return filters;
    }

    List<ClientResponseFilter> getResponseFilters() {
        List<ClientResponseFilter> filters = tables.responseFilters;
        if (filters == null) {
            // in the reverse order of their priorities (JAX-RS 6.6)
            filters = new ArrayList<>(getComponentOfType(ClientResponseFilter.class));
            Collections.reverse(filters);
            filters = Collections.unmodifiableList(filters);
            tables.responseFilters = filters;
        }
        return filters;
    }

    /**
     * @param type The type of the providers
     * @param <T>  The type
     * @return The registered providers of a type, in the order of their priorities
     */
    <T> List<T> getProviders(Class<T> type) {
        return getComponentOfType(type);
    }

    private <T> List<T> getComponentOfType(Class<T> type) {
        return registrationsOfType(type).stream().map(BeanRegistration::getBean).toList();
    }

    /**
     * @return The registered providers of a type with their priorities, in the order of the priorities
     */
    private <T> List<BeanRegistration<T>> registrationsOfType(Class<T> type) {
        var registrations = new ArrayList<BeanRegistration<T>>();
        for (JaxRsConfiguration.Component component : configuration.components()) {
            if (!registeredFor(component, type) || isNotConstrainedToClient(component.metadata())) {
                // registered for other contracts only, or for another runtime (@ConstrainedTo)
                continue;
            }
            T instance = injected(component.tryGet(type));
            if (instance != null) {
                registrations.add(JaxRsRouteInterceptors.registration(instance, component.metadata(), priority(component, type)));
            }
        }
        registrations.sort(Comparator.comparingInt(BeanRegistration::getOrder));
        return registrations;
    }

    /**
     * Inject the {@code @Context} fields of a provider a client supports (JAX-RS 9.2): its
     * {@link Providers} and {@link Configuration}. The other context types are the ones of a
     * server request.
     */
    <T> @Nullable T injected(@Nullable T instance) {
        if (instance == null) {
            return null;
        }
        JaxRsReflection.get().injectFields(instance, Context.class, type -> type == Providers.class ? contextProviders
            : type == Configuration.class ? contextConfiguration
            : null);
        return instance;
    }

    /**
     * The configuration of the entity read or written on the current thread, else a default one.
     */
    private static JaxRsConfiguration current(JaxRsConfiguration fallback) {
        return JaxRsCurrentProviders.current() instanceof JaxRsClientProviders clientProviders ? clientProviders.configuration() : fallback;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> @Nullable MessageBodyReader<T> getMessageBodyReader(Class<T> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
        // selected like the readers of the entities (JAX-RS 4.2.1)
        MediaType micronautMediaType = JaxRsUtils.convert(mediaType);
        MessageBodyReader<T> best = null;
        ReaderRank bestRank = null;
        for (JaxRsMessageBodyReaderDefinition definition : getReaders()) {
            if (definition.messageBodyReader() instanceof JaxRsMessageBodyReader<?> jaxRsReader
                && isCompatible(definition.metadata(), Consumes.class, micronautMediaType)
                && definition.type().isAssignableFrom(type)
                && jaxRsReader.delegate().isReadable(type, genericType, annotations, mediaType)) {
                ReaderRank rank = ReaderRank.of(definition, jaxRsReader.delegate(), type, micronautMediaType);
                if (bestRank == null || rank.compareTo(bestRank) < 0) {
                    best = (MessageBodyReader<T>) jaxRsReader.delegate();
                    bestRank = rank;
                }
            }
        }
        return best != null ? best : (MessageBodyReader<T>) standardProvider(type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> @Nullable MessageBodyWriter<T> getMessageBodyWriter(Class<T> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
        // selected like the writers of the entities (JAX-RS 4.2.2)
        MediaType micronautMediaType = JaxRsUtils.convert(mediaType);
        MessageBodyWriter<T> best = null;
        long bestRank = Long.MAX_VALUE;
        for (JaxRsMessageBodyWriterDefinition definition : getWriters()) {
            if (definition.messageBodyWriter() instanceof JaxRsMessageBodyWriter<?> jaxRsWriter
                && isCompatible(definition.metadata(), Produces.class, micronautMediaType)
                && definition.type().isAssignableFrom(type)
                && jaxRsWriter.delegate().isWriteable(type, genericType, annotations, mediaType)) {
                long rank = rank(definition.type().getType(), jaxRsWriter.delegate(), definition.metadata(), Produces.class, type, micronautMediaType);
                if (rank < bestRank) {
                    best = (MessageBodyWriter<T>) jaxRsWriter.delegate();
                    bestRank = rank;
                }
            }
        }
        return best != null ? best : (MessageBodyWriter<T>) standardProvider(type);
    }

    /**
     * The standard JAX-RS provider of {@code String} and {@code byte[]} (JAX-RS 4.2.4): the
     * client reads and writes them with the handlers of the HTTP client, which are not JAX-RS
     * providers, so these are returned for the ones that ask for the provider of these types.
     */
    private static @Nullable Object standardProvider(Class<?> type) {
        if (type == String.class) {
            return new JaxRsStringMessageBodyReaderWriter();
        }
        if (type == byte[].class) {
            return new JaxRsByteArrayMessageBodyReaderWriter();
        }
        return null;
    }

    @Override
    public <T extends Throwable> @Nullable ExceptionMapper<T> getExceptionMapper(Class<T> type) {
        // exception mappers are providers of the server
        return null;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> @Nullable ContextResolver<T> getContextResolver(Class<T> contextType, jakarta.ws.rs.core.MediaType mediaType) {
        // the resolvers of the context type, by their type argument, whose produced types are
        // compatible with the media type, the most specific first, then by priority (JAX-RS 4.3):
        // a resolver is asked for the context of an entity type, and may have none for it
        MediaType micronautMediaType = mediaType == null ? null : JaxRsUtils.convert(mediaType);
        List<BeanRegistration<ContextResolver>> candidates = new ArrayList<>();
        Set<BeanRegistration<ContextResolver>> untyped = new HashSet<>();
        for (BeanRegistration<ContextResolver> registration : registrationsOfType(ContextResolver.class)) {
            ContextResolver<?> resolver = registration.getBean();
            Argument<?> resolved = JaxRsReflection.get().resolveGeneric(resolver.getClass(), ContextResolver.class);
            Argument<?>[] arguments = resolved == null ? new Argument<?>[0] : resolved.getTypeParameters();
            if ((arguments.length == 0 || contextType.isAssignableFrom(arguments[0].getType()))
                && isCompatible(registration.getBeanDefinition().getAnnotationMetadata(), Produces.class, micronautMediaType)) {
                candidates.add(registration);
                if (arguments.length == 0) {
                    untyped.add(registration);
                }
            }
        }
        // a stable sort: equal ones keep the order of their priorities
        candidates.sort(Comparator.comparingInt((BeanRegistration<ContextResolver> r) -> micronautMediaType == null ? 0
            : JaxRsUtils.mediaTypeSpecificity(r.getBeanDefinition().getAnnotationMetadata().stringValues(Produces.class), List.of(micronautMediaType))).reversed());
        List<ContextResolver<T>> resolvers = candidates.stream()
            .map(r -> untyped.contains(r) ? ofType(contextType, (ContextResolver<?>) r.getBean()) : (ContextResolver<T>) r.getBean())
            .toList();
        if (resolvers.isEmpty()) {
            return null;
        }
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
     * A resolver whose context type is not known, e.g. a raw one or one whose generic
     * hierarchy was not recorded: its contexts of another type are none.
     */
    private static <T> ContextResolver<T> ofType(Class<T> contextType, ContextResolver<?> resolver) {
        return type -> {
            Object context = resolver.getContext(type);
            return contextType.isInstance(context) ? contextType.cast(context) : null;
        };
    }

    /**
     * The {@link Providers} a provider is injected with: the ones of the configuration of the
     * current invocation, else of the configuration that injected it.
     *
     * @param fallback The providers of the configuration that injects it
     */
    private record CurrentProviders(Providers fallback) implements Providers {

        private Providers delegate() {
            return JaxRsCurrentProviders.current() instanceof JaxRsClientProviders clientProviders ? clientProviders : fallback;
        }

        @Override
        public <T> @Nullable MessageBodyReader<T> getMessageBodyReader(Class<T> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
            return delegate().getMessageBodyReader(type, genericType, annotations, mediaType);
        }

        @Override
        public <T> @Nullable MessageBodyWriter<T> getMessageBodyWriter(Class<T> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
            return delegate().getMessageBodyWriter(type, genericType, annotations, mediaType);
        }

        @Override
        public <T extends Throwable> @Nullable ExceptionMapper<T> getExceptionMapper(Class<T> type) {
            return delegate().getExceptionMapper(type);
        }

        @Override
        public <T> @Nullable ContextResolver<T> getContextResolver(Class<T> contextType, jakarta.ws.rs.core.MediaType mediaType) {
            return delegate().getContextResolver(contextType, mediaType);
        }
    }

    /**
     * The {@link Configuration} a provider is injected with: the configuration of the current
     * invocation, else the one that injected it.
     *
     * @param fallback The configuration that injects it
     */
    private record CurrentConfiguration(JaxRsConfiguration fallback) implements Configuration {

        @Override
        public RuntimeType getRuntimeType() {
            return RuntimeType.CLIENT;
        }

        @Override
        public Map<String, Object> getProperties() {
            return current(fallback).getProperties();
        }

        @Override
        public @Nullable Object getProperty(String name) {
            return current(fallback).getProperty(name);
        }

        @Override
        public Collection<String> getPropertyNames() {
            return current(fallback).getPropertyNames();
        }

        @Override
        public boolean isEnabled(Feature feature) {
            return current(fallback).isEnabled(feature);
        }

        @Override
        public boolean isEnabled(Class<? extends Feature> featureClass) {
            return current(fallback).isEnabled(featureClass);
        }

        @Override
        public boolean isRegistered(Object component) {
            return current(fallback).isRegistered(component);
        }

        @Override
        public boolean isRegistered(Class<?> componentClass) {
            return current(fallback).isRegistered(componentClass);
        }

        @Override
        public Map<Class<?>, Integer> getContracts(Class<?> componentClass) {
            return current(fallback).getContracts(componentClass);
        }

        @Override
        public Set<Class<?>> getClasses() {
            return current(fallback).getClasses();
        }

        @Override
        public Set<Object> getInstances() {
            return current(fallback).getInstances();
        }

        @Override
        public String toString() {
            return current(fallback).toString();
        }
    }

    /**
     * The order of a JAX-RS reader (JAX-RS 4.2.1): the providers of the application before the
     * standard ones (4.1.3), e.g. a reader of any media type before the standard one of JSON, then
     * the most specific media type, then the highest priority, then, which it leaves open, the
     * nearest type.
     *
     * @param specificity The specificity of the media type the reader consumes, 2 for a concrete one
     * @param standard    1 for a standard provider, else 0
     * @param priority    The priority of the reader
     * @param distance    The distance of the type the reader reads to the type of the entity
     */
    private record ReaderRank(int specificity, int standard, int priority, int distance) implements Comparable<ReaderRank> {

        static ReaderRank of(JaxRsMessageBodyReaderDefinition definition, Object reader, Class<?> type, @Nullable MediaType mediaType) {
            String[] declared = definition.metadata().stringValues(Consumes.class);
            return new ReaderRank(
                mediaType == null ? 0 : JaxRsUtils.mediaTypeSpecificity(declared, List.of(mediaType)),
                reader.getClass().getName().startsWith("io.micronaut.") ? 1 : 0,
                definition.priority(),
                JaxRsUtils.typeDistance(definition.type().getType(), type)
            );
        }

        @Override
        public int compareTo(ReaderRank other) {
            int result = Integer.compare(standard, other.standard);
            if (result == 0) {
                result = Integer.compare(other.specificity, specificity);
            }
            if (result == 0) {
                result = Integer.compare(priority, other.priority);
            }
            return result == 0 ? Integer.compare(distance, other.distance) : result;
        }
    }

    /**
     * The scope of the current providers, see {@link #current()}.
     */
    interface CurrentScope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * The providers found among the components of a configuration: built fully, then published
     * unmodifiable.
     */
    static final class Tables {
        private volatile @Nullable List<BeanRegistration<ReaderInterceptor>> readerInterceptors;
        private volatile @Nullable List<BeanRegistration<WriterInterceptor>> writerInterceptors;
        private volatile @Nullable List<JaxRsMessageBodyReaderDefinition> readers;
        private volatile @Nullable List<JaxRsMessageBodyWriterDefinition> writers;
        private volatile @Nullable List<ClientRequestFilter> requestFilters;
        private volatile @Nullable List<ClientResponseFilter> responseFilters;

        private Tables copy() {
            Tables copy = new Tables();
            copy.readerInterceptors = readerInterceptors;
            copy.writerInterceptors = writerInterceptors;
            copy.readers = readers;
            copy.writers = writers;
            copy.requestFilters = requestFilters;
            copy.responseFilters = responseFilters;
            return copy;
        }
    }
}
