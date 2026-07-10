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
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.ConvertibleMultiValues;
import io.micronaut.core.convert.value.ConvertibleMultiValuesMap;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.RequestArgumentBinder;
import io.micronaut.jaxrs.runtime.ext.bind.UriInfoImpl;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A binder for binding arguments annotated with {@link QueryParam}.
 *
 * @param <T> The argument type
 * @author Denis Stepanov
 * @since 4.10
 */
@Prototype
final class QueryParamArgumentBinder<T> extends AbstractParamArgumentBinder<QueryParam, T> {

    /**
     * Constructor.
     *
     * @param conversionService       conversion service
     * @param paramConverterProviders param converter providers
     */
    public QueryParamArgumentBinder(ConversionService conversionService, List<ParamConverterProvider> paramConverterProviders) {
        super(conversionService, paramConverterProviders);
    }

    /**
     * Constructor.
     *
     * @param conversionService conversion service
     * @param argument          The argument
     * @param paramConverter    The paramConverter
     * @param elementParamConverter The element paramConverter
     */
    public QueryParamArgumentBinder(ConversionService conversionService,
                                    Argument<T> argument,
                                    @Nullable ParamConverter<T> paramConverter,
                                    @Nullable ParamConverter<?> elementParamConverter) {
        super(conversionService, argument, paramConverter, elementParamConverter);
    }

    @Override
    public Class<QueryParam> getAnnotationType() {
        return QueryParam.class;
    }

    @Override
    protected boolean isBindable(Argument<T> argument, HttpRequest<?> source) {
        AnnotationMetadata annotationMetadata = argument.getAnnotationMetadata();
        // During the unmatched check avoid requests that don't allow bodies.
        return !source.getMethod().permitsRequestBody() || annotationMetadata.hasAnnotation(QueryParam.class);
    }

    @Override
    protected ConvertibleMultiValues<String> parameterValues(HttpRequest<?> source, Argument<T> argument) {
        if (!argument.getAnnotationMetadata().hasAnnotation(Encoded.class) || source.getUri().getRawQuery() == null) {
            return source.getParameters();
        }
        Map<CharSequence, List<String>> values = new LinkedHashMap<>();
        UriInfoImpl.getEncodedParameters(source.getUri()).forEach(values::put);
        return new ConvertibleMultiValuesMap<>(values, conversionService);
    }

    @Override
    protected RequestArgumentBinder<T> createSpecific(Argument<T> argument,
                                                      @Nullable ParamConverter<T> paramConverter,
                                                      @Nullable ParamConverter<?> elementParamConverter) {
        return new QueryParamArgumentBinder<>(conversionService, argument, paramConverter, elementParamConverter);
    }

    @Override
    protected RuntimeException conversionException(RuntimeException exception) {
        return new NotFoundException(exception);
    }
}
