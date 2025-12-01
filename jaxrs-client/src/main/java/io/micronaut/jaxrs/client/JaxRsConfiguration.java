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
import org.jspecify.annotations.Nullable;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.order.OrderUtil;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.Headers;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMessage;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpMessage;
import io.micronaut.http.body.TypedMessageBodyReader;
import io.micronaut.http.body.TypedMessageBodyWriter;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.jaxrs.common.ByteArrayByteBuffer;
import io.micronaut.jaxrs.common.HttpMessageEntityReader;
import io.micronaut.jaxrs.common.JaxRsInterceptedRead;
import io.micronaut.jaxrs.common.JaxRsInterceptedWrite;
import io.micronaut.jaxrs.common.JaxRsMessageBodyReader;
import io.micronaut.jaxrs.common.JaxRsMessageBodyReaderDefinition;
import io.micronaut.jaxrs.common.JaxRsMessageBodyWriter;
import io.micronaut.jaxrs.common.JaxRsUtils;
import io.micronaut.jaxrs.common.JaxRsWriterInterceptorContextState;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
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

    private static final Logger LOG = LoggerFactory.getLogger(JaxRsConfiguration.class);

    private final Map<String, Object> properties;
    private final List<Component> components;

    private List<ReaderInterceptor> readerInterceptors;
    private List<WriterInterceptor> writerInterceptors;
    private List<JaxRsMessageBodyReaderDefinition> readers;
    private List<JaxRsMessageBodyWriterDefinition> writers;
    private List<ClientRequestFilter> requestFilters;
    private List<ClientResponseFilter> responseFilters;

    public JaxRsConfiguration() {
        this(new LinkedHashMap<>(), new ArrayList<>());
    }

    public JaxRsConfiguration(Map<String, Object> properties, List<Component> components) {
        this.properties = properties;
        this.components = components;
    }

    JaxRsConfiguration copy() {
        return new JaxRsConfiguration(new LinkedHashMap<>(properties), new ArrayList<>(components));
    }

    public void addProperty(String name, Object value) {
        properties.put(name, value);
    }

    void register(Class<?> componentClass) {
        register(componentClass, 0);
    }

    void register(Class<?> componentClass, int priority) {
        register(componentClass, priority, new Class<?>[0]);
    }

    void register(Class<?> componentClass, Class<?>... contracts) {
        if (contracts == null || contracts.length == 0) {
            return;
        }
        register(componentClass, 0, contracts);
    }

    private List<ComponentContract> toContracts(Class<?>[] contracts) {
        return Arrays.stream(contracts).map(c -> new ComponentContract(c, 0)).toList();
    }

    void register(Class<?> componentClass, int priority, Class<?>... contracts) {
        components.add(new ClassComponent(componentClass, priority, toContracts(contracts)));
    }

    void register(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
        components.add(new ClassComponent(componentClass, 0, toContracts(contracts)));
    }

    private List<ComponentContract> toContracts(Map<Class<?>, Integer> contracts) {
        return contracts.entrySet().stream().map(e -> new ComponentContract(e.getKey(), e.getValue())).toList();
    }

    void register(Object component) {
        register(component, 0);
    }

    void register(Object component, int priority) {
        register(component, priority, new Class<?>[0]);
    }

    void register(Object component, Class<?>... contracts) {
        if (contracts == null || contracts.length == 0) {
            return;
        }
        register(component, 0, contracts);
    }

    void register(Object component, int priority, Class<?>... contracts) {
        components.add(new InstanceComponent(component, priority, toContracts(contracts)));
    }

    void register(Object component, Map<Class<?>, Integer> contracts) {
        components.add(new InstanceComponent(component, 0, toContracts(contracts)));
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
        return components.stream()
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
                MessageBodyReader<?> reader = component.tryGet(MessageBodyReader.class);
                if (reader != null) {
                    if (isNotConstrainedToClient(reader.getClass())) {
                        continue;
                    }
                    readers.add(new JaxRsMessageBodyReaderDefinition(
                        AnnotationReflectionUtils.resolveGenericToArgument(reader.getClass(), MessageBodyReader.class).getTypeParameters()[0],
                        new JaxRsMessageBodyReader<>(reader),
                        component.priority() == 0 ? JaxRsUtils.getPriorityOrder(reader) : component.priority()
                    ));
                }
                io.micronaut.http.body.MessageBodyReader<?> micronautReader = component.tryGet(io.micronaut.http.body.MessageBodyReader.class);
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
                            component.priority() == 0 ? JaxRsUtils.getPriorityOrder(micronautReader) : component.priority()
                        ));
                    } else {
                        readers.add(new JaxRsMessageBodyReaderDefinition(
                            AnnotationReflectionUtils.resolveGenericToArgument(micronautReader.getClass(), io.micronaut.http.body.MessageBodyReader.class).getTypeParameters()[0],
                            micronautReader,
                            component.priority() == 0 ? JaxRsUtils.getPriorityOrder(micronautReader) : component.priority()
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

    private List<JaxRsMessageBodyWriterDefinition> getWriters() {
        if (writers == null) {
            writers = new ArrayList<>();
            for (JaxRsConfiguration.Component component : components) {
                MessageBodyWriter<?> writer = component.tryGet(MessageBodyWriter.class);
                if (writer != null) {
                    AnnotationMetadata annotationMetadata = annotationMetadataOf(writer.getClass());
                    if (isNotConstrainedToClient(annotationMetadata)) {
                        continue;
                    }
                    Argument<MessageBodyWriter> messageBodyWriterArgument = AnnotationReflectionUtils.resolveGenericToArgument(writer.getClass(), MessageBodyWriter.class);
                    writers.add(new JaxRsMessageBodyWriterDefinition(
                        messageBodyWriterArgument.getTypeParameters()[0],
                        new JaxRsMessageBodyWriter<>(annotationMetadata, (MessageBodyWriter<Object>) writer),
                        component.priority() == 0 ? JaxRsUtils.getPriorityOrder(writer) : component.priority()
                    ));
                }
                io.micronaut.http.body.MessageBodyWriter<?> micronautWriter = component.tryGet(io.micronaut.http.body.MessageBodyWriter.class);
                if (micronautWriter != null) {
                    if (micronautWriter instanceof TypedMessageBodyWriter<?> typedMessageBodyWriter) {
                        Argument<?> type = typedMessageBodyWriter.getType();
                        writers.add(new JaxRsMessageBodyWriterDefinition(
                            type,
                            micronautWriter,
                            component.priority() == 0 ? JaxRsUtils.getPriorityOrder(micronautWriter) : component.priority()
                        ));
                    } else {
                        writers.add(new JaxRsMessageBodyWriterDefinition(
                            AnnotationReflectionUtils.resolveGenericToArgument(micronautWriter.getClass(), io.micronaut.http.body.MessageBodyWriter.class).getTypeParameters()[0],
                            micronautWriter,
                            component.priority() == 0 ? JaxRsUtils.getPriorityOrder(micronautWriter) : component.priority()
                        ));
                    }
                }
            }
            OrderUtil.sortOrdered(writers);
        }
        return writers;
    }

    private static AnnotationMetadata annotationMetadataOf(AnnotatedElement annotatedElement) {
        // Use AnnotationReflectionUtils#annotationMetadataOf
        Annotation[] annotations = annotatedElement.getAnnotations();
        if (annotations.length == 0) {
            return AnnotationMetadata.EMPTY_METADATA;
        }
        MutableAnnotationMetadata mutableAnnotationMetadata = new MutableAnnotationMetadata();
        for (Annotation annotation : annotations) {
            Map<CharSequence, Object> values = new LinkedHashMap<>();
            Class<? extends Annotation> annotationType = annotation.annotationType();
            Method[] methods = annotationType.getMethods();
            for (Method method : methods) {
                if (!method.getDeclaringClass().equals(annotationType)) {
                    continue;
                }
                Object value = ReflectionUtils.invokeMethod(annotation, method);
                if (value != null) {
                    values.put(method.getName(), value);
                }
            }
            mutableAnnotationMetadata.addAnnotation(annotationType.getName(), values);
        }
        return mutableAnnotationMetadata;
    }

    public HttpMessageEntityReader createHttpMessageEntityReader() {
        return new HttpMessageEntityReader() {

            @Override
            public <T> T readEntity(HttpMessage<?> message, Argument<T> entityType) {
                ByteBuffer<?> byteBuffer = message.getBody(ByteBuffer.class)
                    .or(() -> message.getBody(byte[].class).map(ByteArrayByteBuffer::new))
                    .orElse(null);
                if (byteBuffer != null) {
                    List<ReaderInterceptor> readerInterceptors = getReaderInterceptors();
                    io.micronaut.http.MediaType mediaType = message.getContentType().orElse(MediaType.ALL_TYPE);
                    HttpHeaders headers = message.getHeaders();
                    if (readerInterceptors.isEmpty()) {
                        io.micronaut.http.body.MessageBodyReader<T> reader = findReader(entityType, mediaType);
                        if (reader != null) {
                            return reader.read(entityType, mediaType, headers, byteBuffer);
                        }
                    } else {
                        return new JaxRsInterceptedRead<T>(readerInterceptors) {

                            @Override
                            protected T readFromAfterInterception(Argument<Object> type, MediaType mediaType, Headers httpHeaders, InputStream inputStream) {
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

    @Nullable
    private <T> io.micronaut.http.body.MessageBodyReader<T> findReader(Argument<T> argument,
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
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        io.micronaut.http.MediaType mediaType = mutableHttpMessage.getContentType().orElse(MediaType.ALL_TYPE);

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
            mutableHttpMessage.body(outputStream.toByteArray());
        } else if (!getWriterInterceptors().isEmpty()) {
            throw new IllegalStateException("Unknown entity type " + bodyArgument.getType());
        } else {
            mutableHttpMessage.body(body);
        }
    }

    @Nullable
    private <T> io.micronaut.http.body.MessageBodyWriter<T> findWriter(Argument<T> argument,
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

    private <T> List<T> getComponentOfType(Class<T> type) {
        var valuesWithPriority = new ArrayList<Map.Entry<T, Integer>>();
        for (JaxRsConfiguration.Component component : components) {
            T instance = component.tryGet(type);
            if (instance != null) {
                valuesWithPriority.add(Map.entry(instance, component.priority() == 0 ? JaxRsUtils.getPriorityOrder(instance) : component.priority()));
            }
        }
        valuesWithPriority.sort(Comparator.comparingInt(Map.Entry::getValue));
        return valuesWithPriority.stream().map(Map.Entry::getKey).collect(Collectors.toList());
    }

    sealed interface Component {

        boolean is(Class<?> type);

        <T> T tryGet(Class<T> type);

        int priority();

        List<ComponentContract> components();

    }

    record InstanceComponent(Object component, int priority,
                             List<ComponentContract> components) implements Component {
        @Override
        public <T> T tryGet(Class<T> type) {
            if (type.isInstance(component)) {
                return (T) component;
            }
            return null;
        }

        @Override
        public boolean is(Class<?> type) {
            return type.equals(component.getClass());
        }
    }

    record ClassComponent(Class<?> componentClass, int priority,
                          List<ComponentContract> components) implements Component {

        @Override
        public <T> T tryGet(Class<T> type) {
            if (type.isAssignableFrom(componentClass)) {
                return initialize(componentClass);
            }
            return null;
        }

        @Override
        public boolean is(Class<?> type) {
            return type.equals(componentClass);
        }

        private <T> T initialize(Class<?> clazz) {
            try {
                Optional<? extends Constructor<?>> optionalConstructor = ReflectionUtils.findConstructor(clazz);
                if (optionalConstructor.isPresent()) {
                    return (T) optionalConstructor.get().newInstance();
                }
                LOG.error("Cannot initialize class {}", clazz);
                return null;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    record ComponentContract(Class<?> contract, int priority) {
    }
}
