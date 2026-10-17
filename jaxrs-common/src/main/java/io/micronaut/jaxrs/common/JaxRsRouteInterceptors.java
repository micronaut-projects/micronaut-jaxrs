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
package io.micronaut.jaxrs.common;

import io.micronaut.context.BeanRegistration;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanIdentifier;
import io.micronaut.core.annotation.Internal;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.WriterInterceptor;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * The reader and writer interceptors registered for the resource method of the current request,
 * e.g. by a {@code DynamicFeature}, in addition to the interceptor beans.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
public interface JaxRsRouteInterceptors {

    /**
     * @return The reader interceptors of the resource method of the current request
     */
    List<ReaderInterceptor> readerInterceptors();

    /**
     * @return The writer interceptors of the resource method of the current request
     */
    List<WriterInterceptor> writerInterceptors();

    /**
     * The priority of an interceptor of the resource method: the one it was registered with, else
     * its {@code @Priority}.
     *
     * @param interceptor The interceptor
     * @param contract    The contract, {@link ReaderInterceptor} or {@link WriterInterceptor}
     * @return The priority
     */
    default int priority(Object interceptor, Class<?> contract) {
        return JaxRsUtils.getPriorityOrder(interceptor);
    }

    /**
     * The beans of a contract that are registered for it, with the priorities they were
     * registered with: a component a feature registered for other contracts only is not one of
     * them, see {@code FeatureContext#register(Class, Class[])}.
     *
     * @param beans    The beans
     * @param contract The contract, e.g. {@link WriterInterceptor}
     * @param <I>      The type of the contract
     * @return The beans registered for the contract
     */
    default <I> List<BeanRegistration<I>> registered(List<BeanRegistration<I>> beans, Class<I> contract) {
        return beans;
    }

    /**
     * The interceptor beans followed by the interceptors of a resource method, each with its
     * priority, which orders them.
     *
     * @param beans     The interceptor beans
     * @param forRoute  The interceptors of the resource method
     * @param priority  The priority of an interceptor of the resource method
     * @param <I>       The type of interceptor
     * @return The interceptors
     */
    static <I> List<BeanRegistration<I>> merge(List<BeanRegistration<I>> beans, List<I> forRoute, ToIntFunction<I> priority) {
        if (forRoute.isEmpty()) {
            return beans;
        }
        List<BeanRegistration<I>> all = new ArrayList<>(beans);
        for (I interceptor : forRoute) {
            all.add(registration(interceptor, priority.applyAsInt(interceptor)));
        }
        return all;
    }

    /**
     * The registration of an interceptor that is not a bean: it has no identifier and no bean
     * definition.
     *
     * @param interceptor The interceptor
     * @param <I>         The type of interceptor
     * @return The registration
     */
    @SuppressWarnings("NullAway") // BeanRegistration has no way to register an instance without a bean definition
    static <I> BeanRegistration<I> registration(I interceptor) {
        return registration(interceptor, JaxRsUtils.getPriorityOrder(interceptor));
    }

    /**
     * The registration of an interceptor that is not a bean, with its priority.
     *
     * @param interceptor The interceptor
     * @param priority    The priority
     * @param <I>         The type of interceptor
     * @return The registration
     */
    @SuppressWarnings("NullAway") // BeanRegistration has no way to register an instance without a bean definition
    static <I> BeanRegistration<I> registration(I interceptor, int priority) {
        return new Prioritized<>(null, null, interceptor, priority);
    }

    /**
     * The registration of a bean with the priority it was registered with, e.g. by a feature,
     * instead of its {@code @Priority}.
     *
     * @param registration The registration of the bean
     * @param priority     The priority
     * @param <I>          The type of the bean
     * @return The registration
     */
    static <I> BeanRegistration<I> registration(BeanRegistration<I> registration, int priority) {
        return new Prioritized<>(registration.getIdentifier(), registration.getBeanDefinition(), registration.getBean(), priority);
    }

    /**
     * The name binding predicate for merged interceptors: the ones of a resource method have no
     * bean definition and are always bound.
     *
     * @param predicate The predicate of the beans
     * @return The predicate
     */
    static NameBindingPredicate predicate(NameBindingPredicate predicate) {
        return metadata -> metadata == null || predicate.test(metadata);
    }

    /**
     * A registration with an explicit priority, see {@link JaxRsUtils#getPriorityOrder(BeanRegistration)}.
     *
     * @param <I> The type of the bean
     */
    final class Prioritized<I> extends BeanRegistration<I> {
        private final int priority;

        @SuppressWarnings("NullAway") // the registration of an instance that is not a bean has no bean definition
        Prioritized(@Nullable BeanIdentifier identifier, @Nullable BeanDefinition<I> definition, I bean, int priority) {
            super(identifier, definition, bean);
            this.priority = priority;
        }

        @Override
        public int getOrder() {
            return priority;
        }
    }
}
