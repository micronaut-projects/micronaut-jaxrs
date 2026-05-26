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

import io.micronaut.core.bind.annotation.AbstractArgumentBinder;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.ConvertibleMultiValues;
import io.micronaut.core.convert.value.ConvertibleMultiValuesMap;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import io.micronaut.http.bind.binders.RequestArgumentBinder;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Base support for Jakarta REST string parameter binders that use {@link ParamConverterProvider}.
 *
 * @param <A> The annotation type
 * @param <T> The argument type
 */
abstract class AbstractParamArgumentBinder<A extends Annotation, T> extends AbstractArgumentBinder<T> implements AnnotatedRequestArgumentBinder<A, T> {

    private final List<ParamConverterProvider> paramConverterProviders;
    private final ParamConverter<T> paramConverter;
    private final ParamConverter<?> elementParamConverter;

    AbstractParamArgumentBinder(ConversionService conversionService, List<ParamConverterProvider> paramConverterProviders) {
        super(conversionService);
        this.paramConverterProviders = paramConverterProviders;
        this.paramConverter = null;
        this.elementParamConverter = null;
    }

    AbstractParamArgumentBinder(ConversionService conversionService,
                                Argument<T> argument,
                                @Nullable ParamConverter<T> paramConverter,
                                @Nullable ParamConverter<?> elementParamConverter) {
        super(conversionService, argument);
        this.paramConverterProviders = List.of();
        this.paramConverter = paramConverter;
        this.elementParamConverter = elementParamConverter;
    }

    @Override
    public final RequestArgumentBinder<T> createSpecific(Argument<T> argument) {
        ParamConverter<T> paramConverter = null;
        ParamConverter<?> elementParamConverter = null;
        if (!paramConverterProviders.isEmpty()) {
            Class<T> rawType = argument.getType();
            Type type = argument.asType();
            Annotation[] annotations = argument.synthesizeAll();
            paramConverter = findParamConverter(rawType, type, annotations);
            if (paramConverter == null) {
                Argument<?> elementArgument = elementArgument(argument);
                if (elementArgument != null) {
                    elementParamConverter = findParamConverter(elementArgument.getType(), elementArgument.asType(), annotations);
                }
            }
        }
        return createSpecific(argument, paramConverter, elementParamConverter);
    }

    @Override
    public final BindingResult<T> bind(ArgumentConversionContext<T> context, HttpRequest<?> source) {
        Argument<T> argument = context.getArgument();
        if (!isBindable(argument, source)) {
            return BindingResult.unsatisfied();
        }
        String parameterName = resolvedParameterName(argument);
        BindingResult<T> directResult = bindDirect(context, source, parameterName);
        if (directResult != null) {
            return directResult;
        }
        ConvertibleMultiValues<String> values = parameterValues(source, argument);
        if (paramConverter != null) {
            String value = valueOrDefault(values.get(parameterName), argument);
            if (value == null) {
                return BindingResult.unsatisfied();
            }
            T result = convert(paramConverter, value);
            return () -> Optional.ofNullable(result);
        }
        if (elementParamConverter != null) {
            return bindConvertedElements(argument, values.getAll(parameterName), valueOrDefault(null, argument));
        }
        List<String> allValues = values.getAll(parameterName);
        if (allValues.size() > 1 && !isMultiValue(argument)) {
            return doBind(context, singleValueParameters(parameterName, allValues.get(0)), BindingResult.unsatisfied());
        }
        return doBind(context, values, BindingResult.unsatisfied());
    }

    protected boolean isBindable(Argument<T> argument, HttpRequest<?> source) {
        return true;
    }

    @Override
    protected final String getParameterName(Argument<T> argument) {
        Class<A> annotationType = getAnnotationType();
        return argument.getAnnotationMetadata()
            .stringValue(annotationType)
            .orElseThrow(() -> new IllegalStateException("Missing @" + annotationType.getSimpleName() + " annotation on argument: " + argument));
    }

    protected abstract ConvertibleMultiValues<String> parameterValues(HttpRequest<?> source, Argument<T> argument);

    protected @Nullable BindingResult<T> bindDirect(ArgumentConversionContext<T> context, HttpRequest<?> source, String parameterName) {
        return null;
    }

    protected abstract RequestArgumentBinder<T> createSpecific(Argument<T> argument,
                                                              @Nullable ParamConverter<T> paramConverter,
                                                              @Nullable ParamConverter<?> elementParamConverter);

    protected abstract RuntimeException conversionException(RuntimeException exception);

    @SuppressWarnings("unchecked")
    private <E> @Nullable ParamConverter<E> findParamConverter(Class<E> rawType, Type type, Annotation[] annotations) {
        for (ParamConverterProvider paramConverterProvider : paramConverterProviders) {
            ParamConverter<E> converter = paramConverterProvider.getConverter(rawType, type, annotations);
            if (converter != null) {
                return converter;
            }
        }
        return null;
    }

    private @Nullable Argument<?> elementArgument(Argument<T> argument) {
        if (argument.getType().isArray()) {
            return Argument.of(argument.getType().getComponentType());
        }
        if (Iterable.class.isAssignableFrom(argument.getType())) {
            Argument<?>[] typeParameters = argument.getTypeParameters();
            if (typeParameters.length == 1) {
                return typeParameters[0];
            }
        }
        return null;
    }

    private boolean isMultiValue(Argument<T> argument) {
        return argument.getType().isArray() || Iterable.class.isAssignableFrom(argument.getType());
    }

    private ConvertibleMultiValues<String> singleValueParameters(String parameterName, @Nullable String value) {
        Map<CharSequence, List<String>> values = new LinkedHashMap<>();
        values.put(parameterName, Collections.singletonList(value));
        return new ConvertibleMultiValuesMap<>(values, conversionService);
    }

    protected final @Nullable String valueOrDefault(@Nullable String value, Argument<T> argument) {
        if (value != null) {
            return value;
        }
        return argument.getAnnotationMetadata().stringValue(Bindable.class, "defaultValue").orElse(null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private BindingResult<T> bindConvertedElements(Argument<T> argument, List<String> values, @Nullable String defaultValue) {
        if (values.isEmpty()) {
            if (defaultValue == null) {
                return BindingResult.unsatisfied();
            }
            values = List.of(defaultValue);
        }
        List<Object> convertedValues = new ArrayList<>(values.size());
        for (String value : values) {
            convertedValues.add(convert((ParamConverter) elementParamConverter, value));
        }
        return () -> conversionService.convert(convertedValues, argument);
    }

    private <E> E convert(ParamConverter<E> converter, @Nullable String value) {
        try {
            return converter.fromString(value);
        } catch (WebApplicationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw conversionException(e);
        }
    }
}
