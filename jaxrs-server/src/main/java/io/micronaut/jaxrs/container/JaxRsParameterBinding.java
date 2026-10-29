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

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.PathVariables;
import io.micronaut.http.form.FormData;
import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import io.micronaut.jaxrs.runtime.ext.bind.JaxRsRequestUri;
import io.micronaut.jaxrs.runtime.ext.bind.UriInfoImpl;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The binding of the parameters of resource methods, resource classes and bean parameters: how
 * the readers of the routes read the values of a request, convert them, with the
 * {@link ParamConverterProvider}s, and apply their default values.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
final class JaxRsParameterBinding {

    private final JaxRsRouteSupport support;

    private final ApplicationProvider applicationProvider;

    private final ConversionService conversionService;

    private final Supplier<List<ParamConverterProvider>> paramConverterProviders;

    private final Map<ConverterKey, StringConverter> converters = new ConcurrentHashMap<>();

    private final Map<Class<?>, BeanParamBinder> beanParams = new ConcurrentHashMap<>();

    /**
     * @param support                 The route support, which reads the context values and the parts of forms
     * @param applicationProvider     The application
     * @param conversionService       The conversion service
     * @param paramConverterProviders The registered parameter converter providers, resolved when first used
     */
    JaxRsParameterBinding(JaxRsRouteSupport support, ApplicationProvider applicationProvider, ConversionService conversionService,
                          Supplier<List<ParamConverterProvider>> paramConverterProviders) {
        this.support = support;
        this.applicationProvider = applicationProvider;
        this.conversionService = conversionService;
        this.paramConverterProviders = paramConverterProviders;
    }

    /**
     * @param request       The request
     * @param pathVariables The path variables of the matched route
     * @param name          The name of the path parameter
     * @param conversion    The conversion of the parameter
     * @param encoded       Whether the value is kept encoded ({@code @Encoded})
     * @return The value, converted
     */
    private @Nullable Object pathParam(HttpRequest<?> request, PathVariables pathVariables, String name, Conversion conversion,
                                       boolean encoded) {
        Argument<?> argument = conversion.argument;
        // an @Encoded parameter gets the value as it appears in the path
        PathVariables variables = encoded ? pathVariables.raw() : pathVariables;
        Optional<String> value = variables.findString(name);
        Class<?> type = argument.getType();
        if (type == PathSegment.class) {
            return value.map(v -> JaxRsPathSegments.pathSegment(request, name, v, encoded)).orElse(null);
        }
        if (type == List.class && argument.getFirstTypeVariable().map(Argument::getType).orElse(null) == PathSegment.class) {
            // a variable over several segments
            return value.map(v -> JaxRsPathSegments.pathSegments(request, name, v, encoded))
                .orElse(List.of());
        }
        List<String> values;
        if (value.isEmpty()) {
            values = List.of();
        } else {
            // a variable that repeats: the values of every occurrence, the last one first
            Optional<String> earlier = variables.findString(JaxRsRouteTemplateEngine.repeated(name, 1));
            if (earlier.isEmpty()) {
                values = List.of(value.get());
            } else {
                values = new ArrayList<>();
                values.add(value.get());
                for (int i = 1; earlier.isPresent(); earlier = variables.findString(JaxRsRouteTemplateEngine.repeated(name, ++i))) {
                    values.add(earlier.get());
                }
            }
        }
        return conversion.convert(values, true);
    }

    /**
     * @param request      The request
     * @param name         The name of the query parameter
     * @param conversion   The conversion of the parameter
     * @param encoded      Whether the value is kept encoded ({@code @Encoded})
     * @return The value, converted
     */
    private @Nullable Object queryParam(HttpRequest<?> request, String name, Conversion conversion, boolean encoded) {
        // the name is decoded, with @Encoded the values are not
        List<String> values = JaxRsRequestUri.of(request).rawQueryValues(name);
        if (!encoded && !values.isEmpty()) {
            List<String> decoded = new ArrayList<>(values.size());
            for (String value : values) {
                decoded.add(UriInfoImpl.decodeQuery(value));
            }
            values = decoded;
        }
        return conversion.convert(values, true);
    }

    /**
     * @param request      The request
     * @param name         The name of the matrix parameter, of the last segment of the path, or
     *                     of the last one a sub-resource locator matched
     * @param conversion   The conversion of the parameter
     * @param encoded      Whether the value is kept encoded ({@code @Encoded})
     * @return The value, converted
     */
    private @Nullable Object matrixParam(HttpRequest<?> request, String name, Conversion conversion, boolean encoded) {
        int matched = request.getAttribute(JaxRsRouteSupport.MATCHED_SEGMENTS, Integer.class).orElse(-1);
        if (matched > 0) {
            // a parameter of a sub-resource locator: of the last segment it matched
            List<PathSegment> segments = new UriInfoImpl(request, applicationProvider.getPath(), applicationProvider.getContextPath())
                .getPathSegments(!encoded);
            List<String> values = new ArrayList<>(1);
            if (matched <= segments.size()) {
                // the name is decoded, with @Encoded the values are not
                segments.get(matched - 1).getMatrixParameters().forEach((key, keyValues) -> {
                    if ((encoded ? JaxRsPathSegments.decode(key) : key).equals(name)) {
                        keyValues.forEach(value -> values.add(value == null ? "" : value));
                    }
                });
            }
            return conversion.convert(values, true);
        }
        return conversion.convert(JaxRsPathSegments.values(request, name, encoded), true);
    }

    /**
     * @param request      The request
     * @param name         The name of the header
     * @param conversion   The conversion of the parameter
     * @return The value, converted
     */
    private static @Nullable Object headerParam(HttpRequest<?> request, String name, Conversion conversion) {
        return conversion.convert(request.getHeaders().getAll(name), false);
    }

    /**
     * @param request      The request
     * @param name         The name of the cookie
     * @param conversion   The conversion of the parameter
     * @return The value, converted
     */
    private static @Nullable Object cookieParam(HttpRequest<?> request, String name, Conversion conversion) {
        Optional<io.micronaut.http.cookie.Cookie> cookie = request.getCookies().findCookie(name);
        if (conversion.argument.getType() == Cookie.class) {
            String defaultValue = conversion.defaultValue;
            if (cookie.isPresent()) {
                io.micronaut.http.cookie.Cookie c = cookie.get();
                return new Cookie.Builder(c.getName()).value(c.getValue()).path(c.getPath()).domain(c.getDomain()).build();
            }
            return defaultValue == null ? null : new Cookie.Builder(name).value(defaultValue).build();
        }
        return conversion.convert(cookie.map(c -> List.of(c.getValue())).orElse(List.of()), false);
    }

    /**
     * @param request      The request
     * @param form         The form
     * @param name         The name of the field
     * @param conversion   The conversion of the parameter
     * @param encoded      Whether the value is kept encoded ({@code @Encoded})
     * @return The value, converted
     */
    private @Nullable Object formParam(HttpRequest<?> request, @Nullable FormData form, String name, Conversion conversion,
                                       boolean encoded) {
        Class<?> type = conversion.argument.getType();
        if (form == null) {
            // no form entity: the default, never the query (FormParam javadoc)
            return type == EntityPart.class || type == InputStream.class ? null : conversion.convert(List.of(), false);
        }
        if (type == EntityPart.class || type == InputStream.class) {
            // a part of a multipart form (JAX-RS 3.1 section 3.5.4)
            EntityPart part = support.formPart(request, form, name);
            return part == null || type == EntityPart.class ? part : part.getContent();
        }
        // the form of a route with an @Encoded form parameter is read with its raw values
        List<String> values = form.getValues(name);
        if (JaxRsRouteSupport.isRawForm(request)) {
            if (!encoded) {
                Charset charset = JaxRsRouteSupport.formCharset(request);
                values = values.stream().map(value -> URLDecoder.decode(value, charset)).toList();
            }
        } else if (encoded) {
            // the decoded values of a form read for parameters that are not @Encoded: form encoding, a space is a plus
            Charset charset = JaxRsRouteSupport.formCharset(request);
            values = values.stream().map(value -> URLEncoder.encode(value, charset)).toList();
        }
        return conversion.convert(values, false);
    }

    /**
     * The binder of a {@code @BeanParam} type, built once: not in a {@code computeIfAbsent}, as
     * building it builds the binders of the bean parameters it has.
     */
    private BeanParamBinder beanParamBinder(Class<?> type) {
        BeanParamBinder binder = beanParams.get(type);
        if (binder == null) {
            binder = newBeanParamBinder(type);
            BeanParamBinder existing = beanParams.putIfAbsent(type, binder);
            if (existing != null) {
                binder = existing;
            }
        }
        return binder;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private BeanParamBinder newBeanParamBinder(Class<?> type) {
        BeanIntrospection<Object> introspection = (BeanIntrospection<Object>) Optional.ofNullable(JaxRsReflection.get().introspection(type))
            .orElseThrow(() -> new IllegalStateException("The @BeanParam type " + type.getName() + " is not introspected"));
        boolean encoded = introspection.hasAnnotation(Encoded.class);
        Argument<?>[] constructorArguments = introspection.getConstructorArguments();
        ValueReader[] constructorReaders = new ValueReader[constructorArguments.length];
        for (int i = 0; i < constructorArguments.length; i++) {
            ValueReader reader = reader(constructorArguments[i].getAnnotationMetadata(), constructorArguments[i], encoded);
            constructorReaders[i] = reader == null ? (r, p, f) -> null : reader;
        }
        List<Map.Entry<BeanProperty<Object, Object>, ValueReader>> properties = new ArrayList<>();
        for (BeanProperty<Object, Object> property : introspection.getBeanProperties()) {
            if (property.isReadOnly()) {
                continue;
            }
            ValueReader reader = reader(property.getAnnotationMetadata(), property.asArgument(), encoded);
            if (reader != null) {
                properties.add(Map.entry(property, reader));
            }
        }
        return (request, pathVariables, form) -> {
            Object[] arguments = new Object[constructorReaders.length];
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = constructorReaders[i].read(request, pathVariables, form);
            }
            Object instance = arguments.length == 0 ? introspection.instantiate() : introspection.instantiate(arguments);
            for (Map.Entry<BeanProperty<Object, Object>, ValueReader> property : properties) {
                property.getKey().set(instance, property.getValue().read(request, pathVariables, form));
            }
            return instance;
        };
    }

    /**
     * How to read the value of an annotated constructor argument or property of a bean parameter.
     */
    @Nullable ValueReader reader(AnnotationMetadata metadata, Argument<?> argument, boolean encodedType) {
        String defaultValue = metadata.stringValue(DefaultValue.class).orElse(null);
        boolean encoded = encodedType || metadata.hasAnnotation(Encoded.class);
        String pathParam = metadata.stringValue(PathParam.class).orElse(null);
        String queryParam = metadata.stringValue(QueryParam.class).orElse(null);
        String matrixParam = metadata.stringValue(MatrixParam.class).orElse(null);
        String headerParam = metadata.stringValue(HeaderParam.class).orElse(null);
        String cookieParam = metadata.stringValue(CookieParam.class).orElse(null);
        String formParam = metadata.stringValue(FormParam.class).orElse(null);
        Conversion conversion = new Conversion(argument, defaultValue);
        if (defaultValue != null && (pathParam != null || queryParam != null || matrixParam != null || headerParam != null
            || cookieParam != null || formParam != null)) {
            convertDefault(conversion, defaultValue);
        }
        if (pathParam != null) {
            return (request, pathVariables, form) -> pathParam(request, pathVariables, pathParam, conversion, encoded);
        } else if (queryParam != null) {
            return (request, pathVariables, form) -> queryParam(request, queryParam, conversion, encoded);
        } else if (matrixParam != null) {
            return (request, pathVariables, form) -> matrixParam(request, matrixParam, conversion, encoded);
        } else if (headerParam != null) {
            return (request, pathVariables, form) -> headerParam(request, headerParam, conversion);
        } else if (cookieParam != null) {
            return (request, pathVariables, form) -> cookieParam(request, cookieParam, conversion);
        } else if (formParam != null) {
            return (request, pathVariables, form) -> formParam(request, form, formParam, conversion, encoded);
        } else if (metadata.hasAnnotation(BeanParam.class)) {
            // built with the routes: the default values of its members are converted eagerly too
            BeanParamBinder binder = beanParamBinder(argument.getType());
            return binder::bind;
        } else if (metadata.hasAnnotation(jakarta.ws.rs.core.Context.class)) {
            String named = metadata.stringValue("jakarta.inject.Named").orElse(null);
            return (request, pathVariables, form) -> support.context(request, argument, named);
        }
        return null;
    }

    /**
     * Convert the default value of a parameter with its {@link ParamConverter} when the routes are
     * built: a converter that is not {@link ParamConverter.Lazy} converts it eagerly, and a
     * failure fails the deployment (see {@link ParamConverter}).
     */
    private void convertDefault(Conversion conversion, String defaultValue) {
        Argument<?> argument = conversion.argument;
        Argument<?> target = conversion.target;
        Annotation[] annotations = target.synthesizeAll();
        for (ParamConverterProvider provider : paramConverterProviders.get()) {
            ParamConverter<?> converter = provider.getConverter(target.getType(), target.asType(), annotations);
            if (converter == null) {
                continue;
            }
            if (!JaxRsReflection.get().annotationMetadata(converter.getClass()).hasAnnotation(ParamConverter.Lazy.class)) {
                try {
                    converter.fromString(defaultValue);
                } catch (RuntimeException e) {
                    throw new IllegalStateException("The default value [" + defaultValue + "] of the parameter " + argument.getName()
                        + " cannot be converted to " + target.getTypeName() + ": " + e.getMessage(), e);
                }
            }
            return;
        }
    }

    /**
     * The type of the elements of a collection or array parameter, carrying the annotations of the
     * parameter: a {@link ParamConverterProvider} converting an element sees them.
     */
    private static Argument<?> element(Argument<?> element, Argument<?> parameter) {
        return Argument.of(element.getType(), parameter.getName(), parameter.getAnnotationMetadata(), element.getTypeParameters());
    }

    private @Nullable Object missing(Argument<?> argument) {
        Class<?> type = argument.getType();
        if (type == Optional.class) {
            return Optional.empty();
        }
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return conversionService.convertRequired(0, type);
    }

    /**
     * How a parameter of a type is converted from a string, resolved once: by a
     * {@link ParamConverterProvider}, else by the rules of JAX-RS for the type (a static
     * {@code fromString} or {@code valueOf} method, or a constructor with a string), else by the
     * conversion service.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private StringConverter converter(Argument<?> argument) {
        Class<?> type = argument.getType();
        Annotation[] annotations = argument.synthesizeAll();
        for (ParamConverterProvider provider : paramConverterProviders.get()) {
            ParamConverter paramConverter = provider.getConverter(type, argument.asType(), annotations);
            if (paramConverter != null) {
                return paramConverter::fromString;
            }
        }
        if (type == String.class) {
            return value -> value;
        }
        // a static valueOf or fromString method, or a constructor with a string (JAX-RS 3.2)
        java.util.function.Function<String, Object> conversion = JaxRsReflection.get().stringConversion(type);
        if (conversion != null) {
            return conversion::apply;
        }
        return value -> conversionService.convert(value, argument).orElse(null);
    }

    /**
     * Reads a value of the request.
     */
    @FunctionalInterface
    interface ValueReader {
        @Nullable Object read(HttpRequest<?> request, PathVariables pathVariables, @Nullable FormData form);
    }

    /**
     * Creates a bean parameter.
     */
    @FunctionalInterface
    private interface BeanParamBinder {
        Object bind(HttpRequest<?> request, PathVariables pathVariables, @Nullable FormData form);
    }

    /**
     * Converts the string value of a parameter.
     */
    @FunctionalInterface
    private interface StringConverter {
        @Nullable Object convert(String value) throws Exception;
    }

    /**
     * The key of the converter of a parameter: its type, and its annotations.
     *
     * @param argument    The parameter
     * @param annotations The annotations of the parameter
     */
    private record ConverterKey(Argument<?> argument, AnnotationMetadata annotations) {
    }

    /**
     * How the string values of a parameter are converted to its type, decided when its reader is
     * built: its shape (a collection, an array or a single value), the type of its elements, and
     * the converter of the elements, resolved when first used.
     */
    private final class Conversion {
        private final Argument<?> argument;
        private final @Nullable String defaultValue;
        private final Class<?> type;
        // the type a converter converts to: the elements of a collection or array, else the parameter
        private final Argument<?> target;
        private final boolean collection;
        private volatile @Nullable StringConverter converter;

        Conversion(Argument<?> argument, @Nullable String defaultValue) {
            this.argument = argument;
            this.defaultValue = defaultValue;
            this.type = argument.getType();
            this.collection = type == List.class || type == Set.class || type == SortedSet.class || type == Collection.class;
            if (collection) {
                target = element(argument.getFirstTypeVariable().orElse(Argument.STRING), argument);
            } else if (type.isArray()) {
                target = element(Argument.of(type.getComponentType()), argument);
            } else {
                target = argument;
            }
        }

        @SuppressWarnings("unchecked")
        @Nullable Object convert(List<String> values, boolean notFound) {
            if (collection) {
                List<String> elements = values.isEmpty() && defaultValue != null ? List.of(defaultValue) : values;
                Collection<Object> result = type == Set.class ? new LinkedHashSet<>() : type == SortedSet.class ? new TreeSet<>() : new ArrayList<>();
                for (String element : elements) {
                    result.add(convertOne(element, notFound));
                }
                // read-only, like the collections of every parameter
                if (type == List.class || type == Collection.class) {
                    return List.copyOf(result);
                }
                return type == SortedSet.class ? Collections.unmodifiableSortedSet((SortedSet<Object>) result) : Collections.unmodifiableSet((Set<Object>) result);
            }
            if (type.isArray()) {
                List<String> elements = values.isEmpty() && defaultValue != null ? List.of(defaultValue) : values;
                List<Object> converted = new ArrayList<>(elements.size());
                for (String element : elements) {
                    converted.add(convertOne(element, notFound));
                }
                return conversionService.convertRequired(converted, type);
            }
            String value = values.isEmpty() ? defaultValue : values.get(0);
            if (value == null) {
                return missing(argument);
            }
            return convertOne(value, notFound);
        }

        private Object convertOne(String value, boolean notFound) {
            Object converted;
            try {
                converted = converter().convert(value);
            } catch (WebApplicationException e) {
                throw e;
            } catch (Exception e) {
                throw notFound ? new NotFoundException(e) : new BadRequestException(e);
            }
            if (converted == null) {
                IllegalArgumentException cause = new IllegalArgumentException("Cannot convert [" + value + "] to " + target.getTypeName());
                throw notFound ? new NotFoundException(cause) : new BadRequestException(cause);
            }
            return converted;
        }

        /**
         * The converter of the elements, of the shared ones: the annotations are part of the key,
         * as a {@link ParamConverterProvider} sees them, and arguments are equal without them.
         */
        private StringConverter converter() {
            StringConverter c = converter;
            if (c == null) {
                c = converters.computeIfAbsent(new ConverterKey(target, target.getAnnotationMetadata()),
                    key -> JaxRsParameterBinding.this.converter(key.argument()));
                converter = c;
            }
            return c;
        }
    }
}
