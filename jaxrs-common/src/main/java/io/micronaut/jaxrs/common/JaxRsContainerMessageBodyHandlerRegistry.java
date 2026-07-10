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
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.order.OrderUtil;
import io.micronaut.core.type.Argument;
import io.micronaut.http.MediaType;
import io.micronaut.inject.BeanDefinition;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

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
    private final Map<LookupKey, LookupCandidates<ReaderCandidate>> readerLookupCandidates = new ConcurrentHashMap<>(16);
    private final Map<LookupKey, LookupCandidates<WriterCandidate>> writerLookupCandidates = new ConcurrentHashMap<>(16);
    private volatile @Nullable List<ReaderCandidate> readerCandidates;
    private volatile @Nullable List<WriterCandidate> writerCandidates;

    /**
     * Default constructor.
     *
     * @param beanLocators The bean locator.
     */
    public JaxRsContainerMessageBodyHandlerRegistry(BeanContext beanLocators) {
        this.beanLocator = beanLocators;
    }

    private <T> @Nullable ReaderCandidate findJaxRsReader(Argument<T> type, List<MediaType> mediaTypes) {
        Class<T> theType = type.getType();
        Type genericType = type.asType();
        LookupCandidates<ReaderCandidate> lookup = orderedReaderCandidates(theType, mediaTypes);
        if (lookup.candidates().isEmpty()) {
            return null;
        }
        Annotation[] annotations = JaxRsArgumentUtil.synthesizeEntityAnnotations(type);
        for (ReaderCandidate candidate : lookup.candidates()) {
            for (jakarta.ws.rs.core.MediaType mediaType : lookup.mediaTypes()) {
                if (candidate.delegate().isReadable(theType, genericType, annotations, mediaType)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private <T> @Nullable WriterCandidate findJaxRsBodyWriter(Argument<T> type, List<MediaType> mediaTypes) {
        Class<T> theType = type.getType();
        Type genericType = type.asType();
        LookupCandidates<WriterCandidate> lookup = orderedWriterCandidates(theType, mediaTypes);
        if (lookup.candidates().isEmpty()) {
            return null;
        }
        Annotation[] annotations = JaxRsArgumentUtil.synthesizeEntityAnnotations(type);
        for (WriterCandidate candidate : lookup.candidates()) {
            for (jakarta.ws.rs.core.MediaType mediaType : lookup.mediaTypes()) {
                if (candidate.delegate().isWriteable(theType, genericType, annotations, mediaType)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private LookupCandidates<ReaderCandidate> orderedReaderCandidates(Class<?> type, List<MediaType> mediaTypes) {
        return readerLookupCandidates.computeIfAbsent(
            new LookupKey(type, mediaTypes),
            key -> new LookupCandidates<>(
                readerCandidates()
                    .stream()
                    .filter(candidate -> matchesMediaTypes(candidate.mediaTypes(), key.mediaTypes()))
                    .filter(candidate -> supportsReadType(candidate, key.type()))
                    .sorted(Comparator
                        .comparingInt((ReaderCandidate candidate) -> typeDistance(candidate, key.type()))
                        .thenComparing(Comparator
                            .comparingInt((ReaderCandidate candidate) -> mediaTypeOrder(candidate.mediaTypes(), key.mediaTypes()))
                            .reversed())
                        .thenComparingInt(ReaderCandidate::order)
                        .thenComparing(Comparator
                            .comparingInt(JaxRsContainerMessageBodyHandlerRegistry::providerClassDepth)
                            .reversed()))
                    .toList(),
                jaxRsMediaTypes(key.mediaTypes())
            )
        );
    }

    private LookupCandidates<WriterCandidate> orderedWriterCandidates(Class<?> type, List<MediaType> mediaTypes) {
        return writerLookupCandidates.computeIfAbsent(
            new LookupKey(type, mediaTypes),
            key -> new LookupCandidates<>(
                writerCandidates()
                    .stream()
                    .filter(candidate -> matchesMediaTypes(candidate.mediaTypes(), key.mediaTypes()))
                    .filter(candidate -> supportsWriteType(candidate, key.type()))
                    .sorted(Comparator
                        .comparingInt((WriterCandidate candidate) -> typeDistance(candidate, key.type()))
                        .thenComparing(Comparator
                            .comparingInt((WriterCandidate candidate) -> mediaTypeOrder(candidate.mediaTypes(), key.mediaTypes()))
                            .reversed())
                        .thenComparingInt(WriterCandidate::order)
                        .thenComparing(Comparator
                            .comparingInt(JaxRsContainerMessageBodyHandlerRegistry::providerClassDepth)
                            .reversed()))
                    .toList(),
                jaxRsMediaTypes(key.mediaTypes())
            )
        );
    }

    private List<ReaderCandidate> readerCandidates() {
        List<ReaderCandidate> candidates = readerCandidates;
        if (candidates == null) {
            candidates = createReaderCandidates();
            readerCandidates = candidates;
        }
        return candidates;
    }

    private List<WriterCandidate> writerCandidates() {
        List<WriterCandidate> candidates = writerCandidates;
        if (candidates == null) {
            candidates = createWriterCandidates();
            writerCandidates = candidates;
        }
        return candidates;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<ReaderCandidate> createReaderCandidates() {
        return beanLocator.getBeanDefinitions(MessageBodyReader.class)
            .stream()
            .map(definition -> (BeanDefinition<MessageBodyReader<?>>) (BeanDefinition) definition)
            .filter(JaxRsContainerMessageBodyHandlerRegistry::isServerComponent)
            .map(this::createReaderCandidate)
            .toList();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<WriterCandidate> createWriterCandidates() {
        return beanLocator.getBeanDefinitions(MessageBodyWriter.class)
            .stream()
            .map(definition -> (BeanDefinition<MessageBodyWriter<?>>) (BeanDefinition) definition)
            .filter(JaxRsContainerMessageBodyHandlerRegistry::isServerComponent)
            .map(this::createWriterCandidate)
            .toList();
    }

    private ReaderCandidate createReaderCandidate(BeanDefinition<MessageBodyReader<?>> beanDefinition) {
        return new ReaderCandidate(
            beanDefinition,
            providerType(
                beanDefinition,
                MessageBodyReader.class,
                JaxRsMessageBodyProvider.MEMBER_READER_TYPE,
                JaxRsMessageBodyProvider.MEMBER_READER_TYPE_VARIABLE
            ),
            mediaTypes(beanDefinition, JaxRsMessageBodyProvider.MEMBER_CONSUMES, Consumes.class),
            OrderUtil.getOrder(beanDefinition.getAnnotationMetadata())
        );
    }

    private WriterCandidate createWriterCandidate(BeanDefinition<MessageBodyWriter<?>> beanDefinition) {
        List<MediaType> mediaTypes = mediaTypes(beanDefinition, JaxRsMessageBodyProvider.MEMBER_PRODUCES, Produces.class);
        return new WriterCandidate(
            beanDefinition,
            providerType(
                beanDefinition,
                MessageBodyWriter.class,
                JaxRsMessageBodyProvider.MEMBER_WRITER_TYPE,
                JaxRsMessageBodyProvider.MEMBER_WRITER_TYPE_VARIABLE
            ),
            mediaTypes,
            OrderUtil.getOrder(beanDefinition.getAnnotationMetadata())
        );
    }

    private static boolean isServerComponent(BeanDefinition<?> beanDefinition) {
        AnnotationMetadata annotationMetadata = beanDefinition.getAnnotationMetadata();
        Optional<Boolean> server = annotationMetadata.booleanValue(JaxRsMessageBodyProvider.class, JaxRsMessageBodyProvider.MEMBER_SERVER);
        if (server.isPresent()) {
            return server.get();
        }
        AnnotationValue<ConstrainedTo> constrainedTo = annotationMetadata.getAnnotation(ConstrainedTo.class);
        if (constrainedTo == null) {
            return true;
        }
        Optional<RuntimeType> runtimeType = constrainedTo.enumValue(RuntimeType.class);
        return runtimeType.isEmpty() || runtimeType.get() == RuntimeType.SERVER;
    }

    private static List<MediaType> mediaTypes(BeanDefinition<?> beanDefinition,
                                              String providerMetadataMember,
                                              Class<? extends Annotation> annotationType) {
        AnnotationMetadata annotationMetadata = beanDefinition.getAnnotationMetadata();
        String[] applicableTypes = annotationMetadata.stringValues(JaxRsMessageBodyProvider.class, providerMetadataMember);
        if (applicableTypes.length == 0) {
            applicableTypes = annotationMetadata.stringValues(annotationType);
        }
        if (applicableTypes.length == 0) {
            return List.of(MediaType.ALL_TYPE);
        }
        return List.of(MediaType.of(applicableTypes));
    }

    private static ProviderType providerType(BeanDefinition<?> beanDefinition,
                                             Class<?> providerType,
                                             String providerMetadataMember,
                                             String providerTypeVariableMetadataMember) {
        AnnotationMetadata annotationMetadata = beanDefinition.getAnnotationMetadata();
        Optional<Class<?>> precomputedType = annotationMetadata.classValue(JaxRsMessageBodyProvider.class, providerMetadataMember)
            .map(type -> (Class<?>) type);
        if (precomputedType.isPresent() && precomputedType.get() != Void.TYPE) {
            return new ProviderType(
                precomputedType.get(),
                annotationMetadata.booleanValue(JaxRsMessageBodyProvider.class, providerTypeVariableMetadataMember).orElse(false)
            );
        }
        List<Argument<?>> typeArguments = beanDefinition.getTypeArguments(providerType);
        if (typeArguments.isEmpty()) {
            return new ProviderType(Object.class, false);
        }
        Argument<?> argument = typeArguments.get(0);
        return new ProviderType(argument.getType(), argument.isTypeVariable());
    }

    private static boolean matchesMediaTypes(List<MediaType> applicableTypes, List<MediaType> mediaTypes) {
        for (MediaType applicableType : applicableTypes) {
            for (MediaType requestedMediaType : mediaTypes) {
                if (matches(applicableType, requestedMediaType)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<jakarta.ws.rs.core.MediaType> jaxRsMediaTypes(List<MediaType> mediaTypes) {
        return mediaTypes.stream().map(JaxRsUtils::convert).toList();
    }

    private static int mediaTypeOrder(List<MediaType> applicableTypes, List<MediaType> mediaTypes) {
        int order = 0;
        for (MediaType applicableType : applicableTypes) {
            order = Integer.max(order, mediaTypeOrder(applicableType, mediaTypes));
        }
        return order;
    }

    private static int mediaTypeOrder(MediaType applicableType, List<MediaType> mediaTypes) {
        int order = 0;
        int size = mediaTypes.size();
        for (int i = 0; i < size; i++) {
            MediaType mediaType = mediaTypes.get(i);
            if (matches(applicableType, mediaType)) {
                // Preserve the client/request media order while still preferring
                // a provider with the more specific declared media type.
                order = Integer.max(order, ((size - i) * 10) + specificity(applicableType));
            }
        }
        return order;
    }

    private static boolean matches(MediaType applicableType, MediaType mediaType) {
        return applicableType.matches(mediaType)
            || mediaType.matches(applicableType)
            || matchesStructuredSyntaxSuffix(applicableType, mediaType)
            || matchesStructuredSyntaxSuffix(mediaType, applicableType);
    }

    private static boolean matchesStructuredSyntaxSuffix(MediaType wildcardType, MediaType mediaType) {
        String wildcardSubtype = wildcardType.getSubtype();
        if (!wildcardSubtype.startsWith("*+")) {
            return false;
        }
        String type = wildcardType.getType();
        if (!"*".equals(type) && !type.equalsIgnoreCase(mediaType.getType())) {
            return false;
        }
        return mediaType.getSubtype().endsWith(wildcardSubtype.substring(1));
    }

    private static int specificity(MediaType mediaType) {
        if ("*".equals(mediaType.getType())) {
            return 0;
        }
        if ("*".equals(mediaType.getSubtype())) {
            return 1;
        }
        return 2;
    }

    private static boolean supportsWriteType(WriterCandidate candidate, Class<?> requiredType) {
        return supportsType(candidate.type(), requiredType);
    }

    private static boolean supportsReadType(ReaderCandidate candidate, Class<?> requiredType) {
        ProviderType readerType = candidate.type();
        if (readerType.type() == Object.class && !readerType.typeVariable()) {
            return requiredType == Object.class;
        }
        return supportsType(readerType, requiredType);
    }

    private static boolean supportsType(ProviderType providerType, Class<?> requiredType) {
        return providerType.type().isAssignableFrom(requiredType)
            || providerType.typeVariable() && providerType.type() == Object.class;
    }

    private static int typeDistance(ProviderCandidate candidate, Class<?> requiredType) {
        ProviderType providerType = candidate.type();
        if (providerType.typeVariable()) {
            // A generic provider is valid but should lose to any concrete type.
            return Integer.MAX_VALUE - 1;
        }
        return typeDistance(requiredType, providerType.type());
    }

    private static int typeDistance(Class<?> requiredType, Class<?> writerType) {
        if (requiredType.equals(writerType)) {
            return 0;
        }
        if (!writerType.isAssignableFrom(requiredType)) {
            return Integer.MAX_VALUE;
        }
        return Math.min(superclassDistance(requiredType, writerType), interfaceDistance(requiredType, writerType, 0));
    }

    private static int superclassDistance(@Nullable Class<?> type, Class<?> target) {
        int distance = 0;
        Class<?> current = type;
        while (current != null) {
            if (current.equals(target)) {
                return distance;
            }
            current = current.getSuperclass();
            distance++;
        }
        return Integer.MAX_VALUE;
    }

    private static int interfaceDistance(@Nullable Class<?> type, Class<?> target, int distance) {
        if (type == null) {
            return Integer.MAX_VALUE;
        }
        int best = Integer.MAX_VALUE;
        for (Class<?> interfaceType : type.getInterfaces()) {
            if (interfaceType.equals(target)) {
                best = Math.min(best, distance + 1);
            } else if (target.isAssignableFrom(interfaceType)) {
                best = Math.min(best, interfaceDistance(interfaceType, target, distance + 1));
            }
        }
        return Math.min(best, interfaceDistance(type.getSuperclass(), target, distance + 1));
    }

    private static int providerClassDepth(ProviderCandidate candidate) {
        int depth = 0;
        Class<?> type = candidate.beanDefinition().getBeanType();
        while (type != null) {
            depth++;
            type = type.getSuperclass();
        }
        return depth;
    }

    @SuppressWarnings({"unchecked"})
    public <T> Optional<io.micronaut.http.body.MessageBodyReader<T>> findReader(Argument<T> type, List<MediaType> mediaTypes) {
        ReaderCandidate candidate = findJaxRsReader(type, mediaTypes);
        if (candidate == null) {
            return Optional.empty();
        }
        return Optional.of((io.micronaut.http.body.MessageBodyReader<T>) candidate.reader());
    }

    @SuppressWarnings({"unchecked"})
    public <T> Optional<io.micronaut.http.body.MessageBodyWriter<T>> findWriter(Argument<T> type, List<MediaType> mediaTypes) {
        if (type.getType() == Object.class) {
            return Optional.empty();
        }
        WriterCandidate candidate = findJaxRsBodyWriter(type, mediaTypes);
        if (candidate == null) {
            return Optional.empty();
        }
        return Optional.of((io.micronaut.http.body.MessageBodyWriter<T>) candidate.writer());
    }

    private sealed interface ProviderCandidate permits ReaderCandidate, WriterCandidate {
        BeanDefinition<?> beanDefinition();

        ProviderType type();

        List<MediaType> mediaTypes();

        int order();
    }

    private record ProviderType(Class<?> type, boolean typeVariable) {
    }

    private record LookupKey(Class<?> type, List<MediaType> mediaTypes) {

        private LookupKey {
            mediaTypes = List.copyOf(mediaTypes);
        }
    }

    private record LookupCandidates<T extends ProviderCandidate>(List<T> candidates,
                                                                 List<jakarta.ws.rs.core.MediaType> mediaTypes) {
    }

    private final class ReaderCandidate implements ProviderCandidate {
        private final BeanDefinition<MessageBodyReader<?>> beanDefinition;
        private final ProviderType type;
        private final List<MediaType> mediaTypes;
        private final int order;
        private volatile @Nullable MessageBodyReader<?> delegate;
        private volatile io.micronaut.http.body.@Nullable MessageBodyReader<?> reader;

        private ReaderCandidate(BeanDefinition<MessageBodyReader<?>> beanDefinition,
                                ProviderType type,
                                List<MediaType> mediaTypes,
                                int order) {
            this.beanDefinition = beanDefinition;
            this.type = type;
            this.mediaTypes = mediaTypes;
            this.order = order;
        }

        @Override
        public BeanDefinition<?> beanDefinition() {
            return beanDefinition;
        }

        @Override
        public ProviderType type() {
            return type;
        }

        @Override
        public List<MediaType> mediaTypes() {
            return mediaTypes;
        }

        @Override
        public int order() {
            return order;
        }

        private MessageBodyReader<?> delegate() {
            MessageBodyReader<?> resolved = delegate;
            if (resolved == null) {
                synchronized (this) {
                    resolved = delegate;
                    if (resolved == null) {
                        resolved = beanLocator.getBean(beanDefinition);
                        delegate = resolved;
                    }
                }
            }
            return resolved;
        }

        private io.micronaut.http.body.MessageBodyReader<?> reader() {
            io.micronaut.http.body.MessageBodyReader<?> resolved = reader;
            if (resolved == null) {
                synchronized (this) {
                    resolved = reader;
                    if (resolved == null) {
                        resolved = new JaxRsMessageBodyReader<>(delegate());
                        reader = resolved;
                    }
                }
            }
            return resolved;
        }
    }

    private final class WriterCandidate implements ProviderCandidate {
        private final BeanDefinition<MessageBodyWriter<?>> beanDefinition;
        private final ProviderType type;
        private final List<MediaType> mediaTypes;
        private final int order;
        private volatile @Nullable MessageBodyWriter<?> delegate;
        private volatile io.micronaut.http.body.@Nullable MessageBodyWriter<?> writer;

        private WriterCandidate(BeanDefinition<MessageBodyWriter<?>> beanDefinition,
                                ProviderType type,
                                List<MediaType> mediaTypes,
                                int order) {
            this.beanDefinition = beanDefinition;
            this.type = type;
            this.mediaTypes = mediaTypes;
            this.order = order;
        }

        @Override
        public BeanDefinition<?> beanDefinition() {
            return beanDefinition;
        }

        @Override
        public ProviderType type() {
            return type;
        }

        @Override
        public List<MediaType> mediaTypes() {
            return mediaTypes;
        }

        @Override
        public int order() {
            return order;
        }

        private MessageBodyWriter<?> delegate() {
            MessageBodyWriter<?> resolved = delegate;
            if (resolved == null) {
                synchronized (this) {
                    resolved = delegate;
                    if (resolved == null) {
                        resolved = beanLocator.getBean(beanDefinition);
                        delegate = resolved;
                    }
                }
            }
            return resolved;
        }

        private io.micronaut.http.body.MessageBodyWriter<?> writer() {
            io.micronaut.http.body.MessageBodyWriter<?> resolved = writer;
            if (resolved == null) {
                synchronized (this) {
                    resolved = writer;
                    if (resolved == null) {
                        resolved = new JaxRsMessageBodyWriter<>(mediaTypes, delegate());
                        writer = resolved;
                    }
                }
            }
            return resolved;
        }
    }
}
