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

import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.ConvertibleMultiValues;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.RequestArgumentBinder;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A binder for binding arguments annotated with {@link HeaderParam}.
 *
 * @param <T> The argument type
 * @since 5.0.1
 */
@Prototype
final class HeaderParamArgumentBinder<T> extends AbstractParamArgumentBinder<HeaderParam, T> {

    /**
     * Constructor.
     *
     * @param conversionService conversion service
     * @param paramConverterProviders param converter providers
     */
    public HeaderParamArgumentBinder(ConversionService conversionService, List<ParamConverterProvider> paramConverterProviders) {
        super(conversionService, paramConverterProviders);
    }

    private HeaderParamArgumentBinder(ConversionService conversionService,
                                      Argument<T> argument,
                                      @Nullable ParamConverter<T> paramConverter,
                                      @Nullable ParamConverter<?> elementParamConverter) {
        super(conversionService, argument, paramConverter, elementParamConverter);
    }

    @Override
    public Class<HeaderParam> getAnnotationType() {
        return HeaderParam.class;
    }

    @Override
    protected ConvertibleMultiValues<String> parameterValues(HttpRequest<?> source, Argument<T> argument) {
        return source.getHeaders();
    }

    @Override
    protected RequestArgumentBinder<T> createSpecific(Argument<T> argument,
                                                      @Nullable ParamConverter<T> paramConverter,
                                                      @Nullable ParamConverter<?> elementParamConverter) {
        return new HeaderParamArgumentBinder<>(conversionService, argument, paramConverter, elementParamConverter);
    }

    @Override
    protected RuntimeException conversionException(RuntimeException exception) {
        return new BadRequestException(exception);
    }
}
