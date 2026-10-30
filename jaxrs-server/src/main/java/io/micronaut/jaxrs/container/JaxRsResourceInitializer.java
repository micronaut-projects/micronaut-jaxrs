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

import io.micronaut.context.event.BeanInitializedEventListener;
import io.micronaut.context.event.BeanInitializingEvent;
import io.micronaut.core.annotation.Internal;
import jakarta.inject.Singleton;

/**
 * Sets the fields and setters of a resource bean created for a request with the values of the
 * request once it is injected, before its {@code @PostConstruct} methods run (Jakarta
 * Annotations 3.5), see {@link JaxRsResourceFactory#create}.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
@Singleton
final class JaxRsResourceInitializer implements BeanInitializedEventListener<Object> {

    @Override
    public Object onInitialized(BeanInitializingEvent<Object> event) {
        Object bean = event.getBean();
        JaxRsResourceFactory.initializing(bean);
        return bean;
    }
}
