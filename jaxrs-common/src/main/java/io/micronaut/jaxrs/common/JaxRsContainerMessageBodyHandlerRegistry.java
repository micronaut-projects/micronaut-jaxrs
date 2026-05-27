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
import io.micronaut.inject.qualifiers.MatchArgumentQualifier;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.RuntimeType;
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
    private static final io.micronaut.http.body.MessageBodyReader<Object> NO_READER = new NoReader();
    private static final io.micronaut.http.body.MessageBodyWriter<Object> NO_WRITER = new NoWriter();
    private final BeanContext beanLocator;
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

    @SuppressWarnings({"unchecked"})
    private <T> MessageBodyReader<T> findJaxRsReader(Argument<T> type, List<MediaType> mediaTypes) {
        Class<T> theType = type.getType();
        Type genericType = type.asType();
        List<jakarta.ws.rs.core.MediaType> types = mediaTypes.stream().map(JaxRsUtils::convert).toList();
        Annotation[] annotations = type.getAnnotationMetadata().synthesizeAll();
        return beanLocator.getBeansOfType(
                Argument.of(MessageBodyReader.class), // Select all readers and eliminate by the type later
                Qualifiers.byQualifiers(
                    // Filter by media types first before filtering by the type hierarchy
                    new MediaTypeQualifier<>(Argument.of(MessageBodyReader.class, type), mediaTypes, Consumes.class),
                    MatchArgumentQualifier.covariant(MessageBodyReader.class, type)
                )
            ).stream()
            .filter(reader -> types.stream().anyMatch(mediaType -> reader.isReadable(theType, genericType, annotations, mediaType)))
            .findFirst()
            .orElse(null);
    }

    @SuppressWarnings({"unchecked"})
    private <T> BeanRegistration<MessageBodyWriter<T>> findJaxRsBodyWriter(Argument<T> type, List<MediaType> mediaTypes) {
        Class<T> theType = type.getType();
        Type genericType = type.asType();
        List<jakarta.ws.rs.core.MediaType> types = mediaTypes.stream().map(JaxRsUtils::convert).toList();
        Annotation[] annotations = type.getAnnotationMetadata().synthesizeAll();
        return beanLocator.getBeanRegistrations(
                Argument.of(MessageBodyWriter.class), // Select all writers and eliminate by the type later
                Qualifiers.byQualifiers(
                    // Filter by media types first before filtering by the type hierarchy
                    new MediaTypeQualifier<>(Argument.of(MessageBodyWriter.class, type), mediaTypes, Produces.class),
                    MatchArgumentQualifier.contravariant(MessageBodyWriter.class, type)
                )
            ).stream()
            .map((BeanRegistration br) -> (BeanRegistration<MessageBodyWriter<T>>) br)
            .filter(br -> types.stream().anyMatch(mediaType -> br.getBean().isWriteable(theType, genericType, annotations, mediaType)))
            .findFirst().orElse(null);
    }

    @SuppressWarnings({"unchecked"})
    public <T> Optional<io.micronaut.http.body.MessageBodyReader<T>> findReader(Argument<T> type, List<MediaType> mediaTypes) {
        if (Number.class.isAssignableFrom(type.getType())) {
            type = (Argument<T>) Argument.of(Number.class, type.getAnnotationMetadata());
        }
        if (InputStream.class.isAssignableFrom(type.getType())) {
            type = (Argument<T>) Argument.of(InputStream.class, type.getAnnotationMetadata());
        }
        HandlerKey<T> key = new HandlerKey<>(type, mediaTypes);
        io.micronaut.http.body.MessageBodyReader<?> messageBodyReader = readers.get(key);
        if (messageBodyReader == null) {
            MessageBodyReader<T> delegate = findJaxRsReader(type, mediaTypes);
            if (delegate != null) {
                io.micronaut.http.body.MessageBodyReader<T> reader = new JaxRsMessageBodyReader<>(delegate);
                readers.put(key, reader);
                return Optional.of(reader);
            } else {
                readers.put(key, NO_READER);
                return Optional.empty();
            }
        } else if (messageBodyReader == NO_READER) {
            return Optional.empty();
        } else {
            //noinspection unchecked
            return Optional.of((io.micronaut.http.body.MessageBodyReader<T>) messageBodyReader);
        }
    }

    @SuppressWarnings({"unchecked"})
    public <T> Optional<io.micronaut.http.body.MessageBodyWriter<T>> findWriter(Argument<T> type, List<MediaType> mediaTypes) {
        if (type.getType() == Object.class) {
            return Optional.empty();
        }
        HandlerKey<T> key = new HandlerKey<>(type, mediaTypes);
        io.micronaut.http.body.MessageBodyWriter<?> messageBodyWriter = writers.get(key);
        if (messageBodyWriter == null) {
            BeanRegistration<MessageBodyWriter<T>> delegate = findJaxRsBodyWriter(type, mediaTypes);
            if (delegate != null) {
                io.micronaut.http.body.MessageBodyWriter<T> micronautWriter = new JaxRsMessageBodyWriter<>(delegate.getBeanDefinition().getAnnotationMetadata(), delegate.bean());
                writers.put(key, micronautWriter);
                return Optional.of(micronautWriter);
            } else {
                writers.put(key, NO_WRITER);
                return Optional.empty();
            }
        } else if (messageBodyWriter == NO_WRITER) {
            return Optional.empty();
        } else {
            //noinspection unchecked
            return Optional.of((io.micronaut.http.body.MessageBodyWriter<T>) messageBodyWriter);
        }
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
        public <K extends QualifiedBeanType<T>> Collection<K> filterQualified(Class<T> beanType, Collection<K> candidates) {
            List<K> all = new ArrayList<>(candidates.size());
            candidatesLoop:
            for (K candidate : candidates) {
                AnnotationMetadata annotationMetadata = candidate.getAnnotationMetadata();
                AnnotationValue<ConstrainedTo> constrainedTo = annotationMetadata.getAnnotation(ConstrainedTo.class);
                if (constrainedTo != null) {
                    Optional<RuntimeType> runtimeType = constrainedTo.enumValue(RuntimeType.class);
                    if (runtimeType.isPresent() && runtimeType.get() != RuntimeType.SERVER) {
                        continue;
                    }
                }
                String[] applicableTypes = annotationMetadata.stringValues(annotationType);
                if (applicableTypes.length == 0) {
                    all.add(candidate);
                    continue;
                }
                for (String mt : applicableTypes) {
                    MediaType mediaType = new MediaType(mt);
                    for (MediaType m : mediaTypes) {
                        if (matches(mediaType, m)) {
                            all.add(candidate);
                            continue candidatesLoop;
                        }
                    }
                }
            }
            // Handlers with a media type defined should have a priority
            all.sort(Comparator.comparingInt(candidate -> findOrder((BeanType<?>) candidate)).reversed());
            return all;
        }

        private int findOrder(BeanType<?> beanType) {
            int order = 0;
            String[] applicableTypes = beanType.getAnnotationMetadata().stringValues(annotationType);
            if (applicableTypes.length == 0) {
                return findMediaTypeOrder(MediaType.ALL_TYPE);
            }
            for (String mt : applicableTypes) {
                order = Integer.max(order, findMediaTypeOrder(new MediaType(mt)));
            }
            return order;
        }

        private int findMediaTypeOrder(MediaType applicableType) {
            int order = 0;
            int size = mediaTypes.size();
            for (int i = 0; i < size; i++) {
                MediaType mediaType = mediaTypes.get(i);
                if (!matches(applicableType, mediaType)) {
                    continue;
                }
                int compareValue = ((size - i) * 10) + specificity(applicableType); // First value should have the priority
                order = Integer.max(order, compareValue);
            }
            return order;
        }

        private static boolean matches(MediaType applicableType, MediaType mediaType) {
            return applicableType.matches(mediaType) || mediaType.matches(applicableType);
        }

        private static int specificity(MediaType mediaType) {
            if (isWildcardType(mediaType)) {
                return 0;
            }
            if (isWildcardSubtype(mediaType)) {
                return 1;
            }
            return 2;
        }

        private static boolean isWildcardType(MediaType mediaType) {
            return "*".equals(mediaType.getType());
        }

        private static boolean isWildcardSubtype(MediaType mediaType) {
            return "*".equals(mediaType.getSubtype());
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

    private static final class NoReader implements io.micronaut.http.body.MessageBodyReader<Object> {
        @Override
        public @Nullable Object read(@NonNull Argument<Object> type, @Nullable MediaType mediaType, @NonNull Headers httpHeaders, @NonNull InputStream inputStream) throws CodecException {
            return null;
        }
    }

    private static final class NoWriter implements io.micronaut.http.body.MessageBodyWriter<Object> {
        @Override
        public void writeTo(@NonNull Argument<Object> type, @NonNull MediaType mediaType, Object object, @NonNull MutableHeaders outgoingHeaders, @NonNull OutputStream outputStream) throws CodecException {

        }
    }
}
