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

import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.core.reflect.InstantiationUtils;
import io.micronaut.reflection.ReflectionBeanIntrospection;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.Application;

/**
 * The {@link Application} named by the {@value #APPLICATION} property, like the
 * {@code jakarta.ws.rs.Application} parameter of a servlet names it (JAX-RS 2.3.2): for an
 * {@code Application} subclass without {@code @ApplicationPath}, which is not a bean. An
 * {@code Application} that is a bean is used instead.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Factory
@Internal
final class JaxRsApplicationFactory {

    /**
     * The property that names the {@code Application} class.
     */
    static final String APPLICATION = "micronaut.jaxrs.application";

    @Singleton
    // an Application that is a bean comes first, then this one, then the default JaxRsApplication
    @Order(Ordered.LOWEST_PRECEDENCE - 1)
    @Requires(property = APPLICATION)
    Application application(@Property(name = APPLICATION) String className, BeanContext beanContext) {
        Class<?> type = ClassUtils.forName(className, beanContext.getClassLoader())
            .orElseThrow(() -> new ConfigurationException("The Application class " + className + " of " + APPLICATION + " is not found"));
        if (!Application.class.isAssignableFrom(type)) {
            throw new ConfigurationException("The class " + className + " of " + APPLICATION + " is not an Application");
        }
        // a class the annotation processors did not make a bean: instantiated from a reflective
        // introspection, which reaches a class or constructor that is not public
        Object application = ReflectionBeanIntrospection.isIntrospectable(type)
            ? ReflectionBeanIntrospection.of(type).instantiate()
            : InstantiationUtils.instantiate(type);
        return (Application) beanContext.inject(application);
    }
}
