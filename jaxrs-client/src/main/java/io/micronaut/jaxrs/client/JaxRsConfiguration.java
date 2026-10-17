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
import io.micronaut.core.order.OrderUtil;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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
        for (Class<?> componentClass : configuration.getClasses()) {
            register(componentClass, configuration.getContracts(componentClass));
        }
        for (Object instance : configuration.getInstances()) {
            register(instance, configuration.getContracts(instance.getClass()));
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
        components.add(new InstanceComponent(component, NO_PRIORITY, List.of()));
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
            add(new InstanceComponent(component, priority, assignable));
        }
    }

    void register(Object component, Map<Class<?>, Integer> contracts) {
        List<ComponentContract> assignable = assignable(component.getClass(), toContracts(contracts));
        if (assignable != null) {
            add(new InstanceComponent(component, NO_PRIORITY, assignable));
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
        List<ComponentContract> contracts = component.components();
        if (contracts.isEmpty() || contracts.stream().anyMatch(c -> c.contract() == Feature.class)) {
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
                MessageBodyReader<?> reader = injected(component.tryGet(MessageBodyReader.class));
                if (reader != null) {
                    if (isNotConstrainedToClient(reader.getClass())) {
                        continue;
                    }
                    built.add(new JaxRsMessageBodyReaderDefinition(
                        providedType(reader, MessageBodyReader.class),
                        new JaxRsMessageBodyReader<>(reader),
                        component.priority() == NO_PRIORITY ? JaxRsUtils.getPriorityOrder(reader) : component.priority()
                    ));
                }
                io.micronaut.http.body.MessageBodyReader<?> micronautReader = injected(component.tryGet(io.micronaut.http.body.MessageBodyReader.class));
                if (micronautReader != null) {
                    if (isNotConstrainedToClient(micronautReader.getClass())) {
                        continue;
                    }
                    if (micronautReader instanceof TypedMessageBodyReader<?> typedMessageBodyReader) {
                        Argument<?> type = typedMessageBodyReader.getType();
                        if (isNotConstrainedToClient(typedMessageBodyReader.getClass())) {
                            continue;
                        }
                        built.add(new JaxRsMessageBodyReaderDefinition(
                            type,
                            micronautReader,
                            component.priority() == NO_PRIORITY ? JaxRsUtils.getPriorityOrder(micronautReader) : component.priority()
                        ));
                    } else {
                        built.add(new JaxRsMessageBodyReaderDefinition(
                            providedType(micronautReader, io.micronaut.http.body.MessageBodyReader.class),
                            micronautReader,
                            component.priority() == NO_PRIORITY ? JaxRsUtils.getPriorityOrder(micronautReader) : component.priority()
                        ));
                    }
                }
            }
            OrderUtil.sortOrdered(built);
            found = Collections.unmodifiableList(built);
            this.readers = found;
        }
        return found;
    }

    private boolean isNotConstrainedToClient(Class<?> bodyHandler) {
        AnnotationMetadata annotationMetadata = annotationMetadataOf(bodyHandler);
        return isNotConstrainedToClient(annotationMetadata);
    }

    private boolean isNotConstrainedToClient(AnnotationMetadata annotationMetadata) {
        AnnotationValue<ConstrainedTo> constrainedTo = annotationMetadata.getAnnotation(ConstrainedTo.class);
        if (constrainedTo == null) {
            return false;
        }
        Optional<RuntimeType> runtimeType = constrainedTo.enumValue(RuntimeType.class);
        return runtimeType.isPresent() && runtimeType.get() != RuntimeType.CLIENT;
    }

    private List<JaxRsMessageBodyWriterDefinition> getWriters() {
        List<JaxRsMessageBodyWriterDefinition> found = writers;
        if (found == null) {
            List<JaxRsMessageBodyWriterDefinition> built = new ArrayList<>();
            for (JaxRsConfiguration.Component component : components) {
                MessageBodyWriter<?> writer = injected(component.tryGet(MessageBodyWriter.class));
                if (writer != null) {
                    AnnotationMetadata annotationMetadata = annotationMetadataOf(writer.getClass());
                    if (isNotConstrainedToClient(annotationMetadata)) {
                        continue;
                    }
                    built.add(new JaxRsMessageBodyWriterDefinition(
                        providedType(writer, MessageBodyWriter.class),
                        new JaxRsMessageBodyWriter<>(annotationMetadata, (MessageBodyWriter<Object>) writer),
                        component.priority() == NO_PRIORITY ? JaxRsUtils.getPriorityOrder(writer) : component.priority()
                    ));
                }
                io.micronaut.http.body.MessageBodyWriter<?> micronautWriter = injected(component.tryGet(io.micronaut.http.body.MessageBodyWriter.class));
                if (micronautWriter != null) {
                    if (micronautWriter instanceof TypedMessageBodyWriter<?> typedMessageBodyWriter) {
                        Argument<?> type = typedMessageBodyWriter.getType();
                        built.add(new JaxRsMessageBodyWriterDefinition(
                            type,
                            micronautWriter,
                            component.priority() == NO_PRIORITY ? JaxRsUtils.getPriorityOrder(micronautWriter) : component.priority()
                        ));
                    } else {
                        built.add(new JaxRsMessageBodyWriterDefinition(
                            providedType(micronautWriter, io.micronaut.http.body.MessageBodyWriter.class),
                            micronautWriter,
                            component.priority() == NO_PRIORITY ? JaxRsUtils.getPriorityOrder(micronautWriter) : component.priority()
                        ));
                    }
                }
            }
            OrderUtil.sortOrdered(built);
            found = Collections.unmodifiableList(built);
            this.writers = found;
        }
        return found;
    }

    private static AnnotationMetadata annotationMetadataOf(Class<?> type) {
        return JaxRsReflection.get().annotationMetadata(type);
    }

    /**
     * The type a provider reads or writes: the type argument of its provider interface, known with
     * reflection, else any type.
     */
    private static Argument<?> providedType(Object provider, Class<?> providerType) {
        Argument<?> argument = JaxRsReflection.get().resolveGeneric(provider.getClass(), providerType);
        return argument == null || argument.getTypeParameters().length == 0 ? Argument.OBJECT_ARGUMENT : argument.getTypeParameters()[0];
    }

    public HttpMessageEntityReader createHttpMessageEntityReader() {
        return new HttpMessageEntityReader() {

            @Override
            public <T> @Nullable T readEntity(HttpMessage<?> message, Argument<T> entityType) {
                ByteBuffer<?> byteBuffer = message.getBody(ByteBuffer.class)
                    .or(() -> message.getBody(byte[].class).map(ByteArrayByteBuffer::new))
                    .orElse(null);
                if (byteBuffer != null) {
                    List<BeanRegistration<ReaderInterceptor>> readerInterceptors = getReaderInterceptors();
                    io.micronaut.http.MediaType mediaType = message.getContentType().orElse(MediaType.ALL_TYPE);
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

                        }.intercept(entityType, mediaType, headers, byteBuffer.toInputStream());
                    }
                }
                return super.readEntity(message, entityType);
            }
        };
    }

    <T> io.micronaut.http.body.@Nullable MessageBodyReader<T> findReader(Argument<T> argument,
                                                                                @Nullable MediaType mediaType) {
        // First, the JAX-RS reader in the order of the providers, then of their priority
        io.micronaut.http.body.MessageBodyReader<T> best = null;
        long bestRank = Long.MAX_VALUE;
        for (JaxRsMessageBodyReaderDefinition readerDer : getReaders()) {
            io.micronaut.http.body.MessageBodyReader<T> reader = (io.micronaut.http.body.MessageBodyReader<T>) readerDer.messageBodyReader();
            if (reader instanceof JaxRsMessageBodyReader<?> jaxRsReader
                && readerDer.type().isAssignableFrom(argument.getType()) && reader.isReadable(argument, mediaType)) {
                long rank = rank(readerDer.type().getType(), jaxRsReader.delegate(), Consumes.class, argument.getType(), mediaType);
                if (rank < bestRank) {
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
            if (readerDer.type().isAssignableFrom(argument.getType()) && reader.isReadable(argument, mediaType)) {
                return reader;
            }
        }
        return null;
    }

    <T> void writeBody(MutableHttpMessage<?> mutableHttpMessage, Argument<T> bodyArgument, @Nullable T body) {
        writeBody(mutableHttpMessage, bodyArgument, body, false);
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
     * @param <T>                The type of the entity
     */
    @SuppressWarnings("unchecked")
    <T> void writeBody(MutableHttpMessage<?> mutableHttpMessage, Argument<T> bodyArgument, @Nullable T body, boolean bytes) {
        if (body == null) {
            return;
        }
        if (body instanceof GenericEntity<?> genericEntity) {
            // the entity, written as its generic type
            writeBody(mutableHttpMessage, (Argument<Object>) JaxRsArgumentUtil.from(genericEntity), genericEntity.getEntity(), bytes);
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

            }.intercept(bodyArgument, mediaType, new JaxRsWriterInterceptorContextState.ClassicState(mutableHttpMessage.getHeaders(), body, outputStream));
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
                && writerDef.type().isAssignableFrom(argument.getType()) && writer.isWriteable(argument, mediaType)) {
                long rank = rank(writerDef.type().getType(), jaxRsWriter.delegate(), Produces.class, argument.getType(), mediaType);
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
            if (writerDef.type().isAssignableFrom(argument.getType()) && writer.isWriteable(argument, mediaType)) {
                return writer;
            }
        }
        return null;
    }

    /**
     * The order of a JAX-RS provider, like the one of the server (JAX-RS 4.2.2, step 4): the nearest
     * type first, then the most specific media type, then the providers of the application before
     * the standard ones. Equal ones keep the order of their priority.
     */
    private static long rank(Class<?> provided, Object provider, Class<? extends Annotation> declaring, Class<?> type,
                             @Nullable MediaType mediaType) {
        String[] declared = annotationMetadataOf(provider.getClass()).stringValues(declaring);
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
        var valuesWithPriority = new ArrayList<Map.Entry<T, Integer>>();
        for (JaxRsConfiguration.Component component : components) {
            List<ComponentContract> contracts = component.components();
            ComponentContract contract = contracts.stream().filter(c -> c.contract().equals(type)).findFirst().orElse(null);
            if (!contracts.isEmpty() && contract == null) {
                // registered for other contracts only
                continue;
            }
            T instance = injected(component.tryGet(type));
            if (instance != null) {
                // the priority of the registration, else of the contract, else of @Priority
                int priority = component.priority() != NO_PRIORITY ? component.priority()
                    : contract != null && contract.priority() != NO_PRIORITY ? contract.priority()
                    : JaxRsUtils.getPriorityOrder(instance);
                valuesWithPriority.add(Map.entry(instance, priority));
            }
        }
        valuesWithPriority.sort(Comparator.comparingInt(Map.Entry::getValue));
        return valuesWithPriority.stream()
            .map(entry -> JaxRsRouteInterceptors.registration(entry.getKey(), entry.getValue()))
            .toList();
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
        JaxRsReflection.get().injectFields(instance, Context.class, type -> type == Providers.class ? providers
            : type == Configuration.class ? this
            : null);
        return instance;
    }

    /**
     * The providers of the client, see {@link Providers}.
     */
    private final class ClientProviders implements Providers {

        @Override
        @SuppressWarnings("unchecked")
        public <T> @Nullable MessageBodyReader<T> getMessageBodyReader(Class<T> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
            // selected like the readers of the entities (JAX-RS 4.2.1)
            MediaType micronautMediaType = JaxRsUtils.convert(mediaType);
            MessageBodyReader<T> best = null;
            long bestRank = Long.MAX_VALUE;
            for (JaxRsMessageBodyReaderDefinition definition : getReaders()) {
                if (definition.messageBodyReader() instanceof JaxRsMessageBodyReader<?> jaxRsReader
                    && definition.type().isAssignableFrom(type)
                    && jaxRsReader.delegate().isReadable(type, genericType, annotations, mediaType)) {
                    long rank = rank(definition.type().getType(), jaxRsReader.delegate(), Consumes.class, type, micronautMediaType);
                    if (rank < bestRank) {
                        best = (MessageBodyReader<T>) jaxRsReader.delegate();
                        bestRank = rank;
                    }
                }
            }
            return best;
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
                    && definition.type().isAssignableFrom(type)
                    && jaxRsWriter.delegate().isWriteable(type, genericType, annotations, mediaType)) {
                    long rank = rank(definition.type().getType(), jaxRsWriter.delegate(), Produces.class, type, micronautMediaType);
                    if (rank < bestRank) {
                        best = (MessageBodyWriter<T>) jaxRsWriter.delegate();
                        bestRank = rank;
                    }
                }
            }
            return best;
        }

        @Override
        public <T extends Throwable> @Nullable ExceptionMapper<T> getExceptionMapper(Class<T> type) {
            // exception mappers are providers of the server
            return null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> @Nullable ContextResolver<T> getContextResolver(Class<T> contextType, jakarta.ws.rs.core.MediaType mediaType) {
            // the resolvers of the context type, by their type argument (JAX-RS 4.3): a resolver is
            // asked for the context of an entity type, and may have none for it
            List<ContextResolver<T>> resolvers = new ArrayList<>();
            for (Component component : components) {
                ContextResolver<T> resolver = component.tryGet(ContextResolver.class);
                if (resolver != null) {
                    Argument<?> resolved = JaxRsReflection.get().resolveGeneric(resolver.getClass(), ContextResolver.class);
                    Argument<?>[] arguments = resolved == null ? new Argument<?>[0] : resolved.getTypeParameters();
                    if (arguments.length == 0 || contextType.isAssignableFrom(arguments[0].getType())) {
                        resolvers.add(injected(resolver));
                    }
                }
            }
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
    }

    sealed interface Component {

        boolean is(Class<?> type);

        Class<?> componentClass();

        <T> @Nullable T tryGet(Class<T> type);

        int priority();

        List<ComponentContract> components();

    }

    record InstanceComponent(Object component, int priority,
                             List<ComponentContract> components) implements Component {
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
        private volatile @Nullable Object instance;

        ClassComponent(Class<?> componentClass, int priority, List<ComponentContract> components) {
            this.componentClass = componentClass;
            this.priority = priority;
            this.components = components;
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
}
