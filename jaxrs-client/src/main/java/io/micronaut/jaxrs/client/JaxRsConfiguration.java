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
import io.micronaut.context.BeanRegistration;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.Headers;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMessage;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpMessage;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.TypedMessageBodyReader;
import io.micronaut.http.body.TypedMessageBodyWriter;
import io.micronaut.jaxrs.common.JaxRsArgumentUtil;
import io.micronaut.jaxrs.common.ByteArrayByteBuffer;
import io.micronaut.jaxrs.common.HttpMessageEntityReader;
import io.micronaut.jaxrs.common.JaxRsInterceptedRead;
import io.micronaut.jaxrs.common.JaxRsInterceptedWrite;
import io.micronaut.jaxrs.common.JaxRsMessageBodyReader;
import io.micronaut.jaxrs.common.JaxRsMessageBodyReaderDefinition;
import io.micronaut.jaxrs.common.JaxRsMessageBodyWriter;
import io.micronaut.jaxrs.common.JaxRsRouteInterceptors;
import io.micronaut.jaxrs.common.JaxRsUtils;
import io.micronaut.jaxrs.common.body.standard.JaxRsByteArrayMessageBodyReaderWriter;
import io.micronaut.jaxrs.common.body.standard.JaxRsStringMessageBodyReaderWriter;
import io.micronaut.jaxrs.common.JaxRsCurrentProviders;
import io.micronaut.jaxrs.common.JaxRsWriterInterceptorContextState;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.ext.Providers;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.WriterInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The JAX-RS Client configuration.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
final class JaxRsConfiguration implements Configuration {

    /**
     * The priority of a component or contract registered without one: the one of its
     * {@code @Priority} applies.
     */
    static final int NO_PRIORITY = Integer.MIN_VALUE;

    private static final Logger LOG = LoggerFactory.getLogger(JaxRsConfiguration.class);
    private final Providers providers = new ClientProviders();
    // the context the providers are injected with: the copies of a configuration share the
    // providers, so these use the configuration of the current invocation, else this one
    private final Providers contextProviders = new CurrentProviders(providers);
    private final Configuration contextConfiguration = new CurrentConfiguration(this);

    private final Map<String, Object> properties;
    private final List<Component> components;

    // the features whose configure returned true
    private final List<Feature> enabledFeatures;

    // the providers found among the components: built fully, then published unmodifiable
    private volatile @Nullable List<BeanRegistration<ReaderInterceptor>> readerInterceptors;
    private volatile @Nullable List<BeanRegistration<WriterInterceptor>> writerInterceptors;
    private volatile @Nullable List<JaxRsMessageBodyReaderDefinition> readers;
    private volatile @Nullable List<JaxRsMessageBodyWriterDefinition> writers;
    private volatile @Nullable List<ClientRequestFilter> requestFilters;
    private volatile @Nullable List<ClientResponseFilter> responseFilters;

    // the body handlers of the HTTP client, which write the entities no JAX-RS writer writes
    private volatile @Nullable MessageBodyHandlerRegistry handlerRegistry;

    public JaxRsConfiguration() {
        this(new LinkedHashMap<>(), new ArrayList<>());
    }

    public JaxRsConfiguration(Map<String, Object> properties, List<Component> components) {
        this(properties, components, new ArrayList<>());
    }

    private JaxRsConfiguration(Map<String, Object> properties, List<Component> components, List<Feature> enabledFeatures) {
        this.properties = properties;
        this.components = components;
        this.enabledFeatures = enabledFeatures;
    }

    /**
     * A copy of the configuration: the components keep their instances, and the providers found
     * among them.
     *
     * @return The copy
     */
    JaxRsConfiguration copy() {
        JaxRsConfiguration copy = new JaxRsConfiguration(new LinkedHashMap<>(properties), new ArrayList<>(components), new ArrayList<>(enabledFeatures));
        copy.readerInterceptors = readerInterceptors;
        copy.writerInterceptors = writerInterceptors;
        copy.readers = readers;
        copy.writers = writers;
        copy.requestFilters = requestFilters;
        copy.responseFilters = responseFilters;
        copy.handlerRegistry = handlerRegistry;
        return copy;
    }

    /**
     * Forget the providers found among the components, when the components change.
     */
    private void clearCaches() {
        readerInterceptors = null;
        writerInterceptors = null;
        readers = null;
        writers = null;
        requestFilters = null;
        responseFilters = null;
    }

    /**
     * Replace the properties and components with the ones of another configuration.
     *
     * @param configuration The configuration
     */
    void replaceWith(Configuration configuration) {
        properties.clear();
        components.clear();
        enabledFeatures.clear();
        clearCaches();
        if (configuration instanceof JaxRsConfiguration jaxRsConfiguration) {
            properties.putAll(jaxRsConfiguration.properties);
            components.addAll(jaxRsConfiguration.components);
            enabledFeatures.addAll(jaxRsConfiguration.enabledFeatures);
            return;
        }
        properties.putAll(configuration.getProperties());
        // no contracts: registered for all of the ones it implements
        for (Class<?> componentClass : configuration.getClasses()) {
            Map<Class<?>, Integer> contracts = configuration.getContracts(componentClass);
            if (contracts.isEmpty()) {
                register(componentClass);
            } else {
                register(componentClass, contracts);
            }
        }
        for (Object instance : configuration.getInstances()) {
            Map<Class<?>, Integer> contracts = configuration.getContracts(instance.getClass());
            if (contracts.isEmpty()) {
                register(instance);
            } else {
                register(instance, contracts);
            }
        }
    }

    public void addProperty(String name, @Nullable Object value) {
        // a null value removes the property (JAX-RS, Configurable#property)
        if (value == null) {
            properties.remove(name);
        } else {
            properties.put(name, value);
        }
    }

    /**
     * @param handlerRegistry The body handlers of the HTTP client
     */
    void setHandlerRegistry(MessageBodyHandlerRegistry handlerRegistry) {
        this.handlerRegistry = handlerRegistry;
    }

    void register(Class<?> componentClass) {
        register(componentClass, NO_PRIORITY);
    }

    void register(Class<?> componentClass, int priority) {
        register(componentClass, priority, new Class<?>[0]);
    }

    void register(Class<?> componentClass, Class<?>... contracts) {
        if (contracts == null || contracts.length == 0) {
            return;
        }
        register(componentClass, NO_PRIORITY, contracts);
    }

    private List<ComponentContract> toContracts(Class<?>[] contracts) {
        return Arrays.stream(contracts).map(c -> new ComponentContract(c, NO_PRIORITY)).toList();
    }

    void register(Class<?> componentClass, int priority, Class<?>... contracts) {
        List<ComponentContract> assignable = assignable(componentClass, toContracts(contracts));
        if (assignable != null) {
            add(new ClassComponent(componentClass, priority, assignable));
        }
    }

    void register(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
        if (contracts == null || contracts.isEmpty()) {
            // registered for no contract: ignored, like the one with no contract classes
            return;
        }
        List<ComponentContract> assignable = assignable(componentClass, toContracts(contracts));
        if (assignable != null) {
            add(new ClassComponent(componentClass, NO_PRIORITY, assignable));
        }
    }

    /**
     * The contracts a component implements: the others are ignored, and a component that
     * implements none of the given contracts is not registered (JAX-RS, {@code Configurable}).
     *
     * @return The contracts, or {@code null} if the component is not registered
     */
    private static @Nullable List<ComponentContract> assignable(Class<?> componentClass, List<ComponentContract> contracts) {
        if (contracts.isEmpty()) {
            return contracts;
        }
        List<ComponentContract> assignable = contracts.stream()
            .filter(contract -> contract.contract().isAssignableFrom(componentClass))
            .toList();
        if (assignable.size() != contracts.size()) {
            LOG.warn("The component {} does not implement the contracts {}: they are ignored", componentClass.getName(),
                contracts.stream().filter(c -> !assignable.contains(c)).map(c -> c.contract().getName()).toList());
        }
        return assignable.isEmpty() ? null : assignable;
    }

    private List<ComponentContract> toContracts(Map<Class<?>, Integer> contracts) {
        return contracts.entrySet().stream().map(e -> new ComponentContract(e.getKey(), e.getValue())).toList();
    }

    void register(Object component) {
        register(component, NO_PRIORITY);
    }

    /**
     * Register a standard provider of the client: several of them may be of the same class, e.g.
     * the readers of the HTTP client.
     *
     * @param component The provider
     */
    void registerStandard(Object component) {
        components.add(InstanceComponent.of(component, NO_PRIORITY, List.of()));
        clearCaches();
    }

    void register(Object component, int priority) {
        register(component, priority, new Class<?>[0]);
    }

    void register(Object component, Class<?>... contracts) {
        if (contracts == null || contracts.length == 0) {
            return;
        }
        register(component, NO_PRIORITY, contracts);
    }

    void register(Object component, int priority, Class<?>... contracts) {
        List<ComponentContract> assignable = assignable(component.getClass(), toContracts(contracts));
        if (assignable != null) {
            add(InstanceComponent.of(component, priority, assignable));
        }
    }

    void register(Object component, Map<Class<?>, Integer> contracts) {
        if (contracts == null || contracts.isEmpty()) {
            // registered for no contract: ignored, like the one with no contract classes
            return;
        }
        List<ComponentContract> assignable = assignable(component.getClass(), toContracts(contracts));
        if (assignable != null) {
            add(InstanceComponent.of(component, NO_PRIORITY, assignable));
        }
    }

    /**
     * Add a component: a feature is configured, and is enabled when it says so (JAX-RS,
     * {@code Feature#configure}). A component of a class that is registered already is ignored
     * (JAX-RS, {@code Configurable}).
     */
    private void add(Component component) {
        Class<?> componentClass = component.componentClass();
        if (isRegistered(componentClass)) {
            LOG.warn("The component {} is registered already: the registration is ignored", componentClass.getName());
            return;
        }
        components.add(component);
        clearCaches();
        if (isNotConstrainedToClient(component.metadata())) {
            // kept in the configuration, which may configure a server, but not used by the client
            LOG.warn("The component {} is constrained to another runtime than the client: the client ignores it", componentClass.getName());
            return;
        }
        if (registeredFor(component, Feature.class)) {
            Feature feature = injected(component.tryGet(Feature.class));
            if (feature != null && feature.configure(new ClientFeatureContext())) {
                enabledFeatures.add(feature);
            }
        }
    }

    @Override
    public RuntimeType getRuntimeType() {
        return RuntimeType.CLIENT;
    }

    @Override
    public Map<String, Object> getProperties() {
        return Collections.unmodifiableMap(properties);
    }

    @Override
    public @Nullable Object getProperty(String name) {
        return properties.get(name);
    }

    @Override
    public Collection<String> getPropertyNames() {
        return properties.keySet();
    }

    @Override
    public boolean isEnabled(Feature feature) {
        return enabledFeatures.contains(feature);
    }

    @Override
    public boolean isEnabled(Class<? extends Feature> featureClass) {
        return enabledFeatures.stream().anyMatch(feature -> feature.getClass() == featureClass);
    }

    @Override
    public boolean isRegistered(Object instance) {
        for (Component component : components) {
            if (component instanceof InstanceComponent instanceComponent && instanceComponent.component.equals(instance)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isRegistered(Class<?> componentClass) {
        for (Component component : components) {
            if (component.is(componentClass)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Map<Class<?>, Integer> getContracts(Class<?> componentClass) {
        return components.stream()
            .filter(c -> c.is(componentClass))
            .flatMap(c -> c.components().stream())
            .collect(Collectors.toMap(component -> component.contract, component -> component.priority, (p1, p2) -> p1));
    }

    @Override
    public Set<Class<?>> getClasses() {
        return components.stream()
            .flatMap(c -> c instanceof ClassComponent classComponent ? Stream.of(classComponent.componentClass) : Stream.empty())
            .collect(Collectors.toSet());
    }

    @Override
    public Set<Object> getInstances() {
        return components.stream()
            .flatMap(c -> c instanceof InstanceComponent instanceComponent ? Stream.of(instanceComponent.component) : Stream.empty())
            .collect(Collectors.toSet());
    }

    private List<BeanRegistration<ReaderInterceptor>> getReaderInterceptors() {
        List<BeanRegistration<ReaderInterceptor>> interceptors = readerInterceptors;
        if (interceptors == null) {
            interceptors = registrationsOfType(ReaderInterceptor.class);
            readerInterceptors = interceptors;
        }
        return interceptors;
    }

    private List<BeanRegistration<WriterInterceptor>> getWriterInterceptors() {
        List<BeanRegistration<WriterInterceptor>> interceptors = writerInterceptors;
        if (interceptors == null) {
            interceptors = registrationsOfType(WriterInterceptor.class);
            writerInterceptors = interceptors;
        }
        return interceptors;
    }

    private List<JaxRsMessageBodyReaderDefinition> getReaders() {
        List<JaxRsMessageBodyReaderDefinition> found = readers;
        if (found == null) {
            List<JaxRsMessageBodyReaderDefinition> built = new ArrayList<>();
            for (JaxRsConfiguration.Component component : components) {
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
            this.readers = found;
        }
        return found;
    }

    private static boolean isNotConstrainedToClient(AnnotationMetadata annotationMetadata) {
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
    private static boolean registeredFor(Component component, Class<?> contract) {
        List<ComponentContract> contracts = component.components();
        return contracts.isEmpty() || contracts.stream().anyMatch(c -> c.contract() == contract);
    }

    /**
     * The priority of a component for a contract: the one of the registration, else of the
     * contract, else its {@code @Priority}, else {@link jakarta.ws.rs.Priorities#USER}.
     */
    private static int priority(Component component, Class<?> contract) {
        if (component.priority() != NO_PRIORITY) {
            return component.priority();
        }
        for (ComponentContract c : component.components()) {
            if (c.contract() == contract && c.priority() != NO_PRIORITY) {
                return c.priority();
            }
        }
        return JaxRsUtils.getPriorityOrder(component.metadata());
    }

    private List<JaxRsMessageBodyWriterDefinition> getWriters() {
        List<JaxRsMessageBodyWriterDefinition> found = writers;
        if (found == null) {
            List<JaxRsMessageBodyWriterDefinition> built = new ArrayList<>();
            for (JaxRsConfiguration.Component component : components) {
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
            this.writers = found;
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
        Providers previous = JaxRsCurrentProviders.set(providers);
        try {
            return supplier.get();
        } finally {
            JaxRsCurrentProviders.restore(previous);
        }
    }

    public HttpMessageEntityReader createHttpMessageEntityReader() {
        return createHttpMessageEntityReader(new LinkedHashMap<>());
    }

    /**
     * @param properties The properties of the request the response is of, which the reader
     *                   interceptor contexts share
     * @return The reader of the entity of a response
     */
    public HttpMessageEntityReader createHttpMessageEntityReader(Map<String, Object> properties) {
        return new HttpMessageEntityReader() {

            @Override
            public <T> @Nullable T readEntity(HttpMessage<?> message, Argument<T> entityType) {
                Providers previous = JaxRsCurrentProviders.set(providers);
                try {
                    return readEntityOfConfiguration(message, entityType);
                } finally {
                    JaxRsCurrentProviders.restore(previous);
                }
            }

            private <T> @Nullable T readEntityOfConfiguration(HttpMessage<?> message, Argument<T> entityType) {
                ByteBuffer<?> byteBuffer = message.getBody(ByteBuffer.class)
                    .or(() -> message.getBody(byte[].class).map(ByteArrayByteBuffer::new))
                    .orElse(null);
                if (byteBuffer != null) {
                    List<BeanRegistration<ReaderInterceptor>> readerInterceptors = getReaderInterceptors();
                    // an entity without a type is a stream of bytes (JAX-RS 4.2.1, step 1)
                    io.micronaut.http.MediaType mediaType = message.getContentType().orElse(MediaType.APPLICATION_OCTET_STREAM_TYPE);
                    HttpHeaders headers = message.getHeaders();
                    if (readerInterceptors.isEmpty()) {
                        io.micronaut.http.body.MessageBodyReader<T> reader = findReader(entityType, mediaType);
                        if (reader != null) {
                            return reader.read(entityType, mediaType, headers, byteBuffer);
                        }
                    } else {
                        return new JaxRsInterceptedRead<T>(readerInterceptors, metadata -> true) {

                            @Override
                            protected @Nullable T readFromAfterInterception(Argument<Object> type, @Nullable MediaType mediaType, Headers httpHeaders, InputStream inputStream) {
                                io.micronaut.http.body.MessageBodyReader<Object> reader = findReader(type, mediaType);
                                if (reader != null) {
                                    return (T) reader.read(type, mediaType, headers, inputStream);
                                }
                                throw new IllegalStateException("No reader found for type " + type.getType() + " and mediaType " + mediaType);
                            }

                        }.intercept(entityType, mediaType, headers, byteBuffer.toInputStream(), properties);
                    }
                }
                return super.readEntity(message, entityType);
            }
        };
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

    <T> void writeBody(MutableHttpMessage<?> mutableHttpMessage, Argument<T> bodyArgument, @Nullable T body) {
        writeBody(mutableHttpMessage, bodyArgument, body, false, new LinkedHashMap<>());
    }

    /**
     * Write an entity: with the JAX-RS writers, else the ones of the HTTP client, through the
     * writer interceptors (JAX-RS 6.4). Without interceptors, an entity no JAX-RS writer writes
     * is left to the HTTP client, unless its bytes are required.
     *
     * @param mutableHttpMessage The message
     * @param bodyArgument       The type of the entity
     * @param body               The entity
     * @param bytes              Whether the entity must be written to bytes, e.g. to write them
     *                           through the entity stream a filter replaced
     * @param properties         The properties of the request, which the writer interceptor contexts share
     * @param <T>                The type of the entity
     */
    @SuppressWarnings("unchecked")
    <T> void writeBody(MutableHttpMessage<?> mutableHttpMessage, Argument<T> bodyArgument, @Nullable T body, boolean bytes,
                       Map<String, Object> properties) {
        if (body == null) {
            return;
        }
        Providers previous = JaxRsCurrentProviders.set(providers);
        try {
            writeBodyOfConfiguration(mutableHttpMessage, bodyArgument, body, bytes, properties);
        } finally {
            JaxRsCurrentProviders.restore(previous);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void writeBodyOfConfiguration(MutableHttpMessage<?> mutableHttpMessage, Argument<T> bodyArgument, T body, boolean bytes,
                                              Map<String, Object> properties) {
        if (body instanceof GenericEntity<?> genericEntity) {
            // the entity, written as its generic type
            writeBody(mutableHttpMessage, (Argument<Object>) JaxRsArgumentUtil.from(genericEntity), genericEntity.getEntity(), bytes, properties);
            return;
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        io.micronaut.http.MediaType mediaType = mutableHttpMessage.getContentType().orElse(MediaType.ALL_TYPE);

        final AtomicBoolean written = new AtomicBoolean(false);
        List<BeanRegistration<WriterInterceptor>> writerInterceptors = getWriterInterceptors();
        if (writerInterceptors.isEmpty()) {
            io.micronaut.http.body.MessageBodyWriter<T> writer = findWriter(bodyArgument, mediaType);
            if (writer == null && bytes) {
                writer = findClientWriter(bodyArgument, mediaType);
                mediaType = clientMediaType(mediaType);
            }
            if (writer != null) {
                writer.writeTo(bodyArgument, mediaType, body, mutableHttpMessage.getHeaders(), outputStream);
                written.set(true);
            }
        } else {
            new JaxRsInterceptedWrite<T, JaxRsWriterInterceptorContextState.ClassicState>(writerInterceptors, metadata -> true) {

                @Override
                protected void writeToAfterInterception(Argument<Object> argument, MediaType mediaType, JaxRsWriterInterceptorContextState.ClassicState state) {
                    io.micronaut.http.body.MessageBodyWriter<Object> writer = findWriter(argument, mediaType);
                    MediaType writtenType = mediaType;
                    if (writer == null) {
                        writer = findClientWriter(argument, mediaType);
                        writtenType = clientMediaType(mediaType);
                    }
                    if (writer != null) {
                        writer.writeTo(argument, writtenType, state.getEntity(), mutableHttpMessage.getHeaders(), state.getOutputStream());
                        written.set(true);
                    }
                }

            }.intercept(bodyArgument, mediaType, new JaxRsWriterInterceptorContextState.ClassicState(mutableHttpMessage.getHeaders(), body, outputStream), properties);
        }

        if (written.get()) {
            mutableHttpMessage.body(outputStream.toByteArray());
        } else if (!getWriterInterceptors().isEmpty()) {
            throw new IllegalStateException("Unknown entity type " + bodyArgument.getType());
        } else {
            mutableHttpMessage.body(body);
        }
    }

    /**
     * The writer of the HTTP client of an entity no JAX-RS writer writes.
     */
    private <T> io.micronaut.http.body.@Nullable MessageBodyWriter<T> findClientWriter(Argument<T> argument, MediaType mediaType) {
        MessageBodyHandlerRegistry registry = handlerRegistry;
        if (registry == null) {
            return null;
        }
        return registry.findWriter(argument, List.of(clientMediaType(mediaType))).orElse(null);
    }

    /**
     * The media type the HTTP client writes an entity with: JSON when the request has none.
     */
    private static MediaType clientMediaType(MediaType mediaType) {
        return mediaType.equals(MediaType.ALL_TYPE) ? MediaType.APPLICATION_JSON_TYPE : mediaType;
    }

    private <T> io.micronaut.http.body.@Nullable MessageBodyWriter<T> findWriter(Argument<T> argument,
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

    public List<ClientRequestFilter> getRequestFilters() {
        List<ClientRequestFilter> filters = requestFilters;
        if (filters == null) {
            filters = getComponentOfType(ClientRequestFilter.class);
            requestFilters = filters;
        }
        return filters;
    }

    public List<ClientResponseFilter> getResponseFilters() {
        List<ClientResponseFilter> filters = responseFilters;
        if (filters == null) {
            // in the reverse order of their priorities (JAX-RS 6.6)
            filters = new ArrayList<>(getComponentOfType(ClientResponseFilter.class));
            Collections.reverse(filters);
            filters = Collections.unmodifiableList(filters);
            responseFilters = filters;
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
        for (JaxRsConfiguration.Component component : components) {
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
    private <T> @Nullable T injected(@Nullable T instance) {
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
        return JaxRsCurrentProviders.current() instanceof ClientProviders clientProviders ? clientProviders.configuration() : fallback;
    }

    /**
     * The {@link Providers} a provider is injected with: the ones of the configuration of the
     * current invocation, else of the configuration that injected it.
     *
     * @param fallback The providers of the configuration that injects it
     */
    private record CurrentProviders(Providers fallback) implements Providers {

        private Providers delegate() {
            return JaxRsCurrentProviders.current() instanceof ClientProviders clientProviders ? clientProviders : fallback;
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
     * The providers of the client, see {@link Providers}.
     */
    private final class ClientProviders implements Providers {

        JaxRsConfiguration configuration() {
            return JaxRsConfiguration.this;
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
    }

    sealed interface Component {

        boolean is(Class<?> type);

        Class<?> componentClass();

        <T> @Nullable T tryGet(Class<T> type);

        int priority();

        List<ComponentContract> components();

        /**
         * @return The annotations of the class of the component, read when it is registered, see
         * {@link JaxRsRouteInterceptors#metadata(Class)}
         */
        AnnotationMetadata metadata();

    }

    record InstanceComponent(Object component, int priority,
                             List<ComponentContract> components, AnnotationMetadata metadata) implements Component {

        static InstanceComponent of(Object component, int priority, List<ComponentContract> components) {
            return new InstanceComponent(component, priority, components, JaxRsRouteInterceptors.metadata(component.getClass()));
        }

        @Override
        public <T> @Nullable T tryGet(Class<T> type) {
            if (type.isInstance(component)) {
                return (T) component;
            }
            return null;
        }

        @Override
        public boolean is(Class<?> type) {
            return type.equals(component.getClass());
        }

        @Override
        public Class<?> componentClass() {
            return component.getClass();
        }
    }

    /**
     * A component registered as a class: it is instantiated once, when it is first used, and the
     * copies of the configuration share the instance.
     */
    static final class ClassComponent implements Component {

        private final Class<?> componentClass;
        private final int priority;
        private final List<ComponentContract> components;
        private final AnnotationMetadata metadata;
        private volatile @Nullable Object instance;

        ClassComponent(Class<?> componentClass, int priority, List<ComponentContract> components) {
            this.componentClass = componentClass;
            this.priority = priority;
            this.components = components;
            this.metadata = JaxRsRouteInterceptors.metadata(componentClass);
        }

        @Override
        public AnnotationMetadata metadata() {
            return metadata;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> @Nullable T tryGet(Class<T> type) {
            if (type.isAssignableFrom(componentClass)) {
                return (T) instance();
            }
            return null;
        }

        private Object instance() {
            Object current = instance;
            if (current == null) {
                synchronized (this) {
                    current = instance;
                    if (current == null) {
                        current = JaxRsReflection.get().instantiate(componentClass);
                        instance = current;
                    }
                }
            }
            return current;
        }

        @Override
        public boolean is(Class<?> type) {
            return type.equals(componentClass);
        }

        @Override
        public Class<?> componentClass() {
            return componentClass;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public List<ComponentContract> components() {
            return components;
        }
    }

    /**
     * The context a feature registers its components in: the configuration.
     */
    private final class ClientFeatureContext implements FeatureContext {

        @Override
        public Configuration getConfiguration() {
            return JaxRsConfiguration.this;
        }

        @Override
        public FeatureContext property(String name, Object value) {
            addProperty(name, value);
            return this;
        }

        @Override
        public FeatureContext register(Class<?> componentClass) {
            JaxRsConfiguration.this.register(componentClass);
            return this;
        }

        @Override
        public FeatureContext register(Class<?> componentClass, int priority) {
            JaxRsConfiguration.this.register(componentClass, priority);
            return this;
        }

        @Override
        public FeatureContext register(Class<?> componentClass, Class<?>... contracts) {
            JaxRsConfiguration.this.register(componentClass, contracts);
            return this;
        }

        @Override
        public FeatureContext register(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
            JaxRsConfiguration.this.register(componentClass, contracts);
            return this;
        }

        @Override
        public FeatureContext register(Object component) {
            JaxRsConfiguration.this.register(component);
            return this;
        }

        @Override
        public FeatureContext register(Object component, int priority) {
            JaxRsConfiguration.this.register(component, priority);
            return this;
        }

        @Override
        public FeatureContext register(Object component, Class<?>... contracts) {
            JaxRsConfiguration.this.register(component, contracts);
            return this;
        }

        @Override
        public FeatureContext register(Object component, Map<Class<?>, Integer> contracts) {
            JaxRsConfiguration.this.register(component, contracts);
            return this;
        }
    }

    record ComponentContract(Class<?> contract, int priority) {
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
}
