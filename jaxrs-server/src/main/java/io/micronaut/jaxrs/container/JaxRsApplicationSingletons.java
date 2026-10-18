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

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.jaxrs.common.JaxRsRouteInterceptors;
import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import jakarta.inject.Singleton;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.WriterInterceptor;

import java.util.List;

/**
 * The provider instances of the {@link Application}, its {@code getSingletons()} (JAX-RS 2.3.2):
 * each one becomes the bean of its class, replacing the one the annotation processors generated,
 * so the filters, interceptors, exception mappers, readers, writers, parameter converter
 * providers and context resolvers of the application are its instances. The Java SE bootstrap
 * registers them before the context starts, see {@link JaxRsSeBootstrapServer}; an application
 * that is a bean registers them once it is created. The resources and features of the
 * application are its instances too, see {@link JaxRsRuntimeRoutes} and {@link JaxRsFeatures}.
 *
 * @author Denis Stepanov
 * @since 5.3.0
 */
@Internal
@Singleton
final class JaxRsApplicationSingletons implements BeanCreatedEventListener<Application> {

    private static final List<Class<?>> PROVIDER_CONTRACTS = List.of(
        ContainerRequestFilter.class, ContainerResponseFilter.class,
        ReaderInterceptor.class, WriterInterceptor.class,
        ExceptionMapper.class, MessageBodyReader.class, MessageBodyWriter.class,
        ParamConverterProvider.class, ContextResolver.class
    );

    private final ApplicationContext context;

    JaxRsApplicationSingletons(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public Application onCreated(BeanCreatedEvent<Application> event) {
        Application application = event.getBean();
        for (Object singleton : application.getSingletons()) {
            if (isProvider(singleton) && !isRegistered(singleton.getClass())) {
                // the @Context fields of the instance, like the ones of a bean of its class
                register(context.inject(singleton));
            }
        }
        return application;
    }

    /**
     * Register an instance as the bean of its class, replacing the one the annotation processors
     * generated, with the annotations and the type arguments of the provider contracts of the
     * class, which select it, e.g. the exception type of an {@code ExceptionMapper}.
     */
    @SuppressWarnings("unchecked")
    private <T> void register(T instance) {
        Class<T> type = (Class<T>) instance.getClass();
        BeanDefinition<T> generated = context.findBeanDefinition(type).orElse(null);
        RuntimeBeanDefinition.Builder<T> definition = RuntimeBeanDefinition.builder(type, () -> instance)
            .singleton(true)
            .replaces(type)
            // the annotations of the replaced bean, else of the class, which is not a bean
            .annotationMetadata(MutableAnnotationMetadata.of(generated == null
                ? JaxRsRouteInterceptors.metadata(type) : generated.getAnnotationMetadata()));
        for (Class<?> contract : PROVIDER_CONTRACTS) {
            if (!contract.isInstance(instance)) {
                continue;
            }
            List<Argument<?>> arguments = generated == null ? List.of() : generated.getTypeArguments(contract);
            if (arguments.isEmpty()) {
                Argument<?> resolved = JaxRsReflection.get().resolveGeneric(type, contract);
                arguments = resolved == null ? List.of() : List.of(resolved.getTypeParameters());
            }
            if (!arguments.isEmpty()) {
                definition.typeArguments(contract, arguments.toArray(Argument[]::new));
            }
        }
        context.registerBeanDefinition(definition.build());
    }

    private static boolean isProvider(Object singleton) {
        for (Class<?> contract : PROVIDER_CONTRACTS) {
            if (contract.isInstance(singleton)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an instance already is the bean of a class, e.g. registered by the Java SE bootstrap.
     */
    private boolean isRegistered(Class<?> type) {
        for (BeanDefinition<?> definition : context.getBeanDefinitions(type)) {
            if (definition instanceof RuntimeBeanDefinition<?> && definition.getBeanType() == type) {
                return true;
            }
        }
        return false;
    }
}
