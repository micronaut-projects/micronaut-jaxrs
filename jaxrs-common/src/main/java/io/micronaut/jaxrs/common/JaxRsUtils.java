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

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.MediaType;
import jakarta.annotation.Priority;

import java.util.Arrays;
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

    public static <T> void sortByPriorityReversed(List<T> values) {
        sortByPriority(values, true);
    }

    private static <T> void sortByPriority(List<T> values, boolean reverse) {
        Comparator<T> comparator = Comparator.comparingInt(JaxRsUtils::getPriorityOrder);
        if (reverse) {
            comparator = comparator.reversed();
        }
        values.sort(comparator);
    }

    public static int getPriorityOrder(Object o1) {
        return Arrays.stream(o1.getClass().getAnnotations())
            .filter(annotation -> annotation instanceof Priority)
            .mapToInt(an -> ((Priority) an).value())
            .findFirst()
            .orElse(0);
    }

    public static <T> T requireNonNull(String name, T value) {
        if (value == null) {
            throw new IllegalArgumentException("Argument [" + name + "] cannot be null");
        }
        return value;
    }

    public static jakarta.ws.rs.core.MediaType convert(MediaType mediaType) {
        return mediaType == null ? null : jakarta.ws.rs.core.MediaType.valueOf(mediaType.toString());
    }

    public static MediaType convert(jakarta.ws.rs.core.MediaType mediaType) {
        return mediaType == null ? null : MediaType.of(mediaType.toString());
    }

}
