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
package io.micronaut.jaxrs.common;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.InterceptorContext;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An abstract implementation of {@link InterceptorContext}.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
abstract sealed class AbstractJaxRsInterceptorContext implements InterceptorContext permits JaxRsReaderInterceptorContext, JaxRsWriterInterceptorContext {

    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final Argument<?> argument;
    private MediaType mediaType;
    private Class<?> type;
    private Type genericType;
    private Annotation[] annotations;

    protected AbstractJaxRsInterceptorContext(Argument<?> argument, MediaType mediaType) {
        this.argument = argument;
        this.mediaType = mediaType;
    }

    public final <T> Argument<T> asArgument() {
        if (type == null && genericType == null && annotations == null) {
            return (Argument<T>) argument;
        }
        AnnotationMetadata annotationMetadata = annotations == null ? argument.getAnnotationMetadata() : JaxRsArgumentUtil.createAnnotationMetadata(annotations);
        if (genericType != null) {
            Argument<?> genericArgument = Argument.of(genericType);
            return (Argument<T>) Argument.of(genericArgument.getType(),
                annotationMetadata,
                genericArgument.getTypeParameters()
            );
        }
        if (type != null) {
            return (Argument<T>) Argument.of(type, annotationMetadata);
        }
        return (Argument<T>) Argument.of(argument.getType(), annotationMetadata, argument.getTypeParameters());
    }

    @Override
    public boolean hasProperty(String name) {
        return properties.containsKey(name);
    }

    @Override
    public Object getProperty(String name) {
        return properties.get(name);
    }

    @Override
    public Collection<String> getPropertyNames() {
        return properties.keySet();
    }

    @Override
    public void setProperty(String name, Object object) {
        properties.put(name, object);
    }

    @Override
    public void removeProperty(String name) {
        properties.remove(name);
    }

    @Override
    public Annotation[] getAnnotations() {
        if (annotations == null) {
            return argument.getAnnotationMetadata().synthesizeAll();
        }
        return annotations;
    }

    @Override
    public void setAnnotations(Annotation[] annotations) {
        Objects.requireNonNull(annotations);
        this.annotations = annotations;
    }

    @Override
    public Class<?> getType() {
        if (type == null) {
            return argument.getType();
        }
        return type;
    }

    @Override
    public void setType(Class<?> type) {
        this.type = type;
    }

    @Override
    public Type getGenericType() {
        if (genericType == null) {
            return argument.asType();
        }
        return genericType;
    }

    @Override
    public void setGenericType(Type genericType) {
        this.genericType = genericType;
    }

    @Override
    public MediaType getMediaType() {
        return mediaType;
    }

    @Override
    public void setMediaType(MediaType mediaType) {
        this.mediaType = mediaType;
    }

}
