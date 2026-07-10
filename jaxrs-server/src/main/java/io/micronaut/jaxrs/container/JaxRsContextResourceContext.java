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
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.reflect.exception.InstantiationException;
import jakarta.inject.Singleton;
import jakarta.ws.rs.container.ResourceContext;

import java.util.List;
import java.util.Optional;

/**
 * Server-side Jakarta REST resource context.
 */
@Internal
@Singleton
final class JaxRsContextResourceContext implements ResourceContext {

    private final BeanContext beanContext;
    private final List<JaxRsProviderInstantiator> providerInstantiators;

    JaxRsContextResourceContext(BeanContext beanContext,
                                List<JaxRsProviderInstantiator> providerInstantiators) {
        this.beanContext = beanContext;
        this.providerInstantiators = providerInstantiators;
    }

    @Override
    public <T> T getResource(Class<T> resourceClass) {
        Optional<T> bean = beanContext.findBean(resourceClass);
        if (bean.isPresent()) {
            return bean.get();
        }
        Optional<BeanIntrospection<T>> introspection = (Optional<BeanIntrospection<T>>) (Optional<?>)
            BeanIntrospector.forClassLoader(beanContext.getClassLoader()).findIntrospection(resourceClass);
        if (introspection.isPresent()) {
            try {
                return initResource(introspection.get().instantiate());
            } catch (InstantiationException e) {
                throw new IllegalStateException("Cannot instantiate Jakarta REST resource " + resourceClass.getName(), e);
            }
        }
        for (JaxRsProviderInstantiator providerInstantiator : providerInstantiators) {
            Optional<Object> instantiated = providerInstantiator.instantiate(resourceClass);
            if (instantiated.isPresent()) {
                return initResource(resourceClass.cast(instantiated.get()));
            }
        }
        throw new IllegalStateException("Cannot instantiate Jakarta REST resource " +
            resourceClass.getName() + " without a bean definition or BeanIntrospection");
    }

    @Override
    public <T> T initResource(T resource) {
        return beanContext.inject(resource);
    }
}
