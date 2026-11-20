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
package io.micronaut.jaxrs.container;

import io.micronaut.core.annotation.Internal;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import java.util.Set;

/**
 * The violations of the constraints of a resource found by the JAX-RS runtime, and whether they
 * are of a return value, which is a server error, else a client error (JAX-RS 7.6).
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
final class JaxRsConstraintViolationException extends ConstraintViolationException {

    private final boolean returnValue;

    /**
     * @param violations  The violations
     * @param returnValue Whether they are of a return value
     */
    JaxRsConstraintViolationException(Set<? extends ConstraintViolation<?>> violations, boolean returnValue) {
        super(violations);
        this.returnValue = returnValue;
    }

    /**
     * @return Whether the violations are of a return value
     */
    boolean isReturnValue() {
        return returnValue;
    }
}
