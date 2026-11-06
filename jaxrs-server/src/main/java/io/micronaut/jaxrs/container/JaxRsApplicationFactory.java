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

import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.reflect.ClassUtils;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.Application;
import org.jspecify.annotations.Nullable;

/**
 * The {@link Application} named by the {@value #APPLICATION} property, like the
 * {@code jakarta.ws.rs.Application} parameter of a servlet names it (JAX-RS 2.3.2): for an
 * {@code Application} subclass without {@code @ApplicationPath}, which is not a bean, or the
 * class the Java SE bootstrap starts. The named class is the application, over the other
 * {@code Application} beans: its bean, when it is one.
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

    /**
     * @param beanContext The context
     * @return The class the {@value #APPLICATION} property names, {@code null} if there is none
     */
    static @Nullable Class<?> applicationClass(BeanContext beanContext) {
        return beanContext instanceof ApplicationContext context
            ? context.getProperty(APPLICATION, String.class)
                .flatMap(name -> ClassUtils.forName(name, beanContext.getClassLoader()))
                .orElse(null)
            : null;
    }

    @Singleton
    // the named application, over the other Application beans and the default JaxRsApplication
    @Primary
    @Requires(property = APPLICATION)
    Application application(@Property(name = APPLICATION) String className, BeanContext beanContext) {
        Class<?> type = ClassUtils.forName(className, beanContext.getClassLoader())
            .orElseThrow(() -> new ConfigurationException("The Application class " + className + " of " + APPLICATION + " is not found"));
        if (!Application.class.isAssignableFrom(type)) {
            throw new ConfigurationException("The class " + className + " of " + APPLICATION + " is not an Application");
        }
        if (beanContext.containsBean(type)) {
            // the bean of the class, created and injected like any bean
            return (Application) beanContext.getBean(type);
        }
        // a class the annotation processors did not make a bean
        Object application = JaxRsReflection.get().instantiate(type);
        return (Application) beanContext.inject(application);
    }
}
