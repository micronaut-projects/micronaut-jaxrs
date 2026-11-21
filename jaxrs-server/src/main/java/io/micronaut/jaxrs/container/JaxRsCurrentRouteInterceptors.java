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

import io.micronaut.context.BeanRegistration;
import io.micronaut.core.annotation.Internal;
import io.micronaut.jaxrs.common.JaxRsRouteInterceptors;
import io.micronaut.jaxrs.common.NameBindingPredicate;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.WriterInterceptor;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The interceptors of the routes, of the application of the current request, see
 * {@link JaxRsFeatures}.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
@Singleton
final class JaxRsCurrentRouteInterceptors implements JaxRsRouteInterceptors {

    private final JaxRsApplications applications;

    JaxRsCurrentRouteInterceptors(JaxRsApplications applications) {
        this.applications = applications;
    }

    private JaxRsFeatures current() {
        return applications.current().features();
    }

    @Override
    public List<ReaderInterceptor> readerInterceptors() {
        return current().readerInterceptors();
    }

    @Override
    public List<WriterInterceptor> writerInterceptors() {
        return current().writerInterceptors();
    }

    @Override
    public int priority(Object interceptor, Class<?> contract) {
        return current().priority(interceptor, contract);
    }

    @Override
    public <I> List<BeanRegistration<I>> registered(List<BeanRegistration<I>> beans, Class<I> contract) {
        return current().registered(beans, contract);
    }

    @Override
    public <I> @Nullable List<I> bound(List<BeanRegistration<I>> beans, Class<I> contract, NameBindingPredicate predicate) {
        return current().bound(beans, contract, predicate);
    }
}
