/*
 * Copyright 2017-2026 original authors
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

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.MediaType;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The matching and ordering of the entity providers of JAX-RS, shared by the server and the
 * client (JAX-RS 4.2.1, 4.2.2): the media types a provider declares, whether they are compatible
 * with the media types of an entity and how specifically, whether a provider is a standard one,
 * and the orders of the readers and of the writers, see {@link Rank}.
 *
 * <p>What can be known of a provider before an entity is read or written, e.g. its declared
 * media types, is meant to be computed once per provider, see {@link #declared}; whether it reads
 * or writes an entity ({@code isReadable}, {@code isWriteable}) is still asked for each one.</p>
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
public final class JaxRsProviderSelection {

    /**
     * The order of the readers of the server (JAX-RS 4.2.1): the most specific media type
     * (step 3), then the providers of the application before the standard ones (4.1.3), then
     * the highest priority (step 5), then, which it leaves open, the nearest type.
     */
    public static final Comparator<Rank> READER_ORDER = Comparator.comparingInt(Rank::specificity).reversed()
        .thenComparing(Rank::standard)
        .thenComparingInt(Rank::priority)
        .thenComparingInt(Rank::distance);

    /**
     * The order of the readers with the providers of the application first (4.1.3), e.g. a
     * reader of any media type before the standard one of JSON, then the most specific media
     * type, then the highest priority, then the nearest type.
     */
    public static final Comparator<Rank> APPLICATION_FIRST_READER_ORDER = Comparator.comparing(Rank::standard)
        .thenComparing(Comparator.comparingInt(Rank::specificity).reversed())
        .thenComparingInt(Rank::priority)
        .thenComparingInt(Rank::distance);

    /**
     * The order of the writers (JAX-RS 4.2.2, step 4): the nearest type first, then the most
     * specific media type, then the providers of the application before the standard ones
     * (4.1.3), then the highest priority.
     */
    public static final Comparator<Rank> WRITER_ORDER = Comparator.comparingInt(Rank::distance)
        .thenComparing(Comparator.comparingInt(Rank::specificity).reversed())
        .thenComparing(Rank::standard)
        .thenComparingInt(Rank::priority);

    private JaxRsProviderSelection() {
    }

    /**
     * Whether a provider is a standard one, of Micronaut, which the ones of the application come
     * before (JAX-RS 4.1.3).
     *
     * @param providerClass The class of the provider
     * @return Whether it is a standard one
     */
    public static boolean isStandard(Class<?> providerClass) {
        return providerClass.getName().startsWith("io.micronaut.");
    }

    /**
     * The media types a provider declares, parsed once: the values of its {@code @Consumes} or
     * {@code @Produces}, none for any type.
     *
     * @param metadata   The annotations of the provider
     * @param declaring  The annotation, {@code Consumes} or {@code Produces}
     * @param splitLists Whether a value is a comma separated list of media types, else one media type
     * @return The media types
     */
    public static List<MediaType> declared(AnnotationMetadata metadata, Class<? extends Annotation> declaring, boolean splitLists) {
        String[] values = metadata.stringValues(declaring);
        if (values.length == 0) {
            return List.of();
        }
        List<MediaType> declared = new ArrayList<>(values.length);
        for (String value : values) {
            if (splitLists) {
                for (String type : value.split(",")) {
                    declared.add(new MediaType(type.trim()));
                }
            } else {
                declared.add(new MediaType(value));
            }
        }
        return List.copyOf(declared);
    }

    /**
     * Whether a provider reads or writes a media type: one of the media types it declares, any
     * without one, is compatible with it, see {@link JaxRsUtils#isCompatible}.
     *
     * @param declared  The media types the provider declares, see {@link #declared}
     * @param mediaType The media type, {@code null} for any
     * @return Whether it is compatible
     */
    public static boolean isCompatible(List<MediaType> declared, @Nullable MediaType mediaType) {
        if (mediaType == null || declared.isEmpty()) {
            return true;
        }
        for (MediaType candidate : declared) {
            if (JaxRsUtils.isCompatible(candidate, mediaType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a provider reads or writes one of the media types, see {@link #isCompatible(List, MediaType)}.
     *
     * @param declared   The media types the provider declares, see {@link #declared}
     * @param mediaTypes The media types
     * @return Whether it is compatible with one of them
     */
    public static boolean isCompatible(List<MediaType> declared, List<MediaType> mediaTypes) {
        if (declared.isEmpty()) {
            return true;
        }
        for (MediaType mediaType : mediaTypes) {
            if (isCompatible(declared, mediaType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * How specific the declared media type of a provider that matches the media types is: 2 for
     * a type, 1 for a type with a wildcard subtype, 0 for any type.
     *
     * @param declared   The media types the provider declares, see {@link #declared}
     * @param mediaTypes The media types
     * @return The specificity
     */
    public static int specificity(List<MediaType> declared, List<MediaType> mediaTypes) {
        int best = 0;
        for (MediaType produced : declared) {
            for (MediaType mediaType : mediaTypes) {
                if (JaxRsUtils.isCompatible(produced, mediaType)) {
                    int specificity = "*".equals(produced.getType()) ? 0 : produced.getSubtype().startsWith("*") ? 1 : 2;
                    best = Math.max(best, specificity);
                }
            }
        }
        return best;
    }

    /**
     * The rank of a provider for an entity, which the orders compare, see {@link #READER_ORDER},
     * {@link #APPLICATION_FIRST_READER_ORDER} and {@link #WRITER_ORDER}.
     *
     * @param specificity How specifically it declares the media type, see {@link #specificity}
     * @param standard    Whether it is a standard provider, see {@link #isStandard}
     * @param priority    Its priority, the lowest first
     * @param distance    The distance of the type it reads or writes to the type of the entity,
     *                    see {@link JaxRsUtils#typeDistance}
     */
    public record Rank(int specificity, boolean standard, int priority, int distance) {
    }
}
