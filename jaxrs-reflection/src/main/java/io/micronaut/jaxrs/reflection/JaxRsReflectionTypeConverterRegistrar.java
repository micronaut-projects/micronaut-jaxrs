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
package io.micronaut.jaxrs.reflection;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.CharSequenceToEnumConverter;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.MutableConversionService;
import io.micronaut.core.convert.TypeConverterRegistrar;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.ext.ParamConverter;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Reflection-backed Jakarta REST conversion service fallback.
 */
@Internal
public final class JaxRsReflectionTypeConverterRegistrar implements TypeConverterRegistrar {
    @SuppressWarnings("rawtypes")
    private final CharSequenceToEnumConverter enumConverter = new CharSequenceToEnumConverter<>();

    @Override
    public void register(MutableConversionService conversionService) {
        conversionService.addConverter(CharSequence.class, Object.class, JaxRsReflectionTypeConverterRegistrar::convertObject);
        conversionService.addConverter(CharSequence.class, Enum.class, this::convertEnum);
    }

    @SuppressWarnings("unchecked")
    private static Optional<Object> convertObject(CharSequence value, Class<Object> targetType, ConversionContext context) {
        if (targetType == Object.class || targetType.isEnum()) {
            return Optional.empty();
        }
        return (Optional<Object>) convertWithParamConverter(value, targetType, context);
    }

    @SuppressWarnings("unchecked")
    private Optional<Enum> convertEnum(CharSequence value, Class<Enum> targetType, ConversionContext context) {
        Optional<Enum> converted = convertWithParamConverter(value, targetType, context);
        if (converted.isPresent()) {
            return converted;
        }
        return enumConverter.convert(value, targetType, context);
    }

    @SuppressWarnings("unchecked")
    private static <T> Optional<T> convertWithParamConverter(CharSequence value, Class<T> targetType, ConversionContext context) {
        ParamConverter<T> converter = JaxRsReflectionParamConverterProvider.findConverter(targetType);
        if (converter == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(converter.fromString(value.toString()));
        } catch (WebApplicationException e) {
            throw e;
        } catch (RuntimeException e) {
            RuntimeException conversionException = conversionException(context, e);
            if (conversionException != null) {
                throw conversionException;
            }
            context.reject(value, e);
            return Optional.empty();
        }
    }

    private static @Nullable RuntimeException conversionException(ConversionContext context, RuntimeException exception) {
        AnnotationMetadata annotationMetadata = context.getAnnotationMetadata();
        if (annotationMetadata.hasAnnotation(PathParam.class)
            || annotationMetadata.hasAnnotation(MatrixParam.class)
            || annotationMetadata.hasAnnotation(QueryParam.class)) {
            return new NotFoundException(exception);
        }
        if (annotationMetadata.hasAnnotation(HeaderParam.class)
            || annotationMetadata.hasAnnotation(CookieParam.class)
            || annotationMetadata.hasAnnotation(FormParam.class)) {
            return new BadRequestException(exception);
        }
        return null;
    }
}
