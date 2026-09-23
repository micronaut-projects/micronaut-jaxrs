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
package io.micronaut.jaxrs.common;

import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import io.micronaut.context.BeanRegistration;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.MediaType;
import jakarta.annotation.Priority;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.List;

/**
 * The JAX-RS utils.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
public final class JaxRsUtils {

    public static <T> void sortByPriority(List<T> values) {
        sortByPriority(values, false);
    }

    public static <T> void sortRegistrationsByPriority(List<BeanRegistration<T>> values) {
        sortByPriority(values, false);
    }

    public static <T> void sortByPriorityReversed(List<T> values) {
        sortByPriority(values, true);
    }

    public static <T> void sortRegistrationsByPriorityReversed(List<BeanRegistration<T>> values) {
        sortRegistrationsByPriority(values, true);
    }

    private static <T> void sortRegistrationsByPriority(List<BeanRegistration<T>> values, boolean reverse) {
        Comparator<BeanRegistration<T>> comparator = Comparator.comparingInt(BeanRegistration::getOrder);
        if (reverse) {
            comparator = comparator.reversed();
        }
        values.sort(comparator);
    }

    private static <T> void sortByPriority(List<T> values, boolean reverse) {
        Comparator<T> comparator = Comparator.comparingInt(JaxRsUtils::getPriorityOrder);
        if (reverse) {
            comparator = comparator.reversed();
        }
        values.sort(comparator);
    }

    public static int getPriorityOrder(Object o1) {
        return JaxRsReflection.get().annotationMetadata(o1.getClass()).intValue(Priority.class).orElse(0);
    }

    public static <T> T requireNonNull(String name, @Nullable T value) {
        if (value == null) {
            throw new IllegalArgumentException("Argument [" + name + "] cannot be null");
        }
        return value;
    }

    public static jakarta.ws.rs.core.@Nullable MediaType convert(@Nullable MediaType mediaType) {
        return mediaType == null ? null : jakarta.ws.rs.core.MediaType.valueOf(mediaType.toString());
    }

    public static @Nullable MediaType convert(jakarta.ws.rs.core.@Nullable MediaType mediaType) {
        return mediaType == null ? null : MediaType.of(mediaType.toString());
    }

    /**
     * The number of steps from a type to the type a reader reads or a writer writes, through the
     * superclasses and interfaces: 0 for the type itself, {@link Integer#MAX_VALUE} for another type.
     *
     * @param provided The type of the provider
     * @param type     The type
     * @return The distance
     */
    public static int typeDistance(Class<?> provided, Class<?> type) {
        if (!provided.isAssignableFrom(type)) {
            return Integer.MAX_VALUE;
        }
        int distance = 0;
        for (Class<?> t = type; t != null; t = t.getSuperclass()) {
            if (t == provided) {
                return distance;
            }
            if (provided.isInterface() && provided.isAssignableFrom(t)) {
                return distance + 1;
            }
            distance++;
        }
        return distance;
    }

    /**
     * How specific the consumed type of a reader, or the produced type of a writer, that matches
     * the media types is: 2 for a type, 1 for a type with a wildcard subtype, 0 for any type.
     *
     * @param declared   The declared media types, none for any
     * @param mediaTypes The media types
     * @return The specificity
     */
    public static int mediaTypeSpecificity(String[] declared, List<MediaType> mediaTypes) {
        int best = 0;
        for (String value : declared) {
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
}
