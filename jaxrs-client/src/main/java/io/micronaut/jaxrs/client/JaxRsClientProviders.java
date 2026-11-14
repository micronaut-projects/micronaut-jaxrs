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
import io.micronaut.jaxrs.common.JaxRsMessageBodyWriter;
import io.micronaut.jaxrs.common.JaxRsRouteInterceptors;
import io.micronaut.jaxrs.common.JaxRsProviderSelection;
import io.micronaut.jaxrs.common.JaxRsProviderSelection.Rank;
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
import java.util.function.BiFunction;
import java.util.function.Function;
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
    // the providers found among the components of the configuration: replaced, not changed, when
    // the components change
    private volatile @Nullable Tables tables;
    // set on the thread that builds the tables: a provider instantiated meanwhile, e.g. one that
    // looks up a context resolver in its constructor (JAX-RS 4.1.3, 10.2.6), finds it without them
    private final ThreadLocal<Boolean> building = new ThreadLocal<>();

    /**
     * @param configuration The configuration
     * @param tables        The providers found among its components so far, {@code null} if none
     */
    JaxRsClientProviders(JaxRsConfiguration configuration, @Nullable Tables tables) {
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
     * @return The providers found among the current components of the configuration, which a copy
     * of the configuration shares as long as it has the same components
     */
    Tables tables() {
        List<Component> components = configuration.components();
        Tables current = tables;
        if (current == null || current.components != components) {
            building.set(Boolean.TRUE);
            try {
                current = new Tables(components, this::contextValue);
            } finally {
                building.remove();
            }
            if (components.stream().anyMatch(Component::isConstructing)) {
                // found for the constructor of a component, without it: not kept
                return current;
            }
            tables = current;
        }
        return current;
    }

    /**
     * @return The readers: found without the tables while they are being built, e.g. for a
     * provider that looks up a reader in its constructor (JAX-RS 4.1.3, 10.2.6)
     */
    private List<Definition<io.micronaut.http.body.MessageBodyReader<?>>> readers() {
        return building.get() != null ? readers(configuration.components(), this::contextValue) : tables().readers;
    }

    /**
     * @return The writers: found without the tables while they are being built
     */
    private List<Definition<io.micronaut.http.body.MessageBodyWriter<?>>> writers() {
        return building.get() != null ? writers(configuration.components(), this::contextValue) : tables().writers;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Definition<io.micronaut.http.body.MessageBodyReader<?>>> readers(List<Component> components,
                                                                                       Function<Class<?>, @Nullable Object> contextValues) {
        return (List) definitions(components, contextValues, MessageBodyReader.class,
            (reader, metadata) -> new JaxRsMessageBodyReader<>((MessageBodyReader<Object>) reader), io.micronaut.http.body.MessageBodyReader.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Definition<io.micronaut.http.body.MessageBodyWriter<?>>> writers(List<Component> components,
                                                                                       Function<Class<?>, @Nullable Object> contextValues) {
        return (List) definitions(components, contextValues, MessageBodyWriter.class,
            (writer, metadata) -> new JaxRsMessageBodyWriter<>(metadata, (MessageBodyWriter<Object>) writer), io.micronaut.http.body.MessageBodyWriter.class);
    }

    List<BeanRegistration<ReaderInterceptor>> getReaderInterceptors() {
        return tables().readerInterceptors;
    }

    List<BeanRegistration<WriterInterceptor>> getWriterInterceptors() {
        return tables().writerInterceptors;
    }

    List<ClientRequestFilter> getRequestFilters() {
        return tables().requestFilters;
    }

    List<ClientResponseFilter> getResponseFilters() {
        return tables().responseFilters;
    }

    /**
     * The readers or the writers of the components, the JAX-RS ones and the ones of Micronaut, in
     * the order of their priorities (JAX-RS 4.1.3).
     *
     * @param components        The components
     * @param contextValues     The context values of the constructor of a component registered as a class
     * @param jaxRsContract     The JAX-RS contract
     * @param jaxRsAdapter      The provider of Micronaut of a JAX-RS provider
     * @param micronautContract The contract of Micronaut
     * @param <P>               The contract of Micronaut
     * @return The providers
     */
    @SuppressWarnings("unchecked")
    private static <P> List<Definition<P>> definitions(List<Component> components, Function<Class<?>, @Nullable Object> contextValues,
                                                       Class<?> jaxRsContract, BiFunction<Object, AnnotationMetadata, P> jaxRsAdapter,
                                                       Class<P> micronautContract) {
        List<Definition<P>> built = new ArrayList<>();
        for (Component component : components) {
            if (component.isConstructing()) {
                // its constructor looks up the providers: it is not one of them yet
                continue;
            }
            AnnotationMetadata metadata = component.metadata();
            if (isNotConstrainedToClient(metadata)) {
                continue;
            }
            Object provider = registeredFor(component, jaxRsContract) ? component.tryGet(jaxRsContract, contextValues) : null;
            if (provider != null) {
                built.add(new Definition<>(providedType(provider, jaxRsContract), jaxRsAdapter.apply(provider, metadata),
                    priority(component, jaxRsContract), metadata));
            }
            P micronautProvider = registeredFor(component, micronautContract) ? component.tryGet(micronautContract, contextValues) : null;
            if (micronautProvider != null) {
                Argument<?> type;
                if (micronautContract == io.micronaut.http.body.MessageBodyReader.class && micronautProvider instanceof TypedMessageBodyReader<?> typed) {
                    type = typed.getType();
                } else if (micronautContract == io.micronaut.http.body.MessageBodyWriter.class && micronautProvider instanceof TypedMessageBodyWriter<?> typed) {
                    type = typed.getType();
                } else {
                    type = providedType(micronautProvider, micronautContract);
                }
                built.add(new Definition<>(type, micronautProvider, priority(component, micronautContract), metadata));
            }
        }
        // a stable sort
        built.sort(Comparator.comparingInt(Definition::priority));
        return Collections.unmodifiableList(built);
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

    @SuppressWarnings("unchecked")
    <T> io.micronaut.http.body.@Nullable MessageBodyReader<T> findReader(Argument<T> argument,
                                                                                @Nullable MediaType mediaType) {
        List<Definition<io.micronaut.http.body.MessageBodyReader<?>>> readers = readers();
        // First, the JAX-RS reader in the order of the readers (JAX-RS 4.2.1)
        Definition<io.micronaut.http.body.MessageBodyReader<?>> best = select(readers, definition ->
            definition.provider() instanceof JaxRsMessageBodyReader<?> jaxRsReader
                && isCompatible(definition.metadata(), Consumes.class, mediaType)
                && definition.type().isAssignableFrom(argument.getType())
                && ((io.micronaut.http.body.MessageBodyReader<T>) jaxRsReader).isReadable(argument, mediaType)
                ? rank(definition, jaxRsReader.delegate(), Consumes.class, argument.getType(), mediaType) : null,
            JaxRsProviderSelection.APPLICATION_FIRST_READER_ORDER);
        if (best != null) {
            return (io.micronaut.http.body.MessageBodyReader<T>) best.provider();
        }
        // Find any kind of reader
        for (Definition<io.micronaut.http.body.MessageBodyReader<?>> definition : readers) {
            io.micronaut.http.body.MessageBodyReader<T> reader = (io.micronaut.http.body.MessageBodyReader<T>) definition.provider();
            // the JAX-RS readers were all considered above
            if (!(reader instanceof JaxRsMessageBodyReader<?>)
                && definition.type().isAssignableFrom(argument.getType()) && reader.isReadable(argument, mediaType)) {
                return reader;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    <T> io.micronaut.http.body.@Nullable MessageBodyWriter<T> findWriter(Argument<T> argument,
                                                                                MediaType mediaType) {
        // the JAX-RS writers and the other ones ranked together: a closer type first, e.g. the
        // String writer before a writer of any object of the application (JAX-RS 4.2.2, steps 4-6)
        Definition<io.micronaut.http.body.MessageBodyWriter<?>> best = select(writers(), definition -> {
            io.micronaut.http.body.MessageBodyWriter<T> writer = (io.micronaut.http.body.MessageBodyWriter<T>) definition.provider();
            if (!definition.type().isAssignableFrom(argument.getType())) {
                return null;
            }
            if (writer instanceof JaxRsMessageBodyWriter<?> jaxRsWriter) {
                return isCompatible(definition.metadata(), Produces.class, mediaType) && writer.isWriteable(argument, mediaType)
                    ? rank(definition, jaxRsWriter.delegate(), Produces.class, argument.getType(), mediaType) : null;
            }
            // a writer of Micronaut: a standard one, without a declared media type
            return writer.isWriteable(argument, mediaType)
                ? new Rank(0, true, definition.priority(), JaxRsUtils.typeDistance(definition.type().getType(), argument.getType())) : null;
        }, JaxRsProviderSelection.WRITER_ORDER);
        return best == null ? null : (io.micronaut.http.body.MessageBodyWriter<T>) best.provider();
    }

    /**
     * Select the best of the candidates: the first of the lowest rank in an order.
     *
     * @param candidates The candidates
     * @param rank       The rank of a candidate, {@code null} for one that does not apply
     * @param order      The order of the ranks
     * @param <D>        The type of the candidates
     * @return The best candidate, {@code null} if none applies
     */
    private static <D> @Nullable D select(List<D> candidates, Function<D, @Nullable Rank> rank, Comparator<Rank> order) {
        D best = null;
        Rank bestRank = null;
        for (D candidate : candidates) {
            Rank candidateRank = rank.apply(candidate);
            if (candidateRank != null && (bestRank == null || order.compare(candidateRank, bestRank) < 0)) {
                best = candidate;
                bestRank = candidateRank;
            }
        }
        return best;
    }

    /**
     * Whether a JAX-RS provider reads or writes a media type: the types of its {@code @Consumes}
     * or {@code @Produces}, any type without one, are compatible with it (JAX-RS 4.2.1, 4.2.2).
     */
    private static boolean isCompatible(AnnotationMetadata metadata, Class<? extends Annotation> declaring, @Nullable MediaType mediaType) {
        // a value of the annotation may be a comma separated list
        return mediaType == null || JaxRsProviderSelection.isCompatible(JaxRsProviderSelection.declared(metadata, declaring, true), mediaType);
    }

    /**
     * The rank of a JAX-RS provider for an entity, which {@link JaxRsProviderSelection#APPLICATION_FIRST_READER_ORDER}
     * orders the readers with and {@link JaxRsProviderSelection#WRITER_ORDER} the writers.
     */
    private static Rank rank(Class<?> provided, Object provider, AnnotationMetadata metadata, Class<? extends Annotation> declaring,
                             int priority, Class<?> type, @Nullable MediaType mediaType) {
        int specificity = mediaType == null ? 0
            : JaxRsProviderSelection.specificity(JaxRsProviderSelection.declared(metadata, declaring, true), List.of(mediaType));
        return new Rank(specificity, JaxRsProviderSelection.isStandard(provider.getClass()), priority, JaxRsUtils.typeDistance(provided, type));
    }

    private static Rank rank(Definition<?> definition, Object provider, Class<? extends Annotation> declaring, Class<?> type, @Nullable MediaType mediaType) {
        return rank(definition.type().getType(), provider, definition.metadata(), declaring, definition.priority(), type, mediaType);
    }

    /**
     * @param type The type of the providers
     * @param <T>  The type
     * @return The registered providers of a type, in the order of their priorities
     */
    <T> List<T> getProviders(Class<T> type) {
        return registrations(configuration.components(), type, this::contextValue).stream().map(BeanRegistration::getBean).toList();
    }

    /**
     * @return The registered providers of a type with their priorities, in the order of the priorities
     */
    private static <T> List<BeanRegistration<T>> registrations(List<Component> components, Class<T> type,
                                                               Function<Class<?>, @Nullable Object> contextValues) {
        var registrations = new ArrayList<BeanRegistration<T>>();
        for (Component component : components) {
            if (component.isConstructing()) {
                // its constructor looks up the providers: it is not one of them yet
                continue;
            }
            if (!registeredFor(component, type) || isNotConstrainedToClient(component.metadata())) {
                // registered for other contracts only, or for another runtime (@ConstrainedTo)
                continue;
            }
            T instance = component.tryGet(type, contextValues);
            if (instance != null) {
                registrations.add(JaxRsRouteInterceptors.registration(instance, component.metadata(), priority(component, type)));
            }
        }
        registrations.sort(Comparator.comparingInt(BeanRegistration::getOrder));
        return Collections.unmodifiableList(registrations);
    }

    /**
     * @return The context resolvers with their context types, {@code null} for a resolver whose context type is not known
     */
    @SuppressWarnings("rawtypes")
    private static List<ContextResolverEntry> contextResolvers(List<Component> components, Function<Class<?>, @Nullable Object> contextValues) {
        List<ContextResolverEntry> built = new ArrayList<>();
        for (BeanRegistration<ContextResolver> registration : registrations(components, ContextResolver.class, contextValues)) {
            Argument<?> resolved = JaxRsReflection.get().resolveGeneric(registration.getBean().getClass(), ContextResolver.class);
            Argument<?>[] arguments = resolved == null ? new Argument<?>[0] : resolved.getTypeParameters();
            built.add(new ContextResolverEntry(registration, arguments.length == 0 ? null : arguments[0].getType()));
        }
        return Collections.unmodifiableList(built);
    }

    /**
     * Inject the {@code @Context} fields of a provider a client supports (JAX-RS 9.2): its
     * {@link Providers} and {@link Configuration}, once, when it is registered or instantiated.
     * The other context types are the ones of a server request.
     *
     * @param instance      The provider
     * @param contextValues The values of the context types
     */
    static void inject(Object instance, Function<Class<?>, @Nullable Object> contextValues) {
        JaxRsReflection.get().injectFields(instance, Context.class, contextValues);
    }

    /**
     * @param type A context type
     * @return The value of a context type a client provider is injected with, {@code null} for
     * the context types of a server request
     */
    @Nullable Object contextValue(Class<?> type) {
        return type == Providers.class ? contextProviders
            : type == Configuration.class ? contextConfiguration
            : null;
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
        Definition<io.micronaut.http.body.MessageBodyReader<?>> best = select(readers(), definition ->
            definition.provider() instanceof JaxRsMessageBodyReader<?> jaxRsReader
                && isCompatible(definition.metadata(), Consumes.class, micronautMediaType)
                && definition.type().isAssignableFrom(type)
                && jaxRsReader.delegate().isReadable(type, genericType, annotations, mediaType)
                ? rank(definition, jaxRsReader.delegate(), Consumes.class, type, micronautMediaType) : null,
            JaxRsProviderSelection.APPLICATION_FIRST_READER_ORDER);
        return best != null ? (MessageBodyReader<T>) ((JaxRsMessageBodyReader<?>) best.provider()).delegate()
            : (MessageBodyReader<T>) standardProvider(type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> @Nullable MessageBodyWriter<T> getMessageBodyWriter(Class<T> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
        // selected like the writers of the entities (JAX-RS 4.2.2)
        MediaType micronautMediaType = JaxRsUtils.convert(mediaType);
        Definition<io.micronaut.http.body.MessageBodyWriter<?>> best = select(writers(), definition ->
            definition.provider() instanceof JaxRsMessageBodyWriter<?> jaxRsWriter
                && isCompatible(definition.metadata(), Produces.class, micronautMediaType)
                && definition.type().isAssignableFrom(type)
                && jaxRsWriter.delegate().isWriteable(type, genericType, annotations, mediaType)
                ? rank(definition, jaxRsWriter.delegate(), Produces.class, type, micronautMediaType) : null,
            JaxRsProviderSelection.WRITER_ORDER);
        return best != null ? (MessageBodyWriter<T>) ((JaxRsMessageBodyWriter<?>) best.provider()).delegate()
            : (MessageBodyWriter<T>) standardProvider(type);
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
        List<ContextResolverEntry> entries = building.get() != null
            ? contextResolvers(configuration.components(), this::contextValue) : tables().contextResolvers;
        for (ContextResolverEntry entry : entries) {
            BeanRegistration<ContextResolver> registration = entry.registration();
            Class<?> resolvedType = entry.contextType();
            if ((resolvedType == null || contextType.isAssignableFrom(resolvedType))
                && isCompatible(registration.getBeanDefinition().getAnnotationMetadata(), Produces.class, micronautMediaType)) {
                candidates.add(registration);
                if (resolvedType == null) {
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
     * The scope of the current providers, see {@link #current()}.
     */
    interface CurrentScope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * The providers found among the components of a configuration, built at once: the copies of a
     * configuration share them, as long as they have the same components.
     */
    static final class Tables {
        // the immutable components they were found among
        private final List<Component> components;
        private final List<BeanRegistration<ReaderInterceptor>> readerInterceptors;
        private final List<BeanRegistration<WriterInterceptor>> writerInterceptors;
        private final List<Definition<io.micronaut.http.body.MessageBodyReader<?>>> readers;
        private final List<Definition<io.micronaut.http.body.MessageBodyWriter<?>>> writers;
        private final List<ClientRequestFilter> requestFilters;
        private final List<ClientResponseFilter> responseFilters;
        private final List<ContextResolverEntry> contextResolvers;

        @SuppressWarnings({"unchecked", "rawtypes"})
        Tables(List<Component> components, Function<Class<?>, @Nullable Object> contextValues) {
            this.components = components;
            readerInterceptors = registrations(components, ReaderInterceptor.class, contextValues);
            writerInterceptors = registrations(components, WriterInterceptor.class, contextValues);
            readers = readers(components, contextValues);
            writers = writers(components, contextValues);
            requestFilters = registrations(components, ClientRequestFilter.class, contextValues).stream().map(BeanRegistration::getBean).toList();
            // in the reverse order of their priorities (JAX-RS 6.6)
            responseFilters = registrations(components, ClientResponseFilter.class, contextValues).stream().map(BeanRegistration::getBean).toList().reversed();
            contextResolvers = JaxRsClientProviders.contextResolvers(components, contextValues);
        }
    }

    /**
     * A reader or a writer of the configuration.
     *
     * @param type     The type it reads or writes
     * @param provider The reader or writer of Micronaut, an adapter of a JAX-RS one
     * @param priority The priority
     * @param metadata The annotations of the class of the provider
     * @param <P>      The type of the provider
     */
    private record Definition<P>(Argument<?> type, P provider, int priority, AnnotationMetadata metadata) {
    }

    /**
     * A context resolver, with its context type.
     *
     * @param registration The resolver
     * @param contextType  The type of its contexts, {@code null} if not known
     */
    @SuppressWarnings("rawtypes")
    private record ContextResolverEntry(BeanRegistration<ContextResolver> registration, @Nullable Class<?> contextType) {
    }
}
