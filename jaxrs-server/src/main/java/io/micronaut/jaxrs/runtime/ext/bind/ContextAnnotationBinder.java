/*
 * Copyright 2017-2020 original authors
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
package io.micronaut.jaxrs.runtime.ext.bind;

import javax.ws.rs.core.Context;

import io.micronaut.context.BeanContext;
import io.micronaut.context.Qualifier;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import io.micronaut.inject.qualifiers.Qualifiers;

import jakarta.inject.Singleton;

/**
 * Handles the JAX-RS {@code Context} annotation binding.
 *
 * @param <T> The type to bind
 * @author graemerocher
 * @since 1.0
 */
@Singleton
public class ContextAnnotationBinder<T> implements AnnotatedRequestArgumentBinder<Context, T> {

    private final BeanContext beanContext;

    /**
     * Default constructor.
     * @param beanContext The bean context
     */
    protected ContextAnnotationBinder(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public Class<Context> getAnnotationType() {
        return Context.class;
    }

    @Override
    public BindingResult<T> bind(ArgumentConversionContext<T> context, HttpRequest<?> source) {
        Argument<T> argument = context.getArgument();
        Qualifier<T> qualifier = Qualifiers.forArgument(argument);
        return () -> beanContext.findBean(argument, qualifier);
    }
}
