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

import io.micronaut.core.type.Argument;
import io.micronaut.core.type.Executable;
import org.jspecify.annotations.Nullable;

/**
 * The Bean Validation of the resources (JAX-RS chapter 7), in its phases (JAX-RS 7.6): the fields
 * and properties of a resource once it is created and injected, the parameters of a resource
 * method or sub-resource locator before it is called, including its bean parameters, and its
 * return value. Without an implementation, see {@link JaxRsBeanValidation}, nothing is validated.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
interface JaxRsResourceValidation {

    /**
     * Validates nothing: Bean Validation is not on the classpath.
     */
    JaxRsResourceValidation NONE = new JaxRsResourceValidation() {
        @Override
        public void validateResource(Object resource) {
        }

        @Override
        public void validateParameters(Object resource, String name, Executable<?, ?> method, Argument<?> returnType, @Nullable Object[] arguments) {
        }

        @Override
        public @Nullable Object validateReturnValue(Object resource, String name, Executable<?, ?> method, Argument<?> returnType, @Nullable Object result) {
            return result;
        }
    };

    /**
     * Validate the fields and properties of a resource, and its class constraints.
     *
     * @param resource The resource
     */
    void validateResource(Object resource);

    /**
     * Validate the parameters of a resource method or a sub-resource locator.
     *
     * @param resource   The resource
     * @param name       The name of the method
     * @param method     The method
     * @param returnType Its return type
     * @param arguments  The arguments it is called with
     */
    void validateParameters(Object resource, String name, Executable<?, ?> method, Argument<?> returnType, @Nullable Object[] arguments);

    /**
     * Validate the return value of a resource method: the value of a stage once it completes.
     *
     * @param resource   The resource
     * @param name       The name of the method
     * @param method     The method
     * @param returnType Its return type
     * @param result     What it returned
     * @return The result, or a stage that completes once its value is validated
     */
    @Nullable Object validateReturnValue(Object resource, String name, Executable<?, ?> method, Argument<?> returnType, @Nullable Object result);
}
