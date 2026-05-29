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
package io.micronaut.jaxrs.reflection;

import io.micronaut.context.BeanProvider;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.bind.ArgumentBinder;
import io.micronaut.core.bind.exceptions.UnsatisfiedArgumentException;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.ConversionError;
import io.micronaut.core.convert.exceptions.ConversionErrorException;
import io.micronaut.core.naming.Named;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.RequestBean;
import io.micronaut.http.bind.RequestBinderRegistry;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import io.micronaut.http.bind.binders.PostponedRequestArgumentBinder;
import io.micronaut.http.bind.binders.RequestArgumentBinder;
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * Classloader-aware {@link RequestBean} binder for Jakarta REST compliance deployments.
 */
@Internal
@Singleton
final class JaxRsRequestBeanAnnotationBinder implements AnnotatedRequestArgumentBinder<RequestBean, Object>,
    TypedRequestArgumentBinder<Object>, PostponedRequestArgumentBinder<Object> {
    private static final ConcurrentMap<ClassLoader, BeanIntrospector> INTROSPECTORS = new ConcurrentHashMap<>();

    private final BeanProvider<RequestBinderRegistry> requestBinderRegistryProvider;
    private final @Nullable BeanIntrospection<Object> introspection;

    JaxRsRequestBeanAnnotationBinder(BeanProvider<RequestBinderRegistry> requestBinderRegistryProvider) {
        this(requestBinderRegistryProvider, null);
    }

    private JaxRsRequestBeanAnnotationBinder(BeanProvider<RequestBinderRegistry> requestBinderRegistryProvider,
                                             @Nullable BeanIntrospection<Object> introspection) {
        this.requestBinderRegistryProvider = requestBinderRegistryProvider;
        this.introspection = introspection;
    }

    @Override
    public Class<RequestBean> getAnnotationType() {
        return RequestBean.class;
    }

    @Override
    public Argument<Object> argumentType() {
        return Argument.OBJECT_ARGUMENT;
    }

    @Override
    @SuppressWarnings("unchecked")
    public RequestArgumentBinder<Object> createSpecific(Argument<Object> argument) {
        Class<Object> type = (Class<Object>) argument.getType();
        ClassLoader classLoader = type.getClassLoader();
        BeanIntrospection<Object> beanIntrospection = classLoader == null
            ? BeanIntrospection.getIntrospection(type)
            : (BeanIntrospection<Object>) INTROSPECTORS.computeIfAbsent(classLoader, BeanIntrospector::forClassLoader).getIntrospection(type);
        return new JaxRsRequestBeanAnnotationBinder(requestBinderRegistryProvider, beanIntrospection);
    }

    @Override
    @SuppressWarnings("unchecked")
    public BindingResult<Object> bind(ArgumentConversionContext<Object> context, HttpRequest<?> source) {
        AnnotationMetadata annotationMetadata = context.getArgument().getAnnotationMetadata();
        if (!annotationMetadata.hasAnnotation(RequestBean.class)) {
            return BindingResult.EMPTY;
        }
        BeanIntrospection<Object> resolvedIntrospection = introspection;
        if (resolvedIntrospection == null) {
            resolvedIntrospection = BeanIntrospection.getIntrospection(context.getArgument().getType());
        }
        BeanIntrospection<Object> beanIntrospection = resolvedIntrospection;
        Map<String, BeanProperty<Object, Object>> beanProperties = beanIntrospection.getBeanProperties().stream()
            .collect(Collectors.toMap(Named::getName, property -> property));

        if (beanIntrospection.getConstructorArguments().length > 0) {
            Argument<?>[] constructorArguments = beanIntrospection.getConstructorArguments();
            Object[] argumentValues = new Object[constructorArguments.length];
            for (int i = 0; i < constructorArguments.length; i++) {
                @SuppressWarnings("unchecked")
                Argument<Object> constructorArgument = (Argument<Object>) constructorArguments[i];
                BeanProperty<Object, Object> beanProperty = beanProperties.get(constructorArgument.getName());
                Argument<Object> argumentToBind = beanProperty == null ? constructorArgument : beanProperty.asArgument();
                Optional<Object> bindableResult = getBindableResult(source, argumentToBind);
                argumentValues[i] = constructorArgument.isOptional() ? bindableResult : bindableResult.orElse(null);
            }
            return () -> Optional.of(beanIntrospection.instantiate(false, argumentValues));
        }

        Object bean = beanIntrospection.instantiate();
        for (BeanProperty<Object, Object> property : beanProperties.values()) {
            Argument<Object> propertyArgument = property.asArgument();
            Optional<Object> bindableResult = getBindableResult(source, propertyArgument);
            property.set(bean, propertyArgument.isOptional() ? bindableResult : bindableResult.orElse(null));
        }
        return () -> Optional.of(bean);
    }

    private Optional<Object> getBindableResult(HttpRequest<?> source, Argument<Object> argument) {
        ArgumentConversionContext<Object> conversionContext = ConversionContext.of(
            argument,
            source.getLocale().orElse(Locale.getDefault()),
            source.getCharacterEncoding()
        );
        Optional<ArgumentBinder<Object, HttpRequest<?>>> binder = requestBinderRegistryProvider.get().findArgumentBinder(argument);
        if (binder.isEmpty()) {
            throw new UnsatisfiedArgumentException(argument);
        }
        BindingResult<Object> result = binder.get().bind(conversionContext, source);
        if (!result.isSatisfied() || !result.getConversionErrors().isEmpty()) {
            List<ConversionError> errors = result.getConversionErrors();
            if (!errors.isEmpty()) {
                throw new ConversionErrorException(argument, errors.iterator().next());
            }
        }
        if (!result.isPresentAndSatisfied() && !argument.isNullable() && !argument.getType().isAssignableFrom(Optional.class)) {
            throw new UnsatisfiedArgumentException(argument);
        }
        return result.getValue();
    }
}
