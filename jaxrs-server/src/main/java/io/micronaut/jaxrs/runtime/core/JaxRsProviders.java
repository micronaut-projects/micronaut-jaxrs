package io.micronaut.jaxrs.runtime.core;

import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.List;

@Internal
@Singleton
public final class JaxRsProviders implements Providers {
    private final BeanContext beanContext;

    public JaxRsProviders(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public <T> MessageBodyReader<T> getMessageBodyReader(Class<T> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public <T> MessageBodyWriter<T> getMessageBodyWriter(Class<T> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        throw new UnsupportedOperationException("Not supported");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public <T extends Throwable> ExceptionMapper<T> getExceptionMapper(Class<T> type) {
        Iterator<BeanDefinition<ExceptionMapper>> iterator = beanContext.getBeanDefinitions(ExceptionMapper.class).stream()
            .filter(d -> {
                Argument<?> t = getExceptionType(d);
                return t == null || t.isAssignableFrom(type);
            })
            .iterator();
        if (!iterator.hasNext()) {
            return null;
        }
        BeanDefinition<ExceptionMapper> mostSpecificExceptionMapper = iterator.next();
        Argument<?> mostSpecificTypeVariable = getExceptionType(mostSpecificExceptionMapper);
        while (iterator.hasNext()) {
            BeanDefinition<ExceptionMapper> mapper = iterator.next();
            Argument<?> typeVar = getExceptionType(mapper);
            if (typeVar == null) {
                continue;
            }
            if (mostSpecificTypeVariable == null || mostSpecificTypeVariable.isAssignableFrom(typeVar)) {
                mostSpecificExceptionMapper = mapper;
                mostSpecificTypeVariable = typeVar;
            }
        }
        return beanContext.getBean(mostSpecificExceptionMapper);
    }

    private Argument<?> getExceptionType(BeanDefinition<ExceptionMapper> definition) {
        List<Argument<?>> args = definition.getTypeArguments(ExceptionMapper.class);
        return args.isEmpty() ? null : args.get(0);
    }

    @Override
    public <T> ContextResolver<T> getContextResolver(Class<T> contextType, MediaType mediaType) {
        // "null if no matching context providers are found"
        return null;
    }
}
