/*
 * Copyright 2017-2023 original authors
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
package io.micronaut.jaxrs.common;

import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanRegistration;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.QualifiedBeanType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.Headers;
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.core.util.ObjectUtils;
import io.micronaut.http.MediaType;
import io.micronaut.http.codec.CodecException;
import io.micronaut.inject.BeanType;
import io.micronaut.inject.qualifiers.FilteringQualifier;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Stores JAX-RS message body readers and writers.
 *
 * @author Denis Stepanov
 * @since 4.9.0
 */
@Singleton
@Internal
public final class JaxRsContainerMessageBodyHandlerRegistry {
    // the most handlers cached of each kind, see #cached
    private static final int MAX_CACHED = 256;

    private final BeanContext beanLocator;
    private volatile @Nullable Set<Class<?>> registeredClasses;
    private volatile @Nullable Optional<JaxRsProviderRegistrations> providerRegistrations;
    private final Map<HandlerKey<?>, io.micronaut.http.body.MessageBodyReader<?>> readers = new ConcurrentHashMap<>(10);
    private final Map<HandlerKey<?>, io.micronaut.http.body.MessageBodyWriter<?>> writers = new ConcurrentHashMap<>(10);
    // the metadata of the readers and of the writers, by bean definition
    private final Map<BeanDefinition<?>, ProviderMetadata> readerMetadata = new ConcurrentHashMap<>();
    private final Map<BeanDefinition<?>, ProviderMetadata> writerMetadata = new ConcurrentHashMap<>();

    /**
     * Default constructor.
     *
     * @param beanLocators The bean locator.
     */
    public JaxRsContainerMessageBodyHandlerRegistry(BeanContext beanLocators) {
        this.beanLocator = beanLocators;
    }

    /**
     * The readers of a type and media types: whether each one reads is asked when a value is
     * read, with the argument of that value and its annotations, see {@link SelectingReader}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> List<ReaderCandidate<T>> findJaxRsReaders(Argument<T> type, List<MediaType> mediaTypes) {
        Class<T> theType = type.getType();
        List<Ranked<BeanRegistration<MessageBodyReader<T>>>> ranked = new ArrayList<>();
        for (BeanRegistration br : beanLocator.getBeanRegistrations(
                Argument.of(MessageBodyReader.class), // Select all readers and eliminate by the type later
                // the type is matched below: isReadable decides, e.g. for a reader of a subtype
                new MediaTypeQualifier<>(Argument.of(MessageBodyReader.class, type), mediaTypes, Consumes.class))) {
            BeanRegistration<MessageBodyReader<T>> registration = (BeanRegistration<MessageBodyReader<T>>) br;
            if (!isRegistered(registration.getBeanDefinition().getBeanType(), MessageBodyReader.class)) {
                continue;
            }
            ProviderMetadata metadata = providerMetadata(registration, MessageBodyReader.class, Consumes.class);
            int distance = JaxRsUtils.typeDistance(metadata.provided(), theType);
            // the readers of the type, a supertype, including Object, or a subtype, whose
            // isReadable decides whether it reads the type, e.g. a reader of ArrayList for List
            if (distance != Integer.MAX_VALUE || theType.isAssignableFrom(metadata.provided())) {
                ranked.add(new Ranked<>(registration, rank(registration, metadata, MessageBodyReader.class, mediaTypes, distance)));
            }
        }
        // JAX-RS 4.2.1, see JaxRsProviderSelection#READER_ORDER
        ranked.sort(Comparator.comparing(Ranked::rank, JaxRsProviderSelection.READER_ORDER));
        List<ReaderCandidate<T>> candidates = new ArrayList<>(ranked.size());
        for (Ranked<BeanRegistration<MessageBodyReader<T>>> r : ranked) {
            MessageBodyReader<T> reader = r.provider().getBean();
            candidates.add(new ReaderCandidate<>(reader, new JaxRsMessageBodyReader<>(reader)));
        }
        return candidates;
    }

    /**
     * The writers of a type and media types, in the order of JAX-RS: whether each one writes is
     * asked when a value is written, see {@link SelectingWriter}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> List<WriterCandidate<T>> findJaxRsBodyWriters(Argument<T> type, List<MediaType> mediaTypes) {
        Class<T> theType = type.getType();
        List<Ranked<BeanRegistration<MessageBodyWriter<T>>>> ranked = new ArrayList<>();
        for (BeanRegistration br : beanLocator.getBeanRegistrations(
                Argument.of(MessageBodyWriter.class), // Select all writers and eliminate by the type later
                Qualifiers.byQualifiers(
                    // Filter by media types first before filtering by the type hierarchy
                    // the type is matched below: MessageBodyWriter<Object> writes every type
                    new MediaTypeQualifier<>(Argument.of(MessageBodyWriter.class, type), mediaTypes, Produces.class)
                ))) {
            BeanRegistration<MessageBodyWriter<T>> registration = (BeanRegistration<MessageBodyWriter<T>>) br;
            if (!isRegistered(registration.getBeanDefinition().getBeanType(), MessageBodyWriter.class)) {
                continue;
            }
            ProviderMetadata metadata = providerMetadata(registration, MessageBodyWriter.class, Produces.class);
            // the writers of the type or a supertype, including Object: MessageBodyWriter<Object>
            int distance = JaxRsUtils.typeDistance(metadata.provided(), theType);
            if (distance != Integer.MAX_VALUE) {
                ranked.add(new Ranked<>(registration, rank(registration, metadata, MessageBodyWriter.class, mediaTypes, distance)));
            }
        }
        // JAX-RS 4.2.2, step 4, see JaxRsProviderSelection#WRITER_ORDER
        ranked.sort(Comparator.comparing(Ranked::rank, JaxRsProviderSelection.WRITER_ORDER));
        List<WriterCandidate<T>> candidates = new ArrayList<>(ranked.size());
        for (Ranked<BeanRegistration<MessageBodyWriter<T>>> r : ranked) {
            BeanRegistration<MessageBodyWriter<T>> registration = r.provider();
            MessageBodyWriter<T> writer = registration.getBean();
            AnnotationMetadata annotationMetadata = registration.getBeanDefinition().getAnnotationMetadata();
            candidates.add(new WriterCandidate<>(writer, new JaxRsMessageBodyWriter<>(annotationMetadata, writer),
                providerMetadata(registration, MessageBodyWriter.class, Produces.class).declared()));
        }
        return candidates;
    }

    private JaxRsProviderSelection.Rank rank(BeanRegistration<?> registration, ProviderMetadata metadata, Class<?> contract,
                                             List<MediaType> mediaTypes, int distance) {
        return new JaxRsProviderSelection.Rank(
            JaxRsProviderSelection.specificity(metadata.declared(), mediaTypes),
            metadata.standard(),
            priority(registration, contract),
            distance);
    }

    /**
     * The metadata of a reader or a writer, read once per bean definition.
     */
    private ProviderMetadata providerMetadata(BeanRegistration<?> registration, Class<?> contract, Class<? extends Annotation> declaring) {
        Map<BeanDefinition<?>, ProviderMetadata> cache = contract == MessageBodyReader.class ? readerMetadata : writerMetadata;
        return cache.computeIfAbsent(registration.getBeanDefinition(), definition -> {
            List<Argument<?>> arguments = definition.getTypeArguments(contract);
            return new ProviderMetadata(
                arguments.isEmpty() ? Object.class : arguments.get(0).getType(),
                JaxRsProviderSelection.declared(definition.getAnnotationMetadata(), declaring, false),
                JaxRsProviderSelection.isStandard(definition.getBeanType()));
        });
    }

    /**
     * The priority of a provider for a contract: the one a feature registered it with
     * ({@code Configurable#register(Class, int)}), else its {@code @Priority} (JAX-RS 4.1.3).
     *
     * @param registration The registration of the provider
     * @param contract     The contract, e.g. {@code ExceptionMapper}
     * @return The priority, the lowest value first
     */
    public int priority(BeanRegistration<?> registration, Class<?> contract) {
        Class<?> providerClass = registration.getBeanDefinition().getBeanType();
        JaxRsProviderRegistrations providerRegistrations = providerRegistrations();
        if (providerRegistrations != null) {
            Integer priority = providerRegistrations.priority(providerClass, contract);
            return priority != null ? priority : JaxRsUtils.getPriorityOrder(registration);
        }
        for (Configuration configuration : beanLocator.getBeansOfType(Configuration.class)) {
            if (configuration.getRuntimeType() == RuntimeType.SERVER && configuration.isRegistered(providerClass)) {
                Integer priority = configuration.getContracts(providerClass).get(contract);
                if (priority != null) {
                    return priority;
                }
            }
        }
        return JaxRsUtils.getPriorityOrder(registration);
    }

    public <T> Optional<io.micronaut.http.body.MessageBodyReader<T>> findReader(Argument<T> type, List<MediaType> mediaTypes) {
        SelectingReader<T> reader = selectingReader(type, mediaTypes);
        // whether a JAX-RS reader reads is asked every time: it can depend on the annotations
        return reader.find(type, mediaTypes) != null ? Optional.of(reader) : Optional.empty();
    }

    /**
     * The reader that selects the JAX-RS reader of a type and media types, cached.
     */
    @SuppressWarnings("unchecked")
    private <T> SelectingReader<T> selectingReader(Argument<T> type, List<MediaType> mediaTypes) {
        Argument<T> lookup = boxed(type);
        if (InputStream.class.isAssignableFrom(type.getType())) {
            lookup = (Argument<T>) Argument.of(InputStream.class, type.getAnnotationMetadata());
        }
        Argument<T> candidatesType = lookup;
        return (SelectingReader<T>) cached(readers, new HandlerKey<>(lookup, mediaTypes),
            () -> new SelectingReader<>(findJaxRsReaders(candidatesType, mediaTypes)));
    }

    /**
     * The argument of the wrapper type of a primitive type, which the readers of the wrapper read
     * (JAX-RS 4.2.4), else the argument.
     */
    @SuppressWarnings("unchecked")
    private static <T> Argument<T> boxed(Argument<T> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        return (Argument<T>) Argument.of(ReflectionUtils.getWrapperType(type.getType()), type.getAnnotationMetadata());
    }

    /**
     * A value of a cache of the handlers: the media types come from the requests, so the cache is
     * bounded, a value that does not fit is not cached.
     */
    private static <K, V> V cached(Map<K, V> cache, K key, Supplier<V> factory) {
        V value = cache.get(key);
        if (value == null) {
            value = factory.get();
            if (cache.size() < MAX_CACHED) {
                V previous = cache.putIfAbsent(key, value);
                if (previous != null) {
                    value = previous;
                }
            }
        }
        return value;
    }

    /**
     * The reader of a type and media types that selects the JAX-RS reader when a value is read:
     * which one reads is decided then, with the argument of the value and its annotations, so
     * whether it is present does not depend on the annotations and callers may cache it per type.
     *
     * @param type       The type
     * @param mediaTypes The media types
     * @param <T>        The type
     * @return The reader, empty if no JAX-RS reader of the application reads the type
     */
    public <T> Optional<io.micronaut.http.body.MessageBodyReader<T>> findSelectingReader(Argument<T> type, List<MediaType> mediaTypes) {
        SelectingReader<T> reader = selectingReader(type, mediaTypes);
        return reader.candidates.isEmpty() ? Optional.empty() : Optional.of(reader);
    }

    @SuppressWarnings({"unchecked"})
    public <T> Optional<io.micronaut.http.body.MessageBodyWriter<T>> findWriter(Argument<T> type, List<MediaType> mediaTypes) {
        if (type.getType() == Object.class) {
            return Optional.empty();
        }
        SelectingWriter<T> writer = selectingWriter(type, mediaTypes);
        // whether a JAX-RS writer writes is asked every time: it can change between values
        return writer.writes(type, mediaTypes) ? Optional.of(writer) : Optional.empty();
    }

    /**
     * The writer that selects the JAX-RS writer of a type and media types, cached.
     */
    @SuppressWarnings("unchecked")
    private <T> SelectingWriter<T> selectingWriter(Argument<T> type, List<MediaType> mediaTypes) {
        return (SelectingWriter<T>) cached(writers, new HandlerKey<>(type, mediaTypes),
            () -> new SelectingWriter<>(findJaxRsBodyWriters(type, mediaTypes)));
    }

    /**
     * @return The number of the cached readers and writers
     */
    int cachedHandlers() {
        return readers.size() + writers.size();
    }

    /**
     * The JAX-RS reader of the application that reads a type as a media type, for
     * {@code Providers#getMessageBodyReader}: the registered readers, in the order of JAX-RS
     * (4.2.1), the first one that reads it.
     *
     * @param type        The type
     * @param genericType The generic type
     * @param annotations The annotations
     * @param mediaType   The media type
     * @param <T>         The type
     * @return The reader, {@code null} if none reads it
     */
    public <T> @Nullable MessageBodyReader<T> findJaxRsReader(Class<T> type, Type genericType, Annotation[] annotations,
                                                             jakarta.ws.rs.core.MediaType mediaType) {
        // the candidates of the type, as it is, cached like the ones of the entities
        List<MediaType> mediaTypes = List.of(convertNonNull(mediaType));
        Argument<T> argument = Argument.of(type);
        @SuppressWarnings("unchecked")
        SelectingReader<T> selecting = (SelectingReader<T>) cached(readers, new HandlerKey<>(argument, mediaTypes),
            () -> new SelectingReader<>(findJaxRsReaders(argument, mediaTypes)));
        for (ReaderCandidate<T> candidate : selecting.candidates) {
            if (candidate.reader().isReadable(type, genericType, annotations, mediaType)) {
                return candidate.reader();
            }
        }
        return null;
    }

    /**
     * The JAX-RS writer of the application that writes a type as a media type, for
     * {@code Providers#getMessageBodyWriter}: the registered writers, in the order of JAX-RS
     * (4.2.2), the first one that writes it.
     *
     * @param type        The type
     * @param genericType The generic type
     * @param annotations The annotations
     * @param mediaType   The media type
     * @param <T>         The type
     * @return The writer, {@code null} if none writes it
     */
    public <T> @Nullable MessageBodyWriter<T> findJaxRsWriter(Class<T> type, Type genericType, Annotation[] annotations,
                                                             jakarta.ws.rs.core.MediaType mediaType) {
        for (WriterCandidate<T> candidate : selectingWriter(Argument.of(type), List.of(convertNonNull(mediaType))).candidates) {
            if (candidate.writer().isWriteable(type, genericType, annotations, mediaType)) {
                return candidate.writer();
            }
        }
        return null;
    }

    private static MediaType convertNonNull(jakarta.ws.rs.core.@Nullable MediaType mediaType) {
        MediaType converted = JaxRsUtils.convert(mediaType);
        return converted == null ? MediaType.ALL_TYPE : converted;
    }

    /**
     * The media types the JAX-RS writers of the application write a type as: the types they
     * produce, for the writers that write it (JAX-RS 3.8, step 2).
     *
     * @param type The type
     * @param <T>  The type
     * @return The media types, empty if no JAX-RS writer writes the type
     */
    public <T> List<MediaType> producibleTypes(Argument<T> type) {
        if (type.getType() == Object.class) {
            return List.of();
        }
        Class<T> theType = type.getType();
        Type genericType = type.asType();
        List<MediaType> producible = new ArrayList<>();
        if (isStandardType(theType)) {
            // the standard providers of JAX-RS write it as any type (section 4.2.4)
            return List.of(MediaType.ALL_TYPE);
        }
        for (WriterCandidate<T> candidate : selectingWriter(type, List.of(MediaType.ALL_TYPE)).candidates) {
            List<MediaType> produces = candidate.produces();
            for (MediaType mediaType : produces.isEmpty() ? List.of(MediaType.ALL_TYPE) : produces) {
                if (!producible.contains(mediaType)
                    && candidate.writer().isWriteable(theType, genericType, JaxRsArgumentUtil.annotations(type.getAnnotationMetadata(), candidate.writer()),
                        JaxRsUtils.convert(mediaType))) {
                    producible.add(mediaType);
                }
            }
        }
        return producible;
    }

    /**
     * Whether a provider is registered with the application: the {@code Application} that lists
     * its classes or singletons registers only those and the ones its features register (JAX-RS
     * 2.3, 4.1.3), and one that lists none, all. The providers of this module are always
     * registered.
     *
     * @param providerClass The class of the provider
     * @return Whether it is registered
     */
    public boolean isRegistered(Class<?> providerClass) {
        return isRegistered(providerClass, null);
    }

    /**
     * Whether a provider is registered with the application for a contract: a feature that
     * registers it for some contracts registers it only for those (JAX-RS,
     * {@code Configurable#register(Class, Class[])}), see {@link #isRegistered(Class)}.
     *
     * @param providerClass The class of the provider
     * @param contract      The contract, e.g. {@code MessageBodyReader}, {@code null} for any
     * @return Whether it is registered for the contract
     */
    public boolean isRegistered(Class<?> providerClass, @Nullable Class<?> contract) {
        Set<Class<?>> registered = registeredClasses;
        if (registered == null) {
            Set<Class<?>> classes = new HashSet<>();
            beanLocator.findBean(Application.class).ifPresent(application -> {
                classes.addAll(application.getClasses());
                for (Object singleton : application.getSingletons()) {
                    classes.add(singleton.getClass());
                }
            });
            registered = classes;
            registeredClasses = registered;
        }
        boolean listed = registered.isEmpty() || registered.contains(providerClass) || providerClass.getName().startsWith("io.micronaut.");
        if (listed && contract == null) {
            return true;
        }
        JaxRsProviderRegistrations providerRegistrations = providerRegistrations();
        if (providerRegistrations != null) {
            if (!providerRegistrations.isRegistered(providerClass)) {
                return listed;
            }
            // registered by a feature for some contracts: for those only, none for all
            return contract == null || providerRegistrations.isRegistered(providerClass, contract);
        }
        boolean byFeature = false;
        for (Configuration configuration : beanLocator.getBeansOfType(Configuration.class)) {
            if (configuration.getRuntimeType() == RuntimeType.SERVER && configuration.isRegistered(providerClass)) {
                // registered by a feature for some contracts: for those only, none for all
                Map<Class<?>, Integer> contracts = contract == null ? Map.of() : configuration.getContracts(providerClass);
                if (!contracts.isEmpty() && !contracts.containsKey(contract)) {
                    return false;
                }
                byFeature = true;
            }
        }
        return byFeature || listed;
    }

    /**
     * The registrations of the providers of the server, else, e.g. without a server, the server
     * {@code Configuration} beans are queried.
     */
    private @Nullable JaxRsProviderRegistrations providerRegistrations() {
        Optional<JaxRsProviderRegistrations> registrations = providerRegistrations;
        if (registrations == null) {
            registrations = beanLocator.findBean(JaxRsProviderRegistrations.class);
            providerRegistrations = registrations;
        }
        return registrations.orElse(null);
    }

    /**
     * @param type A type
     * @return Whether a standard provider of JAX-RS writes it as any media type (section 4.2.4)
     */
    private static boolean isStandardType(Class<?> type) {
        return type == String.class || type == byte[].class || InputStream.class.isAssignableFrom(type)
            || java.io.Reader.class.isAssignableFrom(type) || java.io.File.class.isAssignableFrom(type)
            || type.getName().equals("jakarta.activation.DataSource")
            || jakarta.ws.rs.core.StreamingOutput.class.isAssignableFrom(type);
    }

    private static final class MediaTypeQualifier<T> extends FilteringQualifier<T> {
        private final Argument<?> type;
        private final List<MediaType> mediaTypes;
        private final Class<? extends Annotation> annotationType;

        private MediaTypeQualifier(Argument<?> type,
                                   List<MediaType> mediaTypes,
                                   Class<? extends Annotation> annotationType) {
            this.type = type;
            this.mediaTypes = mediaTypes;
            this.annotationType = annotationType;
        }

        @Override
        public boolean doesQualify(Class<T> beanType, BeanType<T> candidate) {
            return qualifies(candidate.getAnnotationMetadata());
        }

        private boolean qualifies(AnnotationMetadata annotationMetadata) {
            if (!JaxRsUtils.isServerProvider(annotationMetadata)) {
                return false;
            }
            String[] applicableTypes = annotationMetadata.stringValues(annotationType);
            if (applicableTypes.length == 0) {
                return true;
            }
            for (String mt : applicableTypes) {
                MediaType mediaType = new MediaType(mt);
                for (MediaType m : mediaTypes) {
                    // compatible either way: application/xml and application/*
                    if (JaxRsUtils.isCompatible(mediaType, m)) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public <K extends QualifiedBeanType<T>> Collection<K> filterQualified(Class<T> beanType, Collection<K> candidates) {
            List<K> all = new ArrayList<>(candidates.size());
            for (K candidate : candidates) {
                if (qualifies(candidate.getAnnotationMetadata())) {
                    all.add(candidate);
                }
            }
            // Handlers with a media type defined should have a priority
            all.sort(Comparator.comparingInt(this::findOrder).reversed());
            return all;
        }

        private int findOrder(BeanType<?> beanType) {
            int order = 0;
            String[] applicableTypes = beanType.getAnnotationMetadata().stringValues(annotationType);
            int size = mediaTypes.size();
            for (String mt : applicableTypes) {
                int index = mediaTypes.indexOf(new MediaType(mt));
                if (index == -1) {
                    continue;
                }
                int compareValue = size - index; // First value should have the priority
                order = Integer.max(order, compareValue);
            }
            return order;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            MediaTypeQualifier<?> that = (MediaTypeQualifier<?>) o;
            return type.equalsType(that.type) && mediaTypes.equals(that.mediaTypes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type.typeHashCode(), mediaTypes);
        }

        @Override
        public String toString() {
            return "MediaTypeQualifier[" +
                "type=" + type + ", " +
                "mediaTypes=" + mediaTypes + ", " +
                "annotationType=" + annotationType + ']';
        }

    }

    private record HandlerKey<T>(Argument<T> type, List<MediaType> mediaTypes) {
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            HandlerKey<?> that = (HandlerKey<?>) o;
            return type.equalsType(that.type) && mediaTypes.equals(that.mediaTypes);
        }

        @Override
        public int hashCode() {
            return ObjectUtils.hash(type.typeHashCode(), mediaTypes);
        }
    }

    /**
     * Reads with the first of the JAX-RS readers that reads the value, asked when it is read
     * (JAX-RS 4.2.1): with the argument of the value, whose annotations a reader may depend on.
     *
     * @param <T> The type
     */
    private static final class SelectingReader<T> implements io.micronaut.http.body.MessageBodyReader<T> {
        private final List<ReaderCandidate<T>> candidates;

        SelectingReader(List<ReaderCandidate<T>> candidates) {
            this.candidates = candidates;
        }

        @Nullable ReaderCandidate<T> find(Argument<T> argument, List<MediaType> mediaTypes) {
            if (candidates.isEmpty()) {
                return null;
            }
            Argument<T> type = boxed(argument);
            Class<T> theType = type.getType();
            Type genericType = type.asType();
            for (MediaType mediaType : mediaTypes) {
                jakarta.ws.rs.core.MediaType jaxRsType = JaxRsUtils.convert(mediaType);
                for (ReaderCandidate<T> candidate : candidates) {
                    Annotation[] annotations = JaxRsArgumentUtil.annotations(type.getAnnotationMetadata(), candidate.reader());
                    if (candidate.reader().isReadable(theType, genericType, annotations, jaxRsType)) {
                        return candidate;
                    }
                }
            }
            return null;
        }

        @Override
        public @Nullable T read(@NonNull Argument<T> type, @Nullable MediaType mediaType, @NonNull Headers httpHeaders,
                                @NonNull InputStream inputStream) throws CodecException {
            ReaderCandidate<T> reader = find(type, List.of(mediaType == null ? MediaType.ALL_TYPE : mediaType));
            if (reader == null) {
                throw new CodecException("No JAX-RS reader reads " + type + " as " + mediaType);
            }
            return reader.adapter().read(boxed(type), mediaType, httpHeaders, inputStream);
        }
    }

    /**
     * Writes with the first of the JAX-RS writers that writes the value, asked when it is written
     * (JAX-RS 4.2.2): whether a writer writes can change between values.
     *
     * @param <T> The type
     */
    private static final class SelectingWriter<T> implements io.micronaut.http.body.MessageBodyWriter<T> {
        private final List<WriterCandidate<T>> candidates;

        SelectingWriter(List<WriterCandidate<T>> candidates) {
            this.candidates = candidates;
        }

        boolean writes(Argument<T> type, List<MediaType> mediaTypes) {
            if (candidates.isEmpty()) {
                return false;
            }
            Class<T> theType = type.getType();
            Type genericType = type.asType();
            for (MediaType mediaType : mediaTypes) {
                jakarta.ws.rs.core.MediaType jaxRsType = JaxRsUtils.convert(mediaType);
                for (WriterCandidate<T> candidate : candidates) {
                    Annotation[] annotations = JaxRsArgumentUtil.annotations(type.getAnnotationMetadata(), candidate.writer());
                    if (candidate.writer().isWriteable(theType, genericType, annotations, jaxRsType)) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public void writeTo(@NonNull Argument<T> type, @NonNull MediaType mediaType, T object,
                            @NonNull MutableHeaders outgoingHeaders, @NonNull OutputStream outputStream) throws CodecException {
            Class<T> theType = type.getType();
            Type genericType = type.asType();
            jakarta.ws.rs.core.MediaType jaxRsType = JaxRsUtils.convert(mediaType);
            WriterCandidate<T> selected = null;
            for (WriterCandidate<T> candidate : candidates) {
                Annotation[] annotations = JaxRsArgumentUtil.annotations(type.getAnnotationMetadata(), candidate.writer());
                // the first one that writes is selected, the later ones are not asked (JAX-RS 4.2.2, step 5)
                if (candidate.writer().isWriteable(theType, genericType, annotations, jaxRsType)) {
                    selected = candidate;
                    break;
                }
            }
            if (selected == null) {
                throw new CodecException("No JAX-RS writer writes " + type + " as " + mediaType);
            }
            selected.adapter().writeTo(type, mediaType, object, outgoingHeaders, outputStream);
        }
    }

    /**
     * What is known of a reader or a writer before an entity is read or written.
     *
     * @param provided The type it reads or writes, {@code Object} if not known
     * @param declared The media types of its {@code @Consumes} or {@code @Produces}
     * @param standard Whether it is a standard provider
     */
    private record ProviderMetadata(Class<?> provided, List<MediaType> declared, boolean standard) {
    }

    /**
     * A provider with its rank for an entity.
     *
     * @param provider The provider
     * @param rank     Its rank
     * @param <P>      The type of the provider
     */
    private record Ranked<P>(P provider, JaxRsProviderSelection.Rank rank) {
    }

    /**
     * A candidate reader, with its adapter, created once.
     *
     * @param reader  The JAX-RS reader
     * @param adapter Its adapter
     * @param <T>     The type it reads
     */
    private record ReaderCandidate<T>(MessageBodyReader<T> reader, JaxRsMessageBodyReader<T> adapter) {
    }

    /**
     * A candidate writer, with its adapter, created once, and the media types it produces.
     *
     * @param writer   The JAX-RS writer
     * @param adapter  Its adapter
     * @param produces The media types of its {@code @Produces}
     * @param <T>      The type it writes
     */
    private record WriterCandidate<T>(MessageBodyWriter<T> writer, JaxRsMessageBodyWriter<T> adapter, List<MediaType> produces) {
    }
}
