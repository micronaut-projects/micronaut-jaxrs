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

import io.micronaut.context.AnnotationReflectionUtils;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.beans.BeanMethod;
import io.micronaut.core.beans.BeanWriteProperty;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.order.OrderUtil;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.Headers;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMessage;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpMessage;
import io.micronaut.http.body.TypedMessageBodyReader;
import io.micronaut.http.body.TypedMessageBodyWriter;
import io.micronaut.http.client.multipart.MultipartBody;
import io.micronaut.jaxrs.common.ByteArrayByteBuffer;
import io.micronaut.jaxrs.common.HttpMessageEntityReader;
import io.micronaut.jaxrs.common.JaxRsInterceptedRead;
import io.micronaut.jaxrs.common.JaxRsInterceptedWrite;
import io.micronaut.jaxrs.common.JaxRsIOException;
import io.micronaut.jaxrs.common.JaxRsMessageBodyReader;
import io.micronaut.jaxrs.common.JaxRsMessageBodyReaderDefinition;
import io.micronaut.jaxrs.common.JaxRsMessageBodyWriter;
import io.micronaut.jaxrs.common.JaxRsUtils;
import io.micronaut.jaxrs.common.JaxRsWriterInterceptorContextState;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.client.RxInvoker;
import jakarta.ws.rs.client.RxInvokerProvider;
import jakarta.ws.rs.client.SyncInvoker;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.WriterInterceptor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
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

    private static final Logger LOG = LoggerFactory.getLogger(JaxRsConfiguration.class);
    private static final List<JaxRsClientComponentInstantiator> CLIENT_COMPONENT_INSTANTIATORS = ServiceLoader
        .load(JaxRsClientComponentInstantiator.class)
        .stream()
        .map(ServiceLoader.Provider::get)
        .toList();
    private static final List<Class<?>> CLIENT_CONTRACTS = List.of(
        Feature.class,
        ClientRequestFilter.class,
        ClientResponseFilter.class,
        ContextResolver.class,
        MessageBodyReader.class,
        MessageBodyWriter.class,
        ReaderInterceptor.class,
        WriterInterceptor.class,
        RxInvokerProvider.class
    );

    private final Map<String, Object> properties;
    private final List<Component> components;
    private @Nullable ExecutorService executorService;
    private @Nullable ScheduledExecutorService scheduledExecutorService;

    private List<ReaderInterceptor> readerInterceptors;
    private List<WriterInterceptor> writerInterceptors;
    private List<JaxRsMessageBodyReaderDefinition> readers;
    private List<JaxRsMessageBodyWriterDefinition> writers;
    private List<ClientRequestFilter> requestFilters;
    private List<ClientResponseFilter> responseFilters;
    private List<ContextResolverDefinition> contextResolvers;
    private List<RxInvokerProvider<?>> rxInvokerProviders;
    private @Nullable Providers providers;

    public JaxRsConfiguration() {
        this(new LinkedHashMap<>(), new ArrayList<>());
    }

    public JaxRsConfiguration(Map<String, Object> properties, List<Component> components) {
        this.properties = properties;
        this.components = components;
    }

    JaxRsConfiguration copy() {
        JaxRsConfiguration copy = new JaxRsConfiguration(new LinkedHashMap<>(properties), components.stream().map(Component::copy).collect(Collectors.toCollection(ArrayList::new)));
        copy.executorService = executorService;
        copy.scheduledExecutorService = scheduledExecutorService;
        return copy;
    }

    void setExecutorService(@Nullable ExecutorService executorService) {
        this.executorService = executorService;
    }

    @Nullable ExecutorService getExecutorService() {
        return executorService;
    }

    void setScheduledExecutorService(@Nullable ScheduledExecutorService scheduledExecutorService) {
        this.scheduledExecutorService = scheduledExecutorService;
    }

    @Nullable ScheduledExecutorService getScheduledExecutorService() {
        return scheduledExecutorService;
    }

    @Nullable Path tempDirectory() {
        return JaxRsUtils.configuredTempDirectory(properties.get(JaxRsUtils.TEMP_DIRECTORY_PROPERTY));
    }

    public void addProperty(String name, Object value) {
        properties.put(name, value);
    }

    void register(Class<?> componentClass) {
        add(new ClassComponent(componentClass, 0, List.of()));
    }

    void register(Class<?> componentClass, int priority) {
        add(new ClassComponent(componentClass, priority, List.of()));
    }

    void register(Class<?> componentClass, Class<?>... contracts) {
        if (contracts == null || contracts.length == 0) {
            return;
        }
        register(componentClass, 0, contracts);
    }

    private List<ComponentContract> toContracts(Class<?> componentClass, Class<?>[] contracts) {
        return Arrays.stream(contracts)
            .filter(contract -> contract != null && contract.isAssignableFrom(componentClass))
            .map(c -> new ComponentContract(c, 0))
            .toList();
    }

    void register(Class<?> componentClass, int priority, Class<?>... contracts) {
        List<ComponentContract> componentContracts = toContracts(componentClass, contracts);
        if (!componentContracts.isEmpty()) {
            add(new ClassComponent(componentClass, priority, componentContracts));
        }
    }

    void register(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
        if (contracts == null || contracts.isEmpty()) {
            return;
        }
        List<ComponentContract> componentContracts = toContracts(componentClass, contracts);
        if (!componentContracts.isEmpty()) {
            add(new ClassComponent(componentClass, 0, componentContracts));
        }
    }

    private List<ComponentContract> toContracts(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
        return contracts.entrySet()
            .stream()
            .filter(e -> e.getKey() != null && e.getKey().isAssignableFrom(componentClass))
            .map(e -> new ComponentContract(e.getKey(), e.getValue() == null ? 0 : e.getValue()))
            .toList();
    }

    void register(Object component) {
        add(new InstanceComponent(component, 0, List.of()));
    }

    void register(Object component, int priority) {
        add(new InstanceComponent(component, priority, List.of()));
    }

    void register(Object component, Class<?>... contracts) {
        if (contracts == null || contracts.length == 0) {
            return;
        }
        register(component, 0, contracts);
    }

    void register(Object component, int priority, Class<?>... contracts) {
        List<ComponentContract> componentContracts = toContracts(component.getClass(), contracts);
        if (!componentContracts.isEmpty()) {
            add(new InstanceComponent(component, priority, componentContracts));
        }
    }

    void register(Object component, Map<Class<?>, Integer> contracts) {
        if (contracts == null || contracts.isEmpty()) {
            return;
        }
        List<ComponentContract> componentContracts = toContracts(component.getClass(), contracts);
        if (!componentContracts.isEmpty()) {
            add(new InstanceComponent(component, 0, componentContracts));
        }
    }

    private void add(Component component) {
        components.add(component);
        readerInterceptors = null;
        writerInterceptors = null;
        readers = null;
        writers = null;
        requestFilters = null;
        responseFilters = null;
        contextResolvers = null;
        rxInvokerProviders = null;
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
    public Object getProperty(String name) {
        return properties.get(name);
    }

    @Override
    public Collection<String> getPropertyNames() {
        return properties.keySet();
    }

    @Override
    public boolean isEnabled(Feature feature) {
        return false;
    }

    @Override
    public boolean isEnabled(Class<? extends Feature> featureClass) {
        return false;
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
        Map<Class<?>, Integer> result = new LinkedHashMap<>();
        for (Component component : components) {
            if (!component.is(componentClass)) {
                continue;
            }
            if (component.contracts().isEmpty()) {
                for (Class<?> contract : CLIENT_CONTRACTS) {
                    if (contract.isAssignableFrom(componentClass)) {
                        result.putIfAbsent(contract, component.priority());
                    }
                }
            } else {
                for (ComponentContract contract : component.contracts()) {
                    result.putIfAbsent(contract.contract(), contract.priority());
                }
            }
        }
        return result;
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

    private List<ReaderInterceptor> getReaderInterceptors() {
        if (readerInterceptors == null) {
            readerInterceptors = getComponentOfType(ReaderInterceptor.class);
        }
        return readerInterceptors;
    }

    private List<WriterInterceptor> getWriterInterceptors() {
        if (writerInterceptors == null) {
            writerInterceptors = getComponentOfType(WriterInterceptor.class);
        }
        return writerInterceptors;
    }

    private List<JaxRsMessageBodyReaderDefinition> getReaders() {
        if (readers == null) {
            readers = new ArrayList<>();
            for (JaxRsConfiguration.Component component : components) {
                MessageBodyReader<?> reader = component.tryGet(MessageBodyReader.class, this);
                if (reader != null) {
                    if (isNotConstrainedToClient(reader.getClass())) {
                        continue;
                    }
                    readers.add(new JaxRsMessageBodyReaderDefinition(
                        AnnotationReflectionUtils.resolveGenericToArgument(reader.getClass(), MessageBodyReader.class).getTypeParameters()[0],
                        new JaxRsMessageBodyReader<>(reader),
                        component.priority(MessageBodyReader.class, reader)
                    ));
                }
                io.micronaut.http.body.MessageBodyReader<?> micronautReader = component.tryGet(io.micronaut.http.body.MessageBodyReader.class, this);
                if (micronautReader != null) {
                    if (isNotConstrainedToClient(micronautReader.getClass())) {
                        continue;
                    }
                    if (micronautReader instanceof TypedMessageBodyReader<?> typedMessageBodyReader) {
                        Argument<?> type = typedMessageBodyReader.getType();
                        if (isNotConstrainedToClient(typedMessageBodyReader.getClass())) {
                            continue;
                        }
                        readers.add(new JaxRsMessageBodyReaderDefinition(
                            type,
                            micronautReader,
                            component.priority(io.micronaut.http.body.MessageBodyReader.class, micronautReader)
                        ));
                    } else {
                        readers.add(new JaxRsMessageBodyReaderDefinition(
                            AnnotationReflectionUtils.resolveGenericToArgument(micronautReader.getClass(), io.micronaut.http.body.MessageBodyReader.class).getTypeParameters()[0],
                            micronautReader,
                            component.priority(io.micronaut.http.body.MessageBodyReader.class, micronautReader)
                        ));
                    }
                }
            }
            OrderUtil.sortOrdered(readers);
        }
        return readers;
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    <T> @Nullable ContextResolver<T> getContextResolver(Class<T> contextType, jakarta.ws.rs.core.MediaType mediaType) {
        ContextResolverDefinition selected = null;
        int selectedMediaScore = -1;
        for (ContextResolverDefinition definition : getContextResolvers()) {
            if (!definition.contextType().getType().isAssignableFrom(contextType)) {
                continue;
            }
            int mediaScore = mediaTypeScore(definition.annotationMetadata(), mediaType);
            if (mediaScore < 0) {
                continue;
            }
            if (selected == null
                || mediaScore > selectedMediaScore
                || mediaScore == selectedMediaScore && definition.priority() < selected.priority()) {
                selected = definition;
                selectedMediaScore = mediaScore;
            }
        }
        return selected == null ? null : (ContextResolver<T>) selected.contextResolver();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<ContextResolverDefinition> getContextResolvers() {
        if (contextResolvers == null) {
            contextResolvers = new ArrayList<>();
            for (JaxRsConfiguration.Component component : components) {
                ContextResolver<?> resolver = component.tryGet(ContextResolver.class, this);
                if (resolver == null) {
                    continue;
                }
                AnnotationMetadata annotationMetadata = annotationMetadataOf(resolver.getClass());
                if (isNotConstrainedToClient(annotationMetadata)) {
                    continue;
                }
                contextResolvers.add(new ContextResolverDefinition(
                    AnnotationReflectionUtils.resolveGenericToArgument(resolver.getClass(), ContextResolver.class).getTypeParameters()[0],
                    resolver,
                    annotationMetadata,
                    component.priority(ContextResolver.class, resolver)
                ));
            }
            contextResolvers.sort(Comparator.comparingInt(ContextResolverDefinition::priority));
        }
        return contextResolvers;
    }

    private static int mediaTypeScore(AnnotationMetadata annotationMetadata, jakarta.ws.rs.core.MediaType requestedMediaType) {
        String[] producedMediaTypes = annotationMetadata.stringValues(jakarta.ws.rs.Produces.class);
        if (producedMediaTypes.length == 0) {
            producedMediaTypes = annotationMetadata.stringValues(io.micronaut.http.annotation.Produces.class);
        }
        if (producedMediaTypes.length == 0) {
            return mediaTypeScore(jakarta.ws.rs.core.MediaType.WILDCARD_TYPE, requestedMediaType);
        }
        int score = -1;
        for (String producedMediaType : producedMediaTypes) {
            score = Math.max(score, mediaTypeScore(jakarta.ws.rs.core.MediaType.valueOf(producedMediaType), requestedMediaType));
        }
        return score;
    }

    private static int mediaTypeScore(jakarta.ws.rs.core.MediaType producedMediaType,
                                      jakarta.ws.rs.core.MediaType requestedMediaType) {
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

    private List<JaxRsMessageBodyWriterDefinition> getWriters() {
        if (writers == null) {
            writers = new ArrayList<>();
            for (JaxRsConfiguration.Component component : components) {
                MessageBodyWriter<?> writer = component.tryGet(MessageBodyWriter.class, this);
                if (writer != null) {
                    AnnotationMetadata annotationMetadata = annotationMetadataOf(writer.getClass());
                    if (isNotConstrainedToClient(annotationMetadata)) {
                        continue;
                    }
                    Argument<MessageBodyWriter> messageBodyWriterArgument = AnnotationReflectionUtils.resolveGenericToArgument(writer.getClass(), MessageBodyWriter.class);
                    writers.add(new JaxRsMessageBodyWriterDefinition(
                        messageBodyWriterArgument.getTypeParameters()[0],
                        new JaxRsMessageBodyWriter<>(annotationMetadata, (MessageBodyWriter<Object>) writer),
                        component.priority(MessageBodyWriter.class, writer)
                    ));
                }
                io.micronaut.http.body.MessageBodyWriter<?> micronautWriter = component.tryGet(io.micronaut.http.body.MessageBodyWriter.class, this);
                if (micronautWriter != null) {
                    if (micronautWriter instanceof TypedMessageBodyWriter<?> typedMessageBodyWriter) {
                        Argument<?> type = typedMessageBodyWriter.getType();
                        writers.add(new JaxRsMessageBodyWriterDefinition(
                            type,
                            micronautWriter,
                            component.priority(io.micronaut.http.body.MessageBodyWriter.class, micronautWriter)
                        ));
                    } else {
                        writers.add(new JaxRsMessageBodyWriterDefinition(
                            AnnotationReflectionUtils.resolveGenericToArgument(micronautWriter.getClass(), io.micronaut.http.body.MessageBodyWriter.class).getTypeParameters()[0],
                            micronautWriter,
                            component.priority(io.micronaut.http.body.MessageBodyWriter.class, micronautWriter)
                        ));
                    }
                }
            }
            OrderUtil.sortOrdered(writers);
        }
        return writers;
    }

    private static AnnotationMetadata annotationMetadataOf(Class<?> componentClass) {
        @SuppressWarnings("unchecked")
        Optional<BeanIntrospection<Object>> introspection = BeanIntrospector.SHARED.findIntrospection((Class<Object>) componentClass);
        if (introspection.isPresent()) {
            return introspection.get().getAnnotationMetadata();
        }
        for (JaxRsClientComponentInstantiator instantiator : CLIENT_COMPONENT_INSTANTIATORS) {
            Optional<AnnotationMetadata> annotationMetadata = instantiator.annotationMetadata(componentClass);
            if (annotationMetadata.isPresent()) {
                return annotationMetadata.get();
            }
        }
        return AnnotationMetadata.EMPTY_METADATA;
    }

    public HttpMessageEntityReader createHttpMessageEntityReader() {
        return new HttpMessageEntityReader() {

            @Override
            public <T> T readEntity(HttpMessage<?> message, Argument<T> entityType) {
                Object body = message instanceof HttpResponse<?> response ? response.body() : message.getBody().orElse(null);
                ByteBuffer<?> byteBuffer = null;
                if (body instanceof ByteBuffer<?> buffer) {
                    byteBuffer = buffer;
                } else if (body instanceof byte[] bytes) {
                    byteBuffer = new ByteArrayByteBuffer(bytes);
                }
                if (byteBuffer != null) {
                    List<ReaderInterceptor> readerInterceptors = getReaderInterceptors();
                    io.micronaut.http.MediaType mediaType = message.getContentType().orElse(MediaType.ALL_TYPE);
                    HttpHeaders headers = message.getHeaders();
                    if (readerInterceptors.isEmpty()) {
                        io.micronaut.http.body.MessageBodyReader<T> reader = findReader(entityType, mediaType);
                        if (reader != null) {
                            try {
                                return reader.read(entityType, mediaType, headers, byteBuffer);
                            } catch (JaxRsIOException e) {
                                if (HttpMessageEntityReader.isNoContentException(e.getCause())) {
                                    throw HttpMessageEntityReader.noContentProcessingException();
                                }
                                throw e;
                            }
                        }
                    } else {
                        return new JaxRsInterceptedRead<T>(readerInterceptors) {

                            @Override
                            protected T readFromAfterInterception(Argument<Object> type, MediaType mediaType, Headers httpHeaders, InputStream inputStream) {
                                io.micronaut.http.body.MessageBodyReader<Object> reader = findReader(type, mediaType);
                                if (reader != null) {
                                    try {
                                        return (T) reader.read(type, mediaType, headers, inputStream);
                                    } catch (JaxRsIOException e) {
                                        if (HttpMessageEntityReader.isNoContentException(e.getCause())) {
                                            throw HttpMessageEntityReader.noContentProcessingException();
                                        }
                                        throw e;
                                    }
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

    private <T> io.micronaut.http.body.@Nullable MessageBodyReader<T> findReader(Argument<T> argument,
                                                                                MediaType mediaType) {
        // First, let's try to find JaxRs reader
        for (JaxRsMessageBodyReaderDefinition readerDer : getReaders()) {
            io.micronaut.http.body.MessageBodyReader<T> reader = (io.micronaut.http.body.MessageBodyReader<T>) readerDer.messageBodyReader();
            if (reader instanceof JaxRsMessageBodyReader<?>) {
                if (readerDer.type().isAssignableFrom(argument.getType()) && reader.isReadable(argument, mediaType)) {
                    return reader;
                }
            }
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

    <T> void writeBody(MutableHttpMessage<?> mutableHttpMessage, Argument<T> bodyArgument, T body) {
        if (body == null) {
            return;
        }
        io.micronaut.http.MediaType mediaType = mutableHttpMessage.getContentType().orElse(MediaType.ALL_TYPE);
        if (writeMultipartBody(mutableHttpMessage, mediaType, body)) {
            return;
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        final AtomicBoolean written = new AtomicBoolean(false);
        List<WriterInterceptor> writerInterceptors = getWriterInterceptors();
        if (writerInterceptors.isEmpty()) {
            io.micronaut.http.body.MessageBodyWriter<T> writer = findWriter(bodyArgument, mediaType);
            if (writer != null) {
                writer.writeTo(bodyArgument, mediaType, body, mutableHttpMessage.getHeaders(), outputStream);
                written.set(true);
            }
        } else {
            new JaxRsInterceptedWrite<T, JaxRsWriterInterceptorContextState.ClassicState>(writerInterceptors) {

                @Override
                protected void writeToAfterInterception(Argument<Object> argument, MediaType mediaType, JaxRsWriterInterceptorContextState.ClassicState state) {
                    io.micronaut.http.body.MessageBodyWriter<Object> writer = findWriter(argument, mediaType);
                    if (writer != null) {
                        writer.writeTo(argument, mediaType, state.getEntity(), mutableHttpMessage.getHeaders(), state.getOutputStream());
                        written.set(true);
                    }
                }

            }.intercept(bodyArgument, mediaType, new JaxRsWriterInterceptorContextState.ClassicState(mutableHttpMessage.getHeaders(), body, outputStream));
        }

        if (written.get()) {
            byte[] bytes = outputStream.toByteArray();
            if (mediaType.equals(MediaType.APPLICATION_FORM_URLENCODED_TYPE)) {
                mutableHttpMessage.body(new String(bytes, StandardCharsets.UTF_8));
            } else {
                mutableHttpMessage.body(bytes);
            }
        } else if (!getWriterInterceptors().isEmpty()) {
            throw new IllegalStateException("Unknown entity type " + bodyArgument.getType());
        } else {
            mutableHttpMessage.body(body);
        }
    }

    private static <T> boolean writeMultipartBody(MutableHttpMessage<?> mutableHttpMessage, MediaType mediaType, T body) {
        if (!mediaType.equals(MediaType.MULTIPART_FORM_DATA_TYPE) || !(body instanceof List<?> values) || !isEntityPartList(values)) {
            return false;
        }
        MultipartBody.Builder builder = MultipartBody.builder();
        for (Object value : values) {
            addPart(builder, (EntityPart) value);
        }
        mutableHttpMessage.body(builder.build());
        return true;
    }

    private static boolean isEntityPartList(List<?> values) {
        for (Object value : values) {
            if (!(value instanceof EntityPart)) {
                return false;
            }
        }
        return true;
    }

    private static void addPart(MultipartBody.Builder builder, EntityPart part) {
        MediaType mediaType = JaxRsUtils.convert(part.getMediaType());
        try {
            Optional<String> fileName = part.getFileName();
            if (fileName.isPresent()) {
                builder.addPart(part.getName(), fileName.get(), mediaType, part.getContent(byte[].class));
            } else if (mediaType.equals(MediaType.TEXT_PLAIN_TYPE)) {
                builder.addPart(part.getName(), part.getContent(String.class));
            } else {
                // Micronaut's client MultipartBody requires a filename for byte[] parts.
                builder.addPart(part.getName(), part.getName(), mediaType, part.getContent(byte[].class));
            }
        } catch (IOException e) {
            throw new JaxRsIOException("Cannot read multipart entity part", e);
        }
    }

    private <T> io.micronaut.http.body.@Nullable MessageBodyWriter<T> findWriter(Argument<T> argument,
                                                                                MediaType mediaType) {
        // First, let's try to find JaxRs writer
        for (JaxRsMessageBodyWriterDefinition writerDef : getWriters()) {
            io.micronaut.http.body.MessageBodyWriter<T> writer = (io.micronaut.http.body.MessageBodyWriter<T>) writerDef.messageBodyWriter();
            if (writer instanceof JaxRsMessageBodyWriter<?>) {
                if (writerDef.type().isAssignableFrom(argument.getType()) && writer.isWriteable(argument, mediaType)) {
                    return writer;
                }
            }
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

    public List<ClientRequestFilter> getRequestFilters() {
        if (requestFilters == null) {
            requestFilters = getComponentOfType(ClientRequestFilter.class);
        }
        return requestFilters;
    }

    public List<ClientResponseFilter> getResponseFilters() {
        if (responseFilters == null) {
            responseFilters = getComponentOfType(ClientResponseFilter.class);
        }
        return responseFilters;
    }

    <T extends RxInvoker> T createRxInvoker(Class<T> type, SyncInvoker syncInvoker) {
        for (RxInvokerProvider<?> provider : getRxInvokerProviders()) {
            if (provider.isProviderFor(type)) {
                return type.cast(provider.getRxInvoker(syncInvoker, executorService));
            }
        }
        throw new IllegalStateException("No RxInvokerProvider registered for " + type.getName());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<RxInvokerProvider<?>> getRxInvokerProviders() {
        if (rxInvokerProviders == null) {
            rxInvokerProviders = (List) getComponentOfType(RxInvokerProvider.class);
        }
        return rxInvokerProviders;
    }

    private <T> List<T> getComponentOfType(Class<T> type) {
        var valuesWithPriority = new ArrayList<Map.Entry<T, Integer>>();
        for (JaxRsConfiguration.Component component : components) {
            T instance = component.tryGet(type, this);
            if (instance != null) {
                valuesWithPriority.add(Map.entry(instance, component.priority(type, instance)));
            }
        }
        valuesWithPriority.sort(Comparator.comparingInt(Map.Entry::getValue));
        return valuesWithPriority.stream().map(Map.Entry::getKey).collect(Collectors.toList());
    }

    @SuppressWarnings({"unchecked"})
    private <T> @Nullable MessageBodyReader<T> getMessageBodyReader(Class<T> type,
                                                                    Type genericType,
                                                                    Annotation[] annotations,
                                                                    jakarta.ws.rs.core.MediaType mediaType) {
        List<Map.Entry<MessageBodyReader<?>, Integer>> candidates = new ArrayList<>();
        for (JaxRsConfiguration.Component component : components) {
            MessageBodyReader<?> reader = component.tryGet(MessageBodyReader.class, this);
            if (reader != null && !isNotConstrainedToClient(reader.getClass())) {
                candidates.add(Map.entry(reader, component.priority(MessageBodyReader.class, reader)));
            }
        }
        candidates.sort(Comparator.comparingInt(Map.Entry::getValue));
        for (Map.Entry<MessageBodyReader<?>, Integer> candidate : candidates) {
            MessageBodyReader<?> reader = candidate.getKey();
            if (reader.isReadable(type, genericType, annotations, mediaType)) {
                return (MessageBodyReader<T>) reader;
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked"})
    private <T> @Nullable MessageBodyWriter<T> getMessageBodyWriter(Class<T> type,
                                                                    Type genericType,
                                                                    Annotation[] annotations,
                                                                    jakarta.ws.rs.core.MediaType mediaType) {
        List<Map.Entry<MessageBodyWriter<?>, Integer>> candidates = new ArrayList<>();
        for (JaxRsConfiguration.Component component : components) {
            MessageBodyWriter<?> writer = component.tryGet(MessageBodyWriter.class, this);
            if (writer != null && !isNotConstrainedToClient(writer.getClass())) {
                candidates.add(Map.entry(writer, component.priority(MessageBodyWriter.class, writer)));
            }
        }
        candidates.sort(Comparator.comparingInt(Map.Entry::getValue));
        for (Map.Entry<MessageBodyWriter<?>, Integer> candidate : candidates) {
            MessageBodyWriter<?> writer = candidate.getKey();
            if (writer.isWriteable(type, genericType, annotations, mediaType)) {
                return (MessageBodyWriter<T>) writer;
            }
        }
        return null;
    }

    private Providers getProviders() {
        Providers resolved = providers;
        if (resolved == null) {
            resolved = new JaxRsClientProviders(this);
            providers = resolved;
        }
        return resolved;
    }

    private @Nullable Object contextValue(Class<?> type) {
        if (type != Object.class && type.isInstance(this)) {
            return this;
        }
        Providers resolvedProviders = getProviders();
        if (type != Object.class && type.isInstance(resolvedProviders)) {
            return resolvedProviders;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Optional<Object> instantiateIntrospected(Class<?> componentClass) {
        Optional<BeanIntrospection<Object>> introspection = BeanIntrospector.SHARED.findIntrospection((Class<Object>) componentClass);
        if (introspection.isEmpty()) {
            return Optional.empty();
        }
        // Prefer Micronaut introspections for provider construction so the
        // default client remains reflection-free. The reflection module supplies
        // a service-loaded fallback for TCK-only classes that cannot be introspected.
        Object instance = introspection.get().instantiate();
        injectIntrospectedContext(instance, introspection.get());
        return Optional.of(instance);
    }

    private void injectIntrospectedContext(Object instance, BeanIntrospection<Object> introspection) {
        for (BeanWriteProperty<Object, Object> property : introspection.getBeanWriteProperties()) {
            if (property.isAnnotationPresent(Context.class) || property.asArgument().isAnnotationPresent(Context.class)) {
                Object value = contextValue(property.getType());
                if (value != null) {
                    property.set(instance, value);
                }
            }
        }
        for (BeanMethod<Object, Object> method : introspection.getBeanMethods()) {
            if (method.getArguments().length == 0) {
                continue;
            }
            // Jakarta REST permits @Context on either the setter method or each
            // parameter. Partial context injection is not valid, so skip methods
            // unless every argument can be resolved.
            Object[] values = contextValues(method);
            if (values.length > 0) {
                method.invoke(instance, values);
            }
        }
    }

    private Object[] contextValues(BeanMethod<Object, Object> method) {
        Argument<?>[] arguments = method.getArguments();
        boolean methodContext = method.isAnnotationPresent(Context.class);
        Object[] values = new Object[arguments.length];
        for (int i = 0; i < values.length; i++) {
            Argument<?> argument = arguments[i];
            if (!methodContext && !argument.isAnnotationPresent(Context.class)) {
                return new Object[0];
            }
            Object value = contextValue(argument.getType());
            if (value == null) {
                return new Object[0];
            }
            values[i] = value;
        }
        return values;
    }

    sealed interface Component {

        boolean is(Class<?> type);

        <T> T tryGet(Class<T> type, JaxRsConfiguration configuration);

        int priority();

        List<ComponentContract> contracts();

        Component copy();

        default boolean supports(Class<?> type) {
            return contracts().isEmpty() || contracts().stream().anyMatch(contract -> contract.matches(type));
        }

        default int priority(Class<?> type, Object instance) {
            for (ComponentContract contract : contracts()) {
                if (contract.matches(type) && contract.priority() != 0) {
                    return contract.priority();
                }
            }
            return priority() == 0 ? JaxRsUtils.getPriorityOrder(instance) : priority();
        }

    }

    record InstanceComponent(Object component, int priority,
                             List<ComponentContract> contracts) implements Component {
        @Override
        public <T> T tryGet(Class<T> type, JaxRsConfiguration configuration) {
            if (type.isInstance(component) && supports(type)) {
                return (T) component;
            }
            return null;
        }

        @Override
        public boolean is(Class<?> type) {
            return type.equals(component.getClass());
        }

        @Override
        public Component copy() {
            return this;
        }
    }

    static final class ClassComponent implements Component {
        private final Class<?> componentClass;
        private final int priority;
        private final List<ComponentContract> contracts;
        private volatile @Nullable Object instance;

        ClassComponent(Class<?> componentClass, int priority, List<ComponentContract> contracts) {
            this.componentClass = componentClass;
            this.priority = priority;
            this.contracts = contracts;
        }

        @Override
        public <T> T tryGet(Class<T> type, JaxRsConfiguration configuration) {
            if (type.isAssignableFrom(componentClass) && supports(type)) {
                return (T) instance(configuration);
            }
            return null;
        }

        @Override
        public boolean is(Class<?> type) {
            return type.equals(componentClass);
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public List<ComponentContract> contracts() {
            return contracts;
        }

        @Override
        public Component copy() {
            return new ClassComponent(componentClass, priority, contracts);
        }

        private Object instance(JaxRsConfiguration configuration) {
            Object resolved = instance;
            if (resolved == null) {
                synchronized (this) {
                    resolved = instance;
                    if (resolved == null) {
                        resolved = initialize(componentClass, configuration);
                        instance = resolved;
                    }
                }
            }
            return resolved;
        }

        @SuppressWarnings("unchecked")
        private static <T> @Nullable T initialize(Class<?> clazz, JaxRsConfiguration configuration) {
            Optional<Object> introspected = configuration.instantiateIntrospected(clazz);
            if (introspected.isPresent()) {
                return (T) introspected.get();
            }
            // Optional modules, such as jaxrs-reflection, are deliberately
            // service-loaded after introspection so they do not affect the normal
            // compile-time optimized path.
            for (JaxRsClientComponentInstantiator instantiator : CLIENT_COMPONENT_INSTANTIATORS) {
                Optional<Object> instance = instantiator.instantiate(clazz, configuration::contextValue);
                if (instance.isPresent()) {
                    return (T) instance.get();
                }
            }
            LOG.error("Cannot initialize class {}. Add a Micronaut introspection or include micronaut-jaxrs-reflection for reflection fallback support.", clazz);
            return null;
        }
    }

    private record JaxRsClientProviders(JaxRsConfiguration configuration) implements Providers {

        @Override
        public <T> MessageBodyReader<T> getMessageBodyReader(Class<T> type,
                                                             Type genericType,
                                                             Annotation[] annotations,
                                                             jakarta.ws.rs.core.MediaType mediaType) {
            return configuration.getMessageBodyReader(type, genericType, annotations, mediaType);
        }

        @Override
        public <T> MessageBodyWriter<T> getMessageBodyWriter(Class<T> type,
                                                             Type genericType,
                                                             Annotation[] annotations,
                                                             jakarta.ws.rs.core.MediaType mediaType) {
            return configuration.getMessageBodyWriter(type, genericType, annotations, mediaType);
        }

        @Override
        public <T extends Throwable> ExceptionMapper<T> getExceptionMapper(Class<T> type) {
            return null;
        }

        @Override
        public <T> ContextResolver<T> getContextResolver(Class<T> contextType, jakarta.ws.rs.core.MediaType mediaType) {
            return configuration.getContextResolver(
                contextType,
                mediaType == null ? jakarta.ws.rs.core.MediaType.WILDCARD_TYPE : mediaType
            );
        }
    }

    record ComponentContract(Class<?> contract, int priority) {
        boolean matches(Class<?> type) {
            return type.isAssignableFrom(contract);
        }
    }

    private record ContextResolverDefinition(Argument<?> contextType,
                                             ContextResolver<?> contextResolver,
                                             AnnotationMetadata annotationMetadata,
                                             int priority) {
    }
}
