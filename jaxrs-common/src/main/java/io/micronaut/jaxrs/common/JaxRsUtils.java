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

import io.micronaut.context.BeanRegistration;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Order;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.MediaType;
import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The JAX-RS utils.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
public final class JaxRsUtils {

    public static <T> void sortRegistrationsByPriority(List<BeanRegistration<T>> values) {
        sortRegistrationsByPriority(values, false);
    }

    public static <T> void sortRegistrationsByPriorityReversed(List<BeanRegistration<T>> values) {
        sortRegistrationsByPriority(values, true);
    }

    private static <T> void sortRegistrationsByPriority(List<BeanRegistration<T>> values, boolean reverse) {
        Comparator<BeanRegistration<T>> comparator = Comparator.comparingInt(JaxRsUtils::getPriorityOrder);
        if (reverse) {
            comparator = comparator.reversed();
        }
        values.sort(comparator);
    }

    /**
     * The priority of a provider of annotations: its {@code @Priority}, else {@link Priorities#USER},
     * the default of the providers without one (JAX-RS 4.1.3, {@link Priorities}).
     *
     * @param metadata The annotations of the provider
     * @return The priority
     */
    public static int getPriorityOrder(AnnotationMetadata metadata) {
        return metadata.intValue(Priority.class).orElse(Priorities.USER);
    }

    /**
     * The priority of a provider bean: its {@code @Priority}, else its {@code @Order}, else
     * {@link Priorities#USER}. A registration without a bean definition, of a provider that is
     * not a bean, and one with the priority it was registered with, has the priority of its order.
     *
     * @param registration The registration
     * @return The priority
     */
    public static int getPriorityOrder(BeanRegistration<?> registration) {
        BeanDefinition<?> definition = registration.getBeanDefinition();
        if (definition == null || registration instanceof JaxRsRouteInterceptors.Prioritized<?>) {
            return registration.getOrder();
        }
        return definition.intValue(Priority.class)
            .orElseGet(() -> definition.intValue(Order.class).orElse(Priorities.USER));
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
     * superclasses and interfaces, the shortest path of the hierarchy: 0 for the type itself, the
     * most for {@code Object}, which every type is nearer to, {@link Integer#MAX_VALUE} for another type.
     *
     * @param provided The type of the provider
     * @param type     The type
     * @return The distance
     */
    public static int typeDistance(Class<?> provided, Class<?> type) {
        if (!provided.isAssignableFrom(type)) {
            return Integer.MAX_VALUE;
        }
        if (provided == type) {
            return 0;
        }
        if (provided == Object.class) {
            return Integer.MAX_VALUE - 1;
        }
        if (provided.isInterface() && JaxRsReflection.get().interfaces(type) == null) {
            // the interfaces are not known: an interface is one step from the first class implementing it
            int distance = 0;
            for (Class<?> t = type; t != null; t = t.getSuperclass()) {
                if (provided.isAssignableFrom(t) && (t.getSuperclass() == null || !provided.isAssignableFrom(t.getSuperclass()))) {
                    return distance + 1;
                }
                distance++;
            }
            return distance;
        }
        // breadth first: e.g. List is one step from ArrayList, Collection two
        List<Class<?>> level = List.of(type);
        Set<Class<?>> visited = new HashSet<>();
        for (int distance = 1; !level.isEmpty(); distance++) {
            List<Class<?>> next = new ArrayList<>();
            for (Class<?> t : level) {
                Class<?> superclass = t.getSuperclass();
                if (superclass != null && provided.isAssignableFrom(superclass) && visited.add(superclass)) {
                    next.add(superclass);
                }
                Class<?>[] interfaces = JaxRsReflection.get().interfaces(t);
                for (Class<?> anInterface : interfaces == null ? new Class<?>[0] : interfaces) {
                    if (provided.isAssignableFrom(anInterface) && visited.add(anInterface)) {
                        next.add(anInterface);
                    }
                }
            }
            if (next.contains(provided)) {
                return distance;
            }
            level = next;
        }
        return Integer.MAX_VALUE - 1;
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
                if (isCompatible(produced, mediaType)) {
                    int specificity = "*".equals(produced.getType()) ? 0 : produced.getSubtype().startsWith("*") ? 1 : 2;
                    best = Math.max(best, specificity);
                }
            }
        }
        return best;
    }

    /**
     * Whether two media types are compatible, either way: like {@link MediaType#matches}, and a
     * subtype with a wildcard and a suffix, e.g. {@code application/*+json}, matches the subtypes
     * with that suffix, e.g. {@code application/vnd.example+json}.
     *
     * @param declared  A media type, e.g. the one a provider declares
     * @param mediaType Another media type
     * @return Whether they are compatible
     */
    public static boolean isCompatible(MediaType declared, MediaType mediaType) {
        return mediaType.matches(declared) || declared.matches(mediaType)
            || matchesSuffix(declared, mediaType) || matchesSuffix(mediaType, declared);
    }

    private static boolean matchesSuffix(MediaType wildcard, MediaType mediaType) {
        String subtype = wildcard.getSubtype();
        if (!subtype.startsWith("*+")) {
            return false;
        }
        return ("*".equals(wildcard.getType()) || wildcard.getType().equalsIgnoreCase(mediaType.getType()))
            && mediaType.getSubtype().toLowerCase(java.util.Locale.ROOT).endsWith(subtype.substring(1).toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Whether a provider is used by the server: it is not constrained to the client
     * ({@code @ConstrainedTo}).
     *
     * @param metadata The annotations of the provider
     * @return Whether the server uses it
     */
    public static boolean isServerProvider(AnnotationMetadata metadata) {
        return metadata.enumValue(jakarta.ws.rs.ConstrainedTo.class, jakarta.ws.rs.RuntimeType.class)
            .map(runtimeType -> runtimeType == jakarta.ws.rs.RuntimeType.SERVER)
            .orElse(true);
    }
}
