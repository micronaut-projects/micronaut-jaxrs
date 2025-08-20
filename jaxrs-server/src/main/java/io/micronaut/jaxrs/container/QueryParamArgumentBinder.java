/*
 * Copyright 2017-2025 original authors
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

import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.bind.annotation.AbstractArgumentBinder;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionError;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.ConvertibleMultiValues;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import io.micronaut.http.bind.binders.RequestArgumentBinder;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;

/**
 * A binder for binding arguments annotated with {@link QueryParam}.
 *
 * @param <T> The argument type
 * @author Denis Stepanov
 * @since 4.10
 */
@Prototype
final class QueryParamArgumentBinder<T> extends AbstractArgumentBinder<T> implements AnnotatedRequestArgumentBinder<QueryParam, T> {

    private final List<ParamConverterProvider> paramConverterProviders;
    private final ParamConverter<T> paramConverter;

    /**
     * Constructor.
     *
     * @param conversionService       conversion service
     * @param paramConverterProviders param converter providers
     */
    public QueryParamArgumentBinder(ConversionService conversionService, List<ParamConverterProvider> paramConverterProviders) {
        super(conversionService);
        this.paramConverterProviders = paramConverterProviders;
        this.paramConverter = null;
    }

    /**
     * Constructor.
     *
     * @param conversionService conversion service
     * @param argument          The argument
     * @param paramConverter    The paramConverter
     */
    public QueryParamArgumentBinder(ConversionService conversionService, Argument<T> argument, @Nullable ParamConverter<T> paramConverter) {
        super(conversionService, argument);
        this.paramConverterProviders = List.of();
        this.paramConverter = paramConverter;

    }

    @Override
    public RequestArgumentBinder<T> createSpecific(Argument<T> argument) {
        ParamConverter<T> paramConverter = null;
        if (!paramConverterProviders.isEmpty()) {
            Class<T> rawType = argument.getType();
            Type type = argument.asType();
            Annotation[] annotations = argument.synthesizeAll();
            for (ParamConverterProvider paramConverterProvider : paramConverterProviders) {
                paramConverter = paramConverterProvider.getConverter(rawType, type, annotations);
                if (paramConverter != null) {
                    break;
                }
            }
        }
        return new QueryParamArgumentBinder<>(conversionService, argument, paramConverter);
    }

    @Override
    public Class<QueryParam> getAnnotationType() {
        return QueryParam.class;
    }

    @Override
    public BindingResult<T> bind(ArgumentConversionContext<T> context, HttpRequest<?> source) {
        ConvertibleMultiValues<String> parameters = source.getParameters();
        Argument<T> argument = context.getArgument();
        AnnotationMetadata annotationMetadata = argument.getAnnotationMetadata();

        if (source.getMethod().permitsRequestBody() && !annotationMetadata.hasAnnotation(QueryParam.class)) {
            // During the unmatched check avoid requests that don't allow bodies
            return BindingResult.unsatisfied();
        }

        String parameterName = resolvedParameterName(argument);
        if (paramConverter != null) {
            String value = parameters.get(parameterName);
            try {
                T result = paramConverter.fromString(value);
                return () -> Optional.ofNullable(result);
            } catch (Exception e) {
                return new BindingResult<>() {
                    @Override
                    public Optional<T> getValue() {
                        return Optional.empty();
                    }

                    @Override
                    public List<ConversionError> getConversionErrors() {
                        return List.of(() -> e);
                    }
                };
            }
        }
        return doBind(context, parameters, BindingResult.unsatisfied());
    }

    @Override
    protected String getParameterName(Argument<T> argument) {
        return argument.getAnnotationMetadata()
            .stringValue(QueryParam.class).
            orElseThrow(() -> new IllegalStateException("Missing @QueryParam annotation on argument: " + argument));
    }
}
