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
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.QualifiedBeanType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.core.Application;
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

/**
 * Stores JAX-RS message body readers and writers.
 *
 * @author Denis Stepanov
 * @since 4.9.0
 */
@Singleton
@Internal
public final class JaxRsContainerMessageBodyHandlerRegistry {
    private final BeanContext beanLocator;
    private volatile @Nullable Set<Class<?>> registeredClasses;
    private final Map<HandlerKey<?>, io.micronaut.http.body.MessageBodyReader<?>> readers = new ConcurrentHashMap<>(10);
    private final Map<HandlerKey<?>, io.micronaut.http.body.MessageBodyWriter<?>> writers = new ConcurrentHashMap<>(10);

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
    private <T> List<MessageBodyReader<T>> findJaxRsReaders(Argument<T> type, List<MediaType> mediaTypes) {
        Class<T> theType = type.getType();
        return (List) beanLocator.getBeanRegistrations(
                Argument.of(MessageBodyReader.class), // Select all readers and eliminate by the type later
                // the type is matched below: isReadable decides, e.g. for a reader of a subtype
                new MediaTypeQualifier<>(Argument.of(MessageBodyReader.class, type), mediaTypes, Consumes.class)
            ).stream()
            .map((BeanRegistration br) -> (BeanRegistration<MessageBodyReader<T>>) br)
            .filter(br -> isRegistered(br.getBeanDefinition().getBeanType()))
            // the readers of the type, a supertype, including Object, or a subtype
            .filter(br -> typeDistance(br, MessageBodyReader.class, theType) != Integer.MAX_VALUE || readsSubtype(br, theType))
            // JAX-RS 4.2.1: the most specific media type first, then the nearest type, then the
            // providers of the application before the standard ones (4.1.3)
            .sorted(Comparator.<BeanRegistration<MessageBodyReader<T>>>comparingInt(br -> mediaTypeSpecificity(br, Consumes.class, mediaTypes)).reversed()
                .thenComparingInt(br -> typeDistance(br, MessageBodyReader.class, theType))
                .thenComparing(br -> br.getBeanDefinition().getBeanType().getName().startsWith("io.micronaut.")))
            .map(BeanRegistration::getBean)
            .toList();
    }

    /**
     * The writers of a type and media types, in the order of JAX-RS: whether each one writes is
     * asked when a value is written, see {@link SelectingWriter}.
     */
    @SuppressWarnings({"unchecked"})
    private <T> List<BeanRegistration<MessageBodyWriter<T>>> findJaxRsBodyWriters(Argument<T> type, List<MediaType> mediaTypes) {
        Class<T> theType = type.getType();
        return beanLocator.getBeanRegistrations(
                Argument.of(MessageBodyWriter.class), // Select all writers and eliminate by the type later
                Qualifiers.byQualifiers(
                    // Filter by media types first before filtering by the type hierarchy
                    // the type is matched below: MessageBodyWriter<Object> writes every type
                    new MediaTypeQualifier<>(Argument.of(MessageBodyWriter.class, type), mediaTypes, Produces.class)
                )
            ).stream()
            .map((BeanRegistration br) -> (BeanRegistration<MessageBodyWriter<T>>) br)
            .filter(br -> isRegistered(br.getBeanDefinition().getBeanType()))
            // the writers of the type or a supertype, including Object: MessageBodyWriter<Object>
            .filter(br -> typeDistance(br, MessageBodyWriter.class, theType) != Integer.MAX_VALUE)
            // JAX-RS 4.2.2: the most specific media type first, then the nearest type, then the
            // providers of the application before the standard ones (4.1.3)
            .sorted(Comparator.<BeanRegistration<MessageBodyWriter<T>>>comparingInt(br -> mediaTypeSpecificity(br, Produces.class, mediaTypes)).reversed()
                .thenComparingInt(br -> typeDistance(br, MessageBodyWriter.class, theType))
                .thenComparing(br -> br.getBeanDefinition().getBeanType().getName().startsWith("io.micronaut.")))
            .toList();
    }

    /**
     * Whether a reader reads a subtype of a type, e.g. a reader of {@code ArrayList} for
     * {@code List}: its {@code isReadable} decides whether it reads the type.
     */
    private static boolean readsSubtype(BeanRegistration<?> registration, Class<?> type) {
        List<Argument<?>> arguments = registration.getBeanDefinition().getTypeArguments(MessageBodyReader.class);
        return !arguments.isEmpty() && type.isAssignableFrom(arguments.get(0).getType());
    }

    /**
     * The number of steps from a type to the type a reader reads or a writer writes, through the
     * superclasses and interfaces: 0 for the type itself.
     */
    private static int typeDistance(BeanRegistration<?> registration, Class<?> provider, Class<?> type) {
        List<Argument<?>> arguments = registration.getBeanDefinition().getTypeArguments(provider);
        Class<?> written = arguments.isEmpty() ? Object.class : arguments.get(0).getType();
        if (!written.isAssignableFrom(type)) {
            // not a writer of the type
            return Integer.MAX_VALUE;
        }
        int distance = 0;
        for (Class<?> t = type; t != null; t = t.getSuperclass()) {
            if (t == written) {
                return distance;
            }
            if (written.isInterface() && written.isAssignableFrom(t)) {
                return distance + 1;
            }
            distance++;
        }
        return distance;
    }

    /**
     * How specific the consumed type of a reader, or the produced type of a writer, that matches
     * the media types is: 2 for a type, 1 for a type with a wildcard subtype, 0 for any type.
     */
    private static int mediaTypeSpecificity(BeanRegistration<?> registration, Class<? extends Annotation> annotation, List<MediaType> mediaTypes) {
        String[] produces = registration.getBeanDefinition().getAnnotationMetadata().stringValues(annotation);
        if (produces.length == 0) {
            return 0;
        }
        int best = 0;
        for (String value : produces) {
            MediaType produced = new MediaType(value);
            for (MediaType mediaType : mediaTypes) {
                if (mediaType.matches(produced) || produced.matches(mediaType)) {
                    int specificity = "*".equals(produced.getType()) ? 0 : "*".equals(produced.getSubtype()) ? 1 : 2;
                    best = Math.max(best, specificity);
                }
            }
        }
        return best;
    }

    @SuppressWarnings({"unchecked"})
    public <T> Optional<io.micronaut.http.body.MessageBodyReader<T>> findReader(Argument<T> type, List<MediaType> mediaTypes) {
        Argument<T> lookup = type;
        if (Number.class.isAssignableFrom(type.getType())) {
            lookup = (Argument<T>) Argument.of(Number.class, type.getAnnotationMetadata());
        }
        if (InputStream.class.isAssignableFrom(type.getType())) {
            lookup = (Argument<T>) Argument.of(InputStream.class, type.getAnnotationMetadata());
        }
        Argument<T> candidatesType = lookup;
        HandlerKey<T> key = new HandlerKey<>(lookup, mediaTypes);
        SelectingReader<T> reader = (SelectingReader<T>) readers.computeIfAbsent(key,
            k -> new SelectingReader<>(findJaxRsReaders(candidatesType, mediaTypes)));
        // whether a JAX-RS reader reads is asked every time: it can depend on the annotations
        return reader.find(type, mediaTypes) != null ? Optional.of(reader) : Optional.empty();
    }

    /**
     * Whether an application JAX-RS reader may read a type as one of the media types: it has
     * readers of the type for the media types. Which one reads is decided when a value is read,
     * with its argument and annotations, so the answer does not depend on the annotations: callers
     * that cache it per type, like the router per route, stay correct.
     *
     * @param type       The type
     * @param mediaTypes The media types
     * @param <T>        The type
     * @return Whether it has candidate readers
     */
    @SuppressWarnings("unchecked")
    public <T> boolean hasReaders(Argument<T> type, List<MediaType> mediaTypes) {
        Argument<T> lookup = type;
        if (Number.class.isAssignableFrom(type.getType())) {
            lookup = (Argument<T>) Argument.of(Number.class, type.getAnnotationMetadata());
        }
        if (InputStream.class.isAssignableFrom(type.getType())) {
            lookup = (Argument<T>) Argument.of(InputStream.class, type.getAnnotationMetadata());
        }
        Argument<T> candidatesType = lookup;
        SelectingReader<T> reader = (SelectingReader<T>) readers.computeIfAbsent(new HandlerKey<>(lookup, mediaTypes),
            k -> new SelectingReader<>(findJaxRsReaders(candidatesType, mediaTypes)));
        return !reader.candidates.isEmpty();
    }

    /**
     * The reader of a type and media types that selects the JAX-RS reader when a value is read,
     * see {@link #hasReaders(Argument, List)}.
     *
     * @param type       The type
     * @param mediaTypes The media types
     * @param <T>        The type
     * @return The reader, empty if no JAX-RS reader of the application reads the type
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<io.micronaut.http.body.MessageBodyReader<T>> findSelectingReader(Argument<T> type, List<MediaType> mediaTypes) {
        if (!hasReaders(type, mediaTypes)) {
            return Optional.empty();
        }
        Argument<T> lookup = type;
        if (Number.class.isAssignableFrom(type.getType())) {
            lookup = (Argument<T>) Argument.of(Number.class, type.getAnnotationMetadata());
        }
        if (InputStream.class.isAssignableFrom(type.getType())) {
            lookup = (Argument<T>) Argument.of(InputStream.class, type.getAnnotationMetadata());
        }
        return Optional.of((io.micronaut.http.body.MessageBodyReader<T>) readers.get(new HandlerKey<>(lookup, mediaTypes)));
    }

    @SuppressWarnings({"unchecked"})
    public <T> Optional<io.micronaut.http.body.MessageBodyWriter<T>> findWriter(Argument<T> type, List<MediaType> mediaTypes) {
        if (type.getType() == Object.class) {
            return Optional.empty();
        }
        HandlerKey<T> key = new HandlerKey<>(type, mediaTypes);
        SelectingWriter<T> writer = (SelectingWriter<T>) writers.computeIfAbsent(key,
            k -> new SelectingWriter<>(findJaxRsBodyWriters(type, mediaTypes)));
        // whether a JAX-RS writer writes is asked every time: it can change between values
        return writer.writes(type, mediaTypes) ? Optional.of(writer) : Optional.empty();
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
        for (BeanRegistration<MessageBodyWriter<T>> candidate : findJaxRsBodyWriters(type, List.of(MediaType.ALL_TYPE))) {
            String[] produces = candidate.getBeanDefinition().getAnnotationMetadata().stringValues(Produces.class);
            for (String value : produces.length == 0 ? new String[]{MediaType.ALL} : produces) {
                MediaType mediaType = new MediaType(value);
                if (!producible.contains(mediaType)
                    && candidate.getBean().isWriteable(theType, genericType, JaxRsArgumentUtil.annotations(type.getAnnotationMetadata(), candidate.getBean()),
                        JaxRsUtils.convert(mediaType))) {
                    producible.add(mediaType);
                }
            }
        }
        return producible;
    }

    /**
     * Whether a provider is registered with the application: the {@code Application} that lists
     * its classes or singletons registers only those (JAX-RS 2.3), and one that lists none, all.
     * The providers of this module are always registered.
     *
     * @param providerClass The class of the provider
     * @return Whether it is registered
     */
    public boolean isRegistered(Class<?> providerClass) {
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
        return registered.isEmpty() || registered.contains(providerClass) || providerClass.getName().startsWith("io.micronaut.");
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
            AnnotationValue<ConstrainedTo> constrainedTo = annotationMetadata.getAnnotation(ConstrainedTo.class);
            if (constrainedTo != null) {
                Optional<RuntimeType> runtimeType = constrainedTo.enumValue(RuntimeType.class);
                if (runtimeType.isPresent() && runtimeType.get() != RuntimeType.SERVER) {
                    return false;
                }
            }
            String[] applicableTypes = annotationMetadata.stringValues(annotationType);
            if (applicableTypes.length == 0) {
                return true;
            }
            for (String mt : applicableTypes) {
                MediaType mediaType = new MediaType(mt);
                for (MediaType m : mediaTypes) {
                    // compatible either way: application/xml and application/*
                    if (m.matches(mediaType) || mediaType.matches(m)) {
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

        private static boolean isInvalidType(List<Argument<?>> consumedType, Argument<?> requiredType) {
            Argument<?> argument = consumedType.get(0);
            return !(argument.isTypeVariable() || argument.isAssignableFrom(requiredType.getType()));
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
        private final List<MessageBodyReader<T>> candidates;

        SelectingReader(List<MessageBodyReader<T>> candidates) {
            this.candidates = candidates;
        }

        @Nullable MessageBodyReader<T> find(Argument<T> type, List<MediaType> mediaTypes) {
            if (candidates.isEmpty()) {
                return null;
            }
            Class<T> theType = type.getType();
            Type genericType = type.asType();
            for (MediaType mediaType : mediaTypes) {
                jakarta.ws.rs.core.MediaType jaxRsType = JaxRsUtils.convert(mediaType);
                for (MessageBodyReader<T> candidate : candidates) {
                    Annotation[] annotations = JaxRsArgumentUtil.annotations(type.getAnnotationMetadata(), candidate);
                    if (candidate.isReadable(theType, genericType, annotations, jaxRsType)) {
                        return candidate;
                    }
                }
            }
            return null;
        }

        @Override
        public @Nullable T read(@NonNull Argument<T> type, @Nullable MediaType mediaType, @NonNull Headers httpHeaders,
                                @NonNull InputStream inputStream) throws CodecException {
            MessageBodyReader<T> reader = find(type, List.of(mediaType == null ? MediaType.ALL_TYPE : mediaType));
            if (reader == null) {
                throw new CodecException("No JAX-RS reader reads " + type + " as " + mediaType);
            }
            return new JaxRsMessageBodyReader<>(reader).read(type, mediaType, httpHeaders, inputStream);
        }
    }

    /**
     * Writes with the first of the JAX-RS writers that writes the value, asked when it is written
     * (JAX-RS 4.2.2): whether a writer writes can change between values.
     *
     * @param <T> The type
     */
    private final class SelectingWriter<T> implements io.micronaut.http.body.MessageBodyWriter<T> {
        private final List<BeanRegistration<MessageBodyWriter<T>>> candidates;

        SelectingWriter(List<BeanRegistration<MessageBodyWriter<T>>> candidates) {
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
                for (BeanRegistration<MessageBodyWriter<T>> candidate : candidates) {
                    Annotation[] annotations = JaxRsArgumentUtil.annotations(type.getAnnotationMetadata(), candidate.getBean());
                    if (candidate.getBean().isWriteable(theType, genericType, annotations, jaxRsType)) {
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
            BeanRegistration<MessageBodyWriter<T>> selected = null;
            for (BeanRegistration<MessageBodyWriter<T>> candidate : candidates) {
                Annotation[] annotations = JaxRsArgumentUtil.annotations(type.getAnnotationMetadata(), candidate.getBean());
                // every writer of the type is asked, like the reference implementation does when it
                // determines the types it can produce; the first one that writes is selected
                if (candidate.getBean().isWriteable(theType, genericType, annotations, jaxRsType) && selected == null) {
                    selected = candidate;
                }
            }
            if (selected == null) {
                throw new CodecException("No JAX-RS writer writes " + type + " as " + mediaType);
            }
            new JaxRsMessageBodyWriter<>(selected.getBeanDefinition().getAnnotationMetadata(), selected.getBean())
                .writeTo(type, mediaType, object, outgoingHeaders, outputStream);
        }
    }
}
