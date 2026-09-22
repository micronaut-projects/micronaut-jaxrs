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
import io.micronaut.core.reflect.InstantiationUtils;
import jakarta.inject.Singleton;
import jakarta.ws.rs.container.ResourceContext;

/**
 * The {@link ResourceContext} injected with {@code @Context}: resources are the beans of the
 * application, and other classes are instantiated and injected like beans.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
@Singleton
final class JaxRsContextResourceContext implements ResourceContext {

    private final BeanContext beanContext;

    JaxRsContextResourceContext(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public <T> T getResource(Class<T> resourceClass) {
        return beanContext.findBean(resourceClass)
            .orElseGet(() -> initResource(InstantiationUtils.instantiate(resourceClass)));
    }

    @Override
    public <T> T initResource(T resource) {
        return beanContext.inject(resource);
    }
}
