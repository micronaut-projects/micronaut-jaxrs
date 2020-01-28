package io.micronaut.jaxrs.runtime.ext.bind;

import io.micronaut.context.BeanContext;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import io.micronaut.jaxrs.runtime.annotation.ContextBindable;

import javax.inject.Singleton;

@Singleton
public class ContextAnnotationBinder<T> implements AnnotatedRequestArgumentBinder<ContextBindable, T> {

    private final BeanContext beanContext;

    public ContextAnnotationBinder(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public Class<ContextBindable> getAnnotationType() {
        return ContextBindable.class;
    }

    @Override
    public BindingResult<T> bind(ArgumentConversionContext<T> context, HttpRequest<?> source) {
        return () -> beanContext.findBean(context.getArgument().getType());
    }
}
