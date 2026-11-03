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
package io.micronaut.jaxrs.common.reflect;

import io.micronaut.core.annotation.Internal;

/**
 * The conversion of a String to a type of a JAX-RS parameter, with its public static
 * {@code valueOf(String)} or {@code fromString(String)} method, or its public constructor of a
 * String: the processor generates one for the types of the parameters of the resources, and
 * {@link MetadataJaxRsReflection} reads them as services, without reflection.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
public interface StringConversion {

    /**
     * @return The type the conversion creates
     */
    Class<?> type();

    /**
     * Convert a value.
     *
     * @param value The value
     * @return The converted value
     * @throws Exception The exception of the factory method or constructor
     */
    Object convert(String value) throws Exception;
}
