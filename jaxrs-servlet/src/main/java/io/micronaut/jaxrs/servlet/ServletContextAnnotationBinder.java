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
package io.micronaut.jaxrs.servlet;

import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder;
import io.micronaut.jaxrs.runtime.ext.bind.ContextAnnotationBinder;
import io.micronaut.servlet.http.ServletHttpRequest;
import jakarta.inject.Singleton;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Collection;
import java.util.Optional;

/**
 * Extends and replaces the context annotation binder to support servlet types.
 *
 * @param <T> The generic type.
 * @author graemerocher
 * @since 4.6.0
 */
@Singleton
@Replaces(ContextAnnotationBinder.class)
public class ServletContextAnnotationBinder<T> extends ContextAnnotationBinder<T> {

    /**
     * Constructor to create a Context binder using all passed in argument binders.
     *
     * @param beanContext     The bean context
     * @param argumentBinders The argument binders
     * @since 3.3.0
     */
    protected ServletContextAnnotationBinder(BeanContext beanContext, Collection<TypedRequestArgumentBinder<?>> argumentBinders) {
        super(beanContext, argumentBinders);
    }

    @Override
    public BindingResult<T> bind(ArgumentConversionContext<T> context, HttpRequest<?> source) {
        Class<T> type = context.getArgument().getType();
        if (source instanceof ServletHttpRequest<?, ?> servletHttpRequest) {
            Object nativeRequest = servletHttpRequest.getNativeRequest();
            if (nativeRequest instanceof HttpServletRequest httpServletRequest) {
                if (ServletContext.class.equals(type)) {
                    ServletContext servletContext = httpServletRequest.getServletContext();
                    return () -> (Optional<T>) Optional.ofNullable(servletContext);
                }
            }
        }
        return super.bind(context, source);
    }
}
