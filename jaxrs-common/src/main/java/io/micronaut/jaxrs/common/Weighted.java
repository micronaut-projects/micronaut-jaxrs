/*
 * Copyright 2017-2020 original authors
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
import io.micronaut.core.util.ArgumentUtils;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;

import java.util.Collections;
import java.util.Map;

/**
 * Used to compare objects be weighted value.
 *
 * @param <T> The object type
 * @author graemerocher
 * @since 1.0
 */
@Internal
final class Weighted<T> implements Comparable<Weighted<T>> {

    private final T object;
    private final float weight;

    /**
     * Constructor that takes parameters.
     *
     * @param object     The object
     * @param parameters An optional map of parameters. If a "q" variable is found try to use it to weigh.
     */
    public Weighted(T object, Map<String, String> parameters) {
        ArgumentUtils.requireNonNull("object", object);
        this.object = object;
        if (parameters != null) {
            final Object q = parameters.get("q");
            if (q != null) {
                try {
                    this.weight = Float.parseFloat(q.toString());
                } catch (NumberFormatException e) {
                    throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Invalid weight: " + q);
                }
                if (weight > 1.0f) {
                    throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Weight [" + q + "] cannot be greater than 1.0");
                }
            } else {
                weight = 1.0f;
            }
        } else {
            this.weight = 1.0f;
        }
    }

    /**
     * Default constructor.
     *
     * @param object The object
     * @param weight The weight
     */
    public Weighted(T object, float weight) {
        ArgumentUtils.requireNonNull("object", object);
        this.object = object;
        this.weight = weight;
    }

    /**
     * Construct with the default weight.
     *
     * @param object The Object
     */
    public Weighted(T object) {
        this(object, 1.0f);
    }

    /**
     * Parse the parameters that come after the semicolon.
     *
     * @param str The string
     * @return The parameters
     */
    public static Map<String, String> parseParameters(String str) {
        ArgumentUtils.requireNonNull("str", str);
        final int i = str.indexOf(';');
        if (i > -1) {
            return new ParameterParser().parse(str.substring(i), ';');
        } else {
            return Collections.emptyMap();
        }
    }

    /**
     * @return The object
     */
    public T getObject() {
        return object;
    }

    /**
     * @return The weight
     */
    public float getWeight() {
        return weight;
    }

    @Override
    public int compareTo(Weighted<T> o) {
        ArgumentUtils.requireNonNull("o", o);
        if (weight > o.weight) {
            return -1;
        } else if (weight < o.weight) {
            return 1;
        }
        return 0;
    }
}
