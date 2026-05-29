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
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.value.ConvertibleMultiValues;
import io.micronaut.core.convert.value.ConvertibleMultiValuesMap;
import io.micronaut.core.type.Argument;
import io.micronaut.http.BasicHttpAttributes;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.RequestArgumentBinder;
import io.micronaut.http.uri.UriMatchInfo;
import io.micronaut.http.uri.UriMatchVariable;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.UriRouteInfo;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A binder for binding arguments annotated with {@link PathParam}.
 *
 * @param <T> The argument type
 */
@Prototype
final class PathParamArgumentBinder<T> extends AbstractParamArgumentBinder<PathParam, T> {

    static final String URI_TEMPLATE_ATTRIBUTE = PathParamArgumentBinder.class.getName() + ".uriTemplate";

    /**
     * Constructor.
     *
     * @param conversionService       conversion service
     * @param paramConverterProviders param converter providers
     */
    public PathParamArgumentBinder(ConversionService conversionService, List<ParamConverterProvider> paramConverterProviders) {
        super(conversionService, paramConverterProviders);
    }

    /**
     * Constructor.
     *
     * @param conversionService      conversion service
     * @param argument               The argument
     * @param paramConverter         The paramConverter
     * @param elementParamConverter  The element paramConverter
     */
    private PathParamArgumentBinder(ConversionService conversionService,
                                    Argument<T> argument,
                                    @Nullable ParamConverter<T> paramConverter,
                                    @Nullable ParamConverter<?> elementParamConverter) {
        super(conversionService, argument, paramConverter, elementParamConverter);
    }

    @Override
    public Class<PathParam> getAnnotationType() {
        return PathParam.class;
    }

    @Override
    protected ConvertibleMultiValues<String> parameterValues(HttpRequest<?> source, Argument<T> argument) {
        return pathParameters(source, !argument.getAnnotationMetadata().hasAnnotation(Encoded.class));
    }

    @Override
    protected @Nullable BindingResult<T> bindDirect(ArgumentConversionContext<T> context, HttpRequest<?> source, String parameterName) {
        if (PathSegment.class.isAssignableFrom(context.getArgument().getType())) {
            PathSegment pathSegment = pathSegment(source, parameterName, !context.getArgument().getAnnotationMetadata().hasAnnotation(Encoded.class));
            if (pathSegment == null) {
                return BindingResult.unsatisfied();
            }
            return () -> Optional.of(context.getArgument().getType().cast(pathSegment));
        }
        return null;
    }

    @Override
    protected RequestArgumentBinder<T> createSpecific(Argument<T> argument,
                                                      @Nullable ParamConverter<T> paramConverter,
                                                      @Nullable ParamConverter<?> elementParamConverter) {
        return new PathParamArgumentBinder<>(conversionService, argument, paramConverter, elementParamConverter);
    }

    @Override
    protected RuntimeException conversionException(RuntimeException exception) {
        return new NotFoundException(exception);
    }

    private ConvertibleMultiValues<String> pathParameters(HttpRequest<?> source, boolean decode) {
        Map<CharSequence, List<String>> values = new LinkedHashMap<>();
        uriTemplate(source).ifPresent(template -> addTemplatePathParameters(values, template, source.getUri().getRawPath(), decode));
        if (values.isEmpty()) {
            BasicHttpAttributes.getRouteMatchInfo(source)
                .ifPresent(matchInfo -> addMatchPathParameters(values, matchInfo, source.getUri().getRawPath(), decode));
        }
        return new ConvertibleMultiValuesMap<>(values, conversionService);
    }

    private @Nullable PathSegment pathSegment(HttpRequest<?> source, String parameterName, boolean decode) {
        Optional<String> uriTemplate = uriTemplate(source);
        if (uriTemplate.isEmpty()) {
            return BasicHttpAttributes.getRouteMatchInfo(source)
                .map(matchInfo -> pathSegment(matchInfo, source.getUri().getRawPath(), parameterName, decode))
                .orElse(null);
        }
        List<String> templateSegments = pathSegments(uriTemplate.get());
        List<String> pathSegments = pathSegments(source.getUri().getRawPath());
        int pathOffset = Math.max(0, pathSegments.size() - templateSegments.size());
        int count = Math.min(templateSegments.size(), pathSegments.size() - pathOffset);
        for (int i = 0; i < count; i++) {
            String variableName = templateVariableName(templateSegments.get(i));
            if (parameterName.equals(variableName)) {
                return toPathSegment(pathSegments.get(pathOffset + i), decode);
            }
        }
        return null;
    }

    private static @Nullable PathSegment pathSegment(UriMatchInfo matchInfo, String rawPath, String parameterName, boolean decode) {
        List<UriMatchVariable> variables = matchInfo.getVariables();
        List<String> pathSegments = pathSegments(rawPath);
        int pathOffset = Math.max(0, pathSegments.size() - variables.size());
        int count = Math.min(variables.size(), pathSegments.size() - pathOffset);
        PathSegment result = null;
        for (int i = 0; i < count; i++) {
            if (parameterName.equals(variables.get(i).getName())) {
                result = toPathSegment(pathSegments.get(pathOffset + i), decode);
            }
        }
        return result;
    }

    private static void addTemplatePathParameters(Map<CharSequence, List<String>> values, String uriTemplate, String rawPath, boolean decode) {
        List<String> templateSegments = pathSegments(uriTemplate);
        List<String> pathSegments = pathSegments(rawPath);
        int pathOffset = Math.max(0, pathSegments.size() - templateSegments.size());
        int count = Math.min(templateSegments.size(), pathSegments.size() - pathOffset);
        for (int i = 0; i < count; i++) {
            String variableName = templateVariableName(templateSegments.get(i));
            if (variableName != null) {
                values.computeIfAbsent(variableName, ignored -> new ArrayList<>())
                    .add(pathValue(pathSegments.get(pathOffset + i), decode));
            }
        }
    }

    private static void addMatchPathParameters(Map<CharSequence, List<String>> values, UriMatchInfo matchInfo, String rawPath, boolean decode) {
        List<UriMatchVariable> variables = matchInfo.getVariables();
        List<String> pathSegments = pathSegments(rawPath);
        int pathOffset = Math.max(0, pathSegments.size() - variables.size());
        int count = Math.min(variables.size(), pathSegments.size() - pathOffset);
        for (int i = 0; i < count; i++) {
            values.computeIfAbsent(variables.get(i).getName(), ignored -> new ArrayList<>())
                .add(pathValue(pathSegments.get(pathOffset + i), decode));
        }
    }

    private static Optional<String> uriTemplate(HttpRequest<?> source) {
        return source.getAttribute(URI_TEMPLATE_ATTRIBUTE, String.class)
            .or(() -> RouteAttributes.getRouteInfo(source)
                .filter(UriRouteInfo.class::isInstance)
                .map(routeInfo -> ((UriRouteInfo<?, ?>) routeInfo).getUriMatchTemplate().toString()))
            .or(() -> BasicHttpAttributes.getUriTemplate(source));
    }

    private static @Nullable String templateVariableName(String templateSegment) {
        if (!templateSegment.startsWith("{")) {
            return null;
        }
        int end = templateSegment.indexOf('}');
        if (end < 0) {
            return null;
        }
        String variableName = templateSegment.substring(1, end);
        int colon = variableName.indexOf(':');
        if (colon > -1) {
            variableName = variableName.substring(0, colon);
        }
        int comma = variableName.indexOf(',');
        if (comma > -1) {
            variableName = variableName.substring(0, comma);
        }
        return variableName;
    }

    private static String pathValue(String rawSegment, boolean decode) {
        int matrixIndex = rawSegment.indexOf(';');
        String value = matrixIndex > -1 ? rawSegment.substring(0, matrixIndex) : rawSegment;
        return decode ? decode(value) : value;
    }

    private static PathSegment toPathSegment(String rawSegment, boolean decode) {
        String[] parts = rawSegment.split(";", -1);
        String path = decode ? decode(parts[0]) : parts[0];
        MultivaluedMap<String, String> matrixParameters = new MultivaluedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                continue;
            }
            int separator = part.indexOf('=');
            String name = separator > -1 ? part.substring(0, separator) : part;
            String value = separator > -1 ? part.substring(separator + 1) : "";
            matrixParameters.add(decode ? decode(name) : name, decode ? decode(value) : value);
        }
        return new DefaultPathSegment(path, matrixParameters);
    }

    private static List<String> pathSegments(String path) {
        int queryIndex = path.indexOf('?');
        if (queryIndex > -1) {
            path = path.substring(0, queryIndex);
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            return List.of();
        }
        List<String> segments = new ArrayList<>();
        int segmentStart = 0;
        int braceDepth = 0;
        for (int i = 0; i <= path.length(); i++) {
            boolean end = i == path.length();
            char c = end ? '\0' : path.charAt(i);
            if (!end) {
                if (c == '{') {
                    braceDepth++;
                } else if (c == '}' && braceDepth > 0) {
                    braceDepth--;
                }
            }
            if (end || (c == '/' && braceDepth == 0)) {
                segments.add(path.substring(segmentStart, i));
                segmentStart = i + 1;
            }
        }
        return segments;
    }

    private static String decode(String value) {
        try {
            return URI.create("/" + value).getPath().substring(1);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private record DefaultPathSegment(String path, MultivaluedMap<String, String> matrixParameters) implements PathSegment {
        @Override
        public String getPath() {
            return path;
        }

        @Override
        public MultivaluedMap<String, String> getMatrixParameters() {
            return matrixParameters;
        }
    }
}
