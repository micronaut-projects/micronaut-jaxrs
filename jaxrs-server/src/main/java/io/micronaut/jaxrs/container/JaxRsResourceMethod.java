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
package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Executable;
import io.micronaut.core.annotation.Internal;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public JAX-RS resource method or sub-resource locator (JAX-RS 3.3.1): the annotation
 * mappers of the JAX-RS HTTP methods and of {@code @Path} add it. The method is executable and
 * processed on startup by {@link JaxRsRuntimeRoutes}, like {@code @HttpMethodMapping} makes the
 * methods of a controller executable.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Executable(processOnStartup = true)
// an implementation of a resource method of an interface or superclass is one too, like it is a
// controller method with @HttpMethodMapping
@Inherited
public @interface JaxRsResourceMethod {
}
