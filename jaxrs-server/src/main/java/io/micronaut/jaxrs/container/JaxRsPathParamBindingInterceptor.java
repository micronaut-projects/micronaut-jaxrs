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

import io.micronaut.aop.InterceptorBean;
import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.bind.ArgumentBinder;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.exceptions.ConversionErrorException;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.MutableArgumentValue;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.RequestBinderRegistry;
import io.micronaut.http.context.ServerRequestContext;
import jakarta.inject.Singleton;
import jakarta.ws.rs.PathParam;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Rebinds Jakarta REST path parameters before invoking the resource method.
 */
@Internal
@Singleton
@InterceptorBean(JaxRsPathParamBinding.class)
final class JaxRsPathParamBindingInterceptor implements MethodInterceptor<Object, Object> {

    private final RequestBinderRegistry requestBinderRegistry;

    JaxRsPathParamBindingInterceptor(RequestBinderRegistry requestBinderRegistry) {
        this.requestBinderRegistry = requestBinderRegistry;
    }

    @Override
    public @Nullable Object intercept(MethodInvocationContext<Object, Object> context) {
        ServerRequestContext.currentRequest().ifPresent(request -> bindPathParams(context, request));
        return context.proceed();
    }

    private void bindPathParams(MethodInvocationContext<Object, Object> context, HttpRequest<?> request) {
        for (MutableArgumentValue<?> parameter : context.getParameters().values()) {
            if (requiresJaxRsBinding(parameter)) {
                bindPathParam(parameter, request);
            }
        }
    }

    private static boolean requiresJaxRsBinding(Argument<?> argument) {
        if (!argument.getAnnotationMetadata().hasAnnotation(PathParam.class)) {
            return false;
        }
        return argument.getType() != String.class
            || argument.isContainerType()
            || argument.getAnnotationMetadata().hasAnnotation(jakarta.ws.rs.Encoded.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void bindPathParam(MutableArgumentValue<T> parameter, HttpRequest<?> request) {
        Argument<T> argument = (Argument<T>) parameter;
        ArgumentBinder<T, HttpRequest<?>> binder = (ArgumentBinder<T, HttpRequest<?>>) requestBinderRegistry.findArgumentBinder(argument)
            .orElseThrow(() -> new IllegalStateException("No request argument binder for Jakarta REST path parameter [" + argument + "]"));
        ArgumentBinder.BindingResult<T> result = binder.bind(ConversionContext.of(argument), request);
        if (!result.getConversionErrors().isEmpty()) {
            throw new ConversionErrorException(argument, result.getConversionErrors().get(0));
        }
        Optional<T> value = result.getValue();
        if (value.isPresent()) {
            parameter.setValue(value.get());
        }
    }
}
